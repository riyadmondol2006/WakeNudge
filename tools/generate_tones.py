#!/usr/bin/env python3
"""Synthesizes WakeNudge's bundled alarm tones from scratch and loudness-normalizes them.

Every tone is generated procedurally (additive / FM synthesis), so there are no third-party samples and no
licensing questions. Each file is normalized to a target integrated loudness (ITU-R BS.1770-4 K-weighting with
gating, implemented below) behind a soft limiter with a -1 dBFS ceiling, then encoded as Ogg Vorbis with ffmpeg.

Usage:  python3 tools/generate_tones.py [output_dir]      (default: app/src/main/res/raw)
Requires: python3 (standard library only) and ffmpeg with libvorbis.
"""
import json
import math
import random
import struct
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

SR = 32000
TAU = 2 * math.pi
CEILING = 10 ** (-1.0 / 20)  # -1 dBFS


# ---------------------------------------------------------------------------------------------- primitives
def buf(seconds):
    return [0.0] * int(seconds * SR)


def add(dst, src, at):
    start = int(at * SR)
    for i, v in enumerate(src):
        j = start + i
        if j >= len(dst):
            break
        dst[j] += v


def env_adsr(n, a, d, s, r):
    """Envelope of n samples; a/d/r in seconds, s sustain level."""
    out = [0.0] * n
    na, nd, nr = int(a * SR), int(d * SR), int(r * SR)
    for i in range(n):
        if i < na:
            out[i] = i / max(1, na)
        elif i < na + nd:
            out[i] = 1.0 - (1.0 - s) * (i - na) / max(1, nd)
        elif i < n - nr:
            out[i] = s
        else:
            out[i] = s * max(0.0, (n - i) / max(1, nr))
    return out


def tone(freq, dur, partials, decay, attack=0.004, vel=1.0):
    """Additive tone. partials = [(ratio, amp)], exponential decay with rate `decay` (1/s)."""
    n = int(dur * SR)
    out = [0.0] * n
    for ratio, amp in partials:
        f = freq * ratio
        if f >= SR / 2:
            continue
        # higher partials die faster, like real struck/plucked bodies
        d = decay * (1 + 0.35 * (ratio - 1))
        ph = TAU * f / SR
        for i in range(n):
            t = i / SR
            a = min(1.0, t / attack) if attack > 0 else 1.0
            out[i] += amp * a * math.exp(-d * t) * math.sin(ph * i)
    return [v * vel for v in out]


def fm_bell(freq, dur, ratio=3.5, index=4.0, decay=3.0, vel=1.0):
    n = int(dur * SR)
    out = [0.0] * n
    for i in range(n):
        t = i / SR
        idx = index * math.exp(-decay * 1.6 * t)
        mod = math.sin(TAU * freq * ratio * t) * idx
        out[i] = math.exp(-decay * t) * math.sin(TAU * freq * t + mod) * min(1.0, t / 0.003)
    return [v * vel for v in out]


def square(freq, dur, duty=0.5, harmonics=9):
    """Band-limited-ish square/pulse via odd harmonics."""
    n = int(dur * SR)
    out = [0.0] * n
    for h in range(1, harmonics * 2, 2):
        if freq * h >= SR / 2:
            break
        amp = 1.0 / h
        ph = TAU * freq * h / SR
        for i in range(n):
            out[i] += amp * math.sin(ph * i)
    return [v * 0.9 for v in out]


def saw(freq, dur, harmonics=24):
    n = int(dur * SR)
    out = [0.0] * n
    for h in range(1, harmonics + 1):
        if freq * h >= SR / 2:
            break
        amp = 1.0 / h
        ph = TAU * freq * h / SR
        for i in range(n):
            out[i] += amp * math.sin(ph * i)
    return [v * 0.6 for v in out]


def lowpass(x, cutoff):
    a = 1 - math.exp(-TAU * cutoff / SR)
    y, s = [], 0.0
    for v in x:
        s += a * (v - s)
        y.append(s)
    return y


def apply_env(x, env):
    return [v * e for v, e in zip(x, env)]


def noise(dur, seed=1):
    rnd = random.Random(seed)
    return [rnd.uniform(-1, 1) for _ in range(int(dur * SR))]


def hz(note):
    """note name like 'C5', 'F#4' -> frequency."""
    names = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
    p = names[note[0]]
    i = 1
    if note[i] == "#":
        p += 1
        i += 1
    elif note[i] == "b":
        p -= 1
        i += 1
    octave = int(note[i:])
    return 440.0 * 2 ** ((p + (octave - 4) * 12 - 9) / 12)


# ---------------------------------------------------------------------------------------------- loudness
def _biquad(x, b, a):
    b0, b1, b2 = b
    a1, a2 = a
    x1 = x2 = y1 = y2 = 0.0
    out = []
    for v in x:
        y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        out.append(y)
        x2, x1 = x1, v
        y2, y1 = y1, y
    return out


def k_weight(x):
    # Stage 1: high-shelf (+4 dB @ ~1.68 kHz); Stage 2: high-pass @ 38 Hz  (BS.1770-4)
    G, Q, fc = 3.999843853973347, 0.7071752369554196, 1681.9744509555319
    A = 10 ** (G / 40)
    w0 = TAU * fc / SR
    alpha = math.sin(w0) / (2 * Q)
    c = math.cos(w0)
    sa = 2 * math.sqrt(A) * alpha
    b = [A * ((A + 1) + (A - 1) * c + sa), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sa)]
    a = [(A + 1) - (A - 1) * c + sa, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sa]
    s1 = _biquad(x, [v / a[0] for v in b], [a[1] / a[0], a[2] / a[0]])

    Q2, fc2 = 0.5003270373238773, 38.13547087613982
    w = TAU * fc2 / SR
    al = math.sin(w) / (2 * Q2)
    cw = math.cos(w)
    b2 = [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2]
    a2 = [1 + al, -2 * cw, 1 - al]
    return _biquad(s1, [v / a2[0] for v in b2], [a2[1] / a2[0], a2[2] / a2[0]])


def integrated_lufs(x):
    y = k_weight(x)
    block, step = int(0.4 * SR), int(0.1 * SR)
    powers = []
    for s in range(0, max(1, len(y) - block + 1), step):
        seg = y[s:s + block]
        powers.append(sum(v * v for v in seg) / len(seg))
    if not powers:
        powers = [sum(v * v for v in y) / max(1, len(y))]
    lufs = lambda p: -0.691 + 10 * math.log10(p) if p > 0 else -200.0
    absolute = [p for p in powers if lufs(p) > -70]
    if not absolute:
        return -70.0
    rel_gate = lufs(sum(absolute) / len(absolute)) - 10
    gated = [p for p in absolute if lufs(p) > rel_gate]
    return lufs(sum(gated) / len(gated))


def soft_limit(x, ceiling=CEILING):
    """Transparent below ~70 % of the ceiling, tanh-shaped above it; never exceeds the ceiling."""
    knee = 0.7 * ceiling
    out = []
    for v in x:
        a = abs(v)
        if a <= knee:
            out.append(v)
        else:
            over = (a - knee) / (ceiling - knee)
            out.append(math.copysign(knee + (ceiling - knee) * math.tanh(over), v))
    return out


def normalize(x, target_lufs):
    gain = 10 ** ((target_lufs - integrated_lufs(x)) / 20)
    y = x
    for _ in range(12):
        y = soft_limit([v * gain for v in x])
        measured = integrated_lufs(y)
        err = target_lufs - measured
        if abs(err) < 0.25:
            break
        gain *= 10 ** (err / 20)
    return y, integrated_lufs(y)


def fade_edges(x, ms=6):
    n = int(ms / 1000 * SR)
    for i in range(min(n, len(x))):
        f = i / n
        x[i] *= f
        x[-1 - i] *= f
    return x


# ---------------------------------------------------------------------------------------------- the tones
def t_sunrise():
    out = buf(7.0)
    notes = ["C5", "E5", "G5", "A5", "C6", "E6", "G6", "E6"]
    for rep in range(2):
        for i, n in enumerate(notes):
            vel = 0.5 + 0.5 * (i / len(notes)) + 0.12 * rep
            add(out, fm_bell(hz(n), 1.6, ratio=2.0, index=1.6, decay=2.2, vel=vel * 0.55), rep * 3.4 + i * 0.36)
    return out


def t_chime():
    out = buf(5.0)
    for i, n in enumerate(["E6", "C6", "G5", "C6"]):
        add(out, tone(hz(n), 2.2, [(1, 1), (2.76, 0.35), (5.4, 0.15)], decay=2.4, vel=0.8), i * 0.55)
    add(out, tone(hz("C5"), 2.4, [(1, 1), (2.0, 0.4), (3.0, 0.2)], decay=1.6, vel=0.5), 2.6)
    return out


def t_bell():
    out = buf(6.0)
    partials = [(0.5, 0.6), (1.0, 1.0), (1.19, 0.5), (1.56, 0.45), (2.0, 0.6), (2.74, 0.4), (3.0, 0.3), (4.07, 0.25), (5.4, 0.15)]
    for i in range(4):
        add(out, tone(hz("G4"), 3.2, partials, decay=1.5, attack=0.002, vel=0.9), i * 1.45)
    return out


def t_marimba():
    out = buf(4.5)
    seq = ["C5", "E5", "G5", "C6", "G5", "E5", "D5", "F5", "A5", "D6", "A5", "F5"]
    for rep in range(2):
        for i, n in enumerate(seq):
            add(out, tone(hz(n), 0.7, [(1, 1), (3.99, 0.5), (9.9, 0.18)], decay=9, attack=0.001, vel=0.9), rep * 2.2 + i * 0.16)
    return out


def t_kalimba():
    out = buf(5.5)
    seq = [("G5", 0.0), ("B5", 0.3), ("D6", 0.6), ("B5", 0.9), ("A5", 1.5), ("C6", 1.8), ("E6", 2.1), ("C6", 2.4), ("G5", 3.0), ("D6", 3.3), ("G6", 3.6)]
    for n, at in seq:
        add(out, tone(hz(n), 1.4, [(1, 1), (5.4, 0.22), (8.9, 0.06)], decay=3.6, attack=0.0015, vel=0.85), at)
    return out


def t_digital():
    out = buf(3.0)
    beep = square(2400, 0.07)
    beep = apply_env(beep, env_adsr(len(beep), 0.002, 0.0, 1.0, 0.004))
    for burst in range(2):
        for i in range(4):
            add(out, beep, burst * 1.5 + i * 0.12)
    return out


def t_pulse():
    n_s = 4.0
    base = [a + b for a, b in zip(saw(220, n_s), saw(330.7, n_s))]
    base = lowpass(base, 1800)
    am = [0.5 + 0.5 * math.sin(TAU * 4 * i / SR - math.pi / 2) for i in range(len(base))]
    am = [a ** 1.6 for a in am]
    return fade_edges([b * a * 0.8 for b, a in zip(base, am)], 20)


def t_radar():
    out = buf(3.2)
    for k in range(2):
        n = int(1.2 * SR)
        sweep = []
        ph = 0.0
        for i in range(n):
            f = 700 + 1300 * (i / n) ** 1.4
            ph += TAU * f / SR
            sweep.append(math.sin(ph))
        sweep = apply_env(sweep, env_adsr(n, 0.02, 0.1, 0.8, 0.25))
        add(out, sweep, k * 1.6)
        add(out, tone(2200, 0.5, [(1, 1)], decay=7, vel=0.5), k * 1.6 + 1.2)
    return out


def t_classic():
    out = buf(3.4)
    n = int(2.0 * SR)
    rnd = random.Random(7)
    clatter = [0.0] * n
    for hit in range(int(2.0 * 22)):
        at = hit / 22.0
        b = tone(1250 if hit % 2 else 1190, 0.12, [(1, 1), (2.32, 0.7), (3.9, 0.5), (6.1, 0.3)], decay=22, attack=0.0008, vel=0.9)
        nz = [rnd.uniform(-1, 1) * math.exp(-300 * i / SR) * 0.25 for i in range(int(0.01 * SR))]
        add(clatter, [x + (nz[i] if i < len(nz) else 0) for i, x in enumerate(b)], at)
    add(out, clatter, 0.0)
    return out


def t_beacon():
    out = buf(3.6)
    for rep in range(3):
        for i, n in enumerate(["A5", "C#6", "E6"]):
            s = square(hz(n), 0.22, harmonics=7)
            s = lowpass(s, 5200)
            s = apply_env(s, env_adsr(len(s), 0.004, 0.03, 0.85, 0.05))
            add(out, s, rep * 1.2 + i * 0.25)
    return out


def t_siren():
    n = int(4.0 * SR)
    out = []
    ph = 0.0
    for i in range(n):
        t = i / SR
        f = 900 + 380 * math.sin(TAU * 0.75 * t)
        ph += TAU * f / SR
        out.append(0.7 * math.sin(ph) + 0.35 * math.sin(2 * ph) + 0.2 * math.sin(3 * ph))
    return fade_edges(out, 15)


def t_ember():
    n_s = 8.0
    pad = [0.0] * int(n_s * SR)
    for f, d in [(130.8, 0.0), (196.0, 0.4), (261.6, -0.3), (329.6, 0.2)]:
        a = saw(f * (1 + 0.003 * d), n_s)
        b = saw(f * (1 - 0.003 * d), n_s)
        for i in range(len(pad)):
            pad[i] += (a[i] + b[i]) * 0.18
    pad = lowpass(pad, 900)
    swell = [min(1.0, i / (1.5 * SR)) * (0.7 + 0.3 * math.sin(TAU * 0.25 * i / SR)) for i in range(len(pad))]
    pad = [p * s for p, s in zip(pad, swell)]
    for i, n in enumerate(["E5", "G5", "B5", "E6", "B5", "G5"]):
        add(pad, fm_bell(hz(n), 2.2, ratio=3.0, index=1.2, decay=1.8, vel=0.5), 1.0 + i * 1.0)
    return fade_edges(pad, 30)


def t_intensify():
    """Piercing layer that joins the main tone when an alarm has been ignored for 40 s."""
    n_s = 2.4
    out = buf(n_s)
    for rep in range(6):
        s = [a + b for a, b in zip(square(2960, 0.14, harmonics=5), square(3520, 0.14, harmonics=5))]
        s = apply_env(s, env_adsr(len(s), 0.002, 0.0, 1.0, 0.01))
        add(out, s, rep * 0.2)
    return out


def t_backup():
    """Distinct, urgent tone for backup alarms: two-tone siren plus beacon stabs."""
    base = t_siren()
    out = buf(4.0)
    add(out, base, 0.0)
    for i in range(8):
        s = square(1760, 0.1, harmonics=5)
        s = apply_env(s, env_adsr(len(s), 0.002, 0.0, 1.0, 0.01))
        add(out, [v * 0.6 for v in s], 0.25 + i * 0.5)
    return out


# id -> (generator, target integrated loudness in LUFS)
TONES = {
    "sunrise": (t_sunrise, -17.0),
    "chime": (t_chime, -16.0),
    "bell": (t_bell, -15.0),
    "marimba": (t_marimba, -15.0),
    "kalimba": (t_kalimba, -16.0),
    "ember": (t_ember, -18.0),
    "digital": (t_digital, -13.0),
    "pulse": (t_pulse, -14.0),
    "radar": (t_radar, -14.0),
    "classic": (t_classic, -13.0),
    "beacon": (t_beacon, -12.0),
    "siren": (t_siren, -11.0),
    "intensify": (t_intensify, -10.0),
    "backup": (t_backup, -10.0),
}


def encode_ogg(samples, path):
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = Path(tmp) / "t.wav"
        with wave.open(str(wav_path), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v)) * 32767)) for v in samples))
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav_path), "-c:a", "libvorbis", "-q:a", "5", str(path)],
            check=True,
        )


def main():
    out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/raw")
    out_dir.mkdir(parents=True, exist_ok=True)
    report = {}
    for tone_id, (gen, target) in TONES.items():
        raw = gen()
        raw = fade_edges(raw, 6)
        norm, measured = normalize(raw, target)
        peak = max(abs(v) for v in norm)
        encode_ogg(norm, out_dir / f"tone_{tone_id}.ogg")
        report[tone_id] = {"seconds": round(len(norm) / SR, 2), "target_lufs": target,
                           "measured_lufs": round(measured, 2), "peak_dbfs": round(20 * math.log10(peak), 2)}
        print(f"{tone_id:10s} {len(norm) / SR:5.1f}s  target {target:6.1f}  measured {measured:6.2f} LUFS  peak {20 * math.log10(peak):5.2f} dBFS")
    (Path(__file__).resolve().parent / "tones_report.json").write_text(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
