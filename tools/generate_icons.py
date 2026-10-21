#!/usr/bin/env python3
"""WakeNudge icon generator: one source of truth for the launcher icon, the status-bar glyphs and every
PNG (Play Store icon, feature graphic, README logo, contact sheet).

The mark: a "sun-clock". A soft 9-lobed cookie (the same Material 3 Expressive cookie that the app's
switches and day chips morph into) is the sun and the clock face; two bold night-indigo hands read 7:00.
The cookie's wobble is the gentle buzz of the alarm: the nudge.

Geometry lives only in Part 4. The script writes the Android VectorDrawable XML, then renders every PNG
by parsing that very XML with the small renderer in Part 1 (Bezier/arc flattening + scanline fill with
nonZero/evenOdd rules, linear/radial gradients, 4-8x supersampling), so the PNGs always match the XML.

Usage (from the repository root, needs python3 + Pillow only):
    python3 tools/generate_icons.py            # write XML + PNGs
    python3 tools/generate_icons.py --check    # exit 1 if the XML in res/ is not what this script emits
    python3 tools/generate_icons.py --xml-only # skip the PNG renders
Text in the PNGs uses Adwaita Sans when installed (set $WAKENUDGE_FONT to another .ttf otherwise).
"""
from __future__ import annotations

import argparse
import math
import os
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops, ImageDraw, ImageFont

# =====================================================================================
# Part 1. A small Android VectorDrawable renderer (path data -> polygons -> scanline fill)
# =====================================================================================

ANDROID = "{http://schemas.android.com/apk/res/android}"

_TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def _arc_to_cubics(x1, y1, rx, ry, phi_deg, large, sweep, x2, y2):
    """SVG/Android elliptical arc (endpoint form) -> list of cubic segments (c1, c2, p)."""
    if (x1, y1) == (x2, y2):
        return []
    rx, ry = abs(rx), abs(ry)
    if rx == 0 or ry == 0:
        return [((x1, y1), (x2, y2), (x2, y2))]
    phi = math.radians(phi_deg)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = cp * dx + sp * dy, -sp * dx + cp * dy
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx, ry = rx * s, ry * s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    coef = math.sqrt(max(0.0, num / den)) if den else 0.0
    if bool(large) == bool(sweep):
        coef = -coef
    cxp, cyp = coef * rx * y1p / ry, -coef * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x1 + x2) / 2
    cy = sp * cxp + cp * cyp + (y1 + y2) / 2

    def ang(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    ux, uy = (x1p - cxp) / rx, (y1p - cyp) / ry
    vx, vy = (-x1p - cxp) / rx, (-y1p - cyp) / ry
    t1 = ang(1, 0, ux, uy)
    dt = ang(ux, uy, vx, vy)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    n = max(1, math.ceil(abs(dt) / (math.pi / 2) - 1e-9))
    d = dt / n
    k = 4 / 3 * math.tan(d / 4)
    out = []

    def pt(t):
        x, y = rx * math.cos(t), ry * math.sin(t)
        return cp * x - sp * y + cx, sp * x + cp * y + cy

    def der(t):
        x, y = -rx * math.sin(t), ry * math.cos(t)
        return cp * x - sp * y, sp * x + cp * y

    t = t1
    for i in range(n):
        p0, p3 = pt(t), pt(t + d)
        d0, d3 = der(t), der(t + d)
        c1 = (p0[0] + k * d0[0], p0[1] + k * d0[1])
        c2 = (p3[0] - k * d3[0], p3[1] - k * d3[1])
        if i == n - 1:
            p3 = (x2, y2)
        out.append((c1, c2, p3))
        t += d
    return out


def parse_path(d: str):
    """Parse Android path data into subpaths: list of (start, [segments], closed).
    Each segment is ('L', p) or ('C', c1, c2, p) in absolute coordinates."""
    toks = _TOKEN.findall(d)
    i, cmd = 0, None
    cur = (0.0, 0.0)
    start = (0.0, 0.0)
    last_c2 = None  # for S
    last_q = None  # for T
    subs = []
    segs = None

    def num():
        nonlocal i
        v = float(toks[i])
        i += 1
        return v

    while i < len(toks):
        t = toks[i]
        if t.isalpha():
            cmd = t
            i += 1
            if cmd in "Zz":
                if segs is not None:
                    subs.append((start, segs, True))
                    segs = None
                cur = start
                last_c2 = last_q = None
                continue
        elif cmd is None:
            raise ValueError("path data must start with a command")
        up = cmd.upper()
        rel = cmd.islower()
        ox, oy = cur if rel else (0.0, 0.0)
        if up == "M":
            x, y = num() + ox, num() + oy
            if segs is not None:
                subs.append((start, segs, False))
            segs = []
            cur = start = (x, y)
            cmd = "l" if rel else "L"  # implicit lineto
            last_c2 = last_q = None
            continue
        if segs is None:  # drawing without a moveto, or after a close
            segs = []
            start = cur
        if up == "L":
            p = (num() + ox, num() + oy)
            segs.append(("L", p))
            cur = p
            last_c2 = last_q = None
        elif up == "H":
            p = (num() + ox, cur[1])
            segs.append(("L", p))
            cur = p
            last_c2 = last_q = None
        elif up == "V":
            p = (cur[0], num() + oy)
            segs.append(("L", p))
            cur = p
            last_c2 = last_q = None
        elif up == "C":
            c1 = (num() + ox, num() + oy)
            c2 = (num() + ox, num() + oy)
            p = (num() + ox, num() + oy)
            segs.append(("C", c1, c2, p))
            cur, last_c2, last_q = p, c2, None
        elif up == "S":
            c1 = (2 * cur[0] - last_c2[0], 2 * cur[1] - last_c2[1]) if last_c2 else cur
            c2 = (num() + ox, num() + oy)
            p = (num() + ox, num() + oy)
            segs.append(("C", c1, c2, p))
            cur, last_c2, last_q = p, c2, None
        elif up in "QT":
            if up == "Q":
                q = (num() + ox, num() + oy)
            else:
                q = (2 * cur[0] - last_q[0], 2 * cur[1] - last_q[1]) if last_q else cur
            p = (num() + ox, num() + oy)
            c1 = (cur[0] + 2 / 3 * (q[0] - cur[0]), cur[1] + 2 / 3 * (q[1] - cur[1]))
            c2 = (p[0] + 2 / 3 * (q[0] - p[0]), p[1] + 2 / 3 * (q[1] - p[1]))
            segs.append(("C", c1, c2, p))
            cur, last_q, last_c2 = p, q, None
        elif up == "A":
            rx, ry, rot, la, sw = num(), num(), num(), num(), num()
            p = (num() + ox, num() + oy)
            for c1, c2, q in _arc_to_cubics(cur[0], cur[1], rx, ry, rot, la, sw, p[0], p[1]):
                segs.append(("C", c1, c2, q))
            cur = p
            last_c2 = last_q = None
        else:
            raise ValueError("unsupported command " + cmd)
    if segs is not None:
        subs.append((start, segs, False))
    return subs


def _apply(m, p):
    a, b, c, d, e, f = m
    return (a * p[0] + c * p[1] + e, b * p[0] + d * p[1] + f)


def _mul(m1, m2):
    """m1 * m2 (apply m2 first, then m1)."""
    a1, b1, c1, d1, e1, f1 = m1
    a2, b2, c2, d2, e2, f2 = m2
    return (a1 * a2 + c1 * b2, b1 * a2 + d1 * b2, a1 * c2 + c1 * d2, b1 * c2 + d1 * d2,
            a1 * e2 + c1 * f2 + e1, b1 * e2 + d1 * f2 + f1)


IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def flatten(subs, m, tol=0.2):
    """Subpaths -> list of (points, closed) in device pixels under matrix m."""
    out = []
    for start, segs, closed in subs:
        p0 = _apply(m, start)
        pts = [p0]
        for s in segs:
            if s[0] == "L":
                p0 = _apply(m, s[1])
                pts.append(p0)
            else:
                c1, c2, p3 = (_apply(m, q) for q in s[1:])
                ddx = max(abs(p0[0] - 2 * c1[0] + c2[0]), abs(c1[0] - 2 * c2[0] + p3[0]))
                ddy = max(abs(p0[1] - 2 * c1[1] + c2[1]), abs(c1[1] - 2 * c2[1] + p3[1]))
                dd = math.hypot(ddx, ddy)
                n = max(1, min(512, math.ceil(math.sqrt(dd * 0.75 / tol))))
                for k in range(1, n + 1):
                    t = k / n
                    u = 1 - t
                    pts.append((u * u * u * p0[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t * t * t * p3[0],
                                u * u * u * p0[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t * t * t * p3[1]))
                p0 = p3
        out.append((pts, closed))
    return out


def fill_mask(polys, size, even_odd=False):
    """Scanline-fill polygons (list of point lists) into an 'L' mask; one sample per pixel centre."""
    W, H = size
    edges = []
    for pts in polys:
        n = len(pts)
        for k in range(n):
            x0, y0 = pts[k]
            x1, y1 = pts[(k + 1) % n]
            if y0 == y1:
                continue
            dirn = 1
            if y0 > y1:
                x0, y0, x1, y1 = x1, y1, x0, y0
                dirn = -1
            r0 = max(0, math.ceil(y0 - 0.5))
            r1 = min(H, math.ceil(y1 - 0.5))
            if r1 <= r0:
                continue
            edges.append((r0, r1, x0, y0, (x1 - x0) / (y1 - y0), dirn))
    mask = Image.new("L", (W, H), 0)
    if not edges:
        return mask
    edges.sort(key=lambda e: e[0])
    draw = ImageDraw.Draw(mask)
    active = []
    ei = 0
    row = edges[0][0]
    last = max(e[1] for e in edges)
    while row < last:
        while ei < len(edges) and edges[ei][0] <= row:
            active.append(edges[ei])
            ei += 1
        active = [e for e in active if e[1] > row]
        if not active:
            if ei >= len(edges):
                break
            row = edges[ei][0]
            continue
        yc = row + 0.5
        xs = sorted((e[2] + (yc - e[3]) * e[4], e[5]) for e in active)
        w = 0
        xstart = 0.0
        for x, dirn in xs:
            was = (w & 1) if even_odd else (w != 0)
            w += dirn
            now = (w & 1) if even_odd else (w != 0)
            if not was and now:
                xstart = x
            elif was and not now:
                a = max(0, math.ceil(xstart - 0.5))
                b = min(W, math.ceil(x - 0.5)) - 1
                if b >= a:
                    draw.line([(a, row), (b, row)], fill=255)
        row += 1
    return mask


def stroke_mask(lines, size, width, cap="butt", join="miter"):
    """Paint the union of stroke pieces (segment quads + round joins/caps) into an 'L' mask."""
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    hw = width / 2
    for pts, closed in lines:
        if len(pts) < 2:
            continue
        seq = pts + [pts[0]] if closed else pts
        for k in range(len(seq) - 1):
            (x0, y0), (x1, y1) = seq[k], seq[k + 1]
            L = math.hypot(x1 - x0, y1 - y0)
            if L == 0:
                continue
            nx, ny = -(y1 - y0) / L * hw, (x1 - x0) / L * hw
            ex, ey = 0.0, 0.0
            if cap == "square" and not closed:
                tx, ty = (x1 - x0) / L * hw, (y1 - y0) / L * hw
                if k == 0:
                    x0, y0 = x0 - tx, y0 - ty
                if k == len(seq) - 2:
                    x1, y1 = x1 + tx, y1 + ty
            draw.polygon([(x0 + nx, y0 + ny), (x1 + nx, y1 + ny), (x1 - nx, y1 - ny), (x0 - nx, y0 - ny)], fill=255)
        # joins (approximated as round for every join style) and round caps
        joins = seq[1:-1] if not closed else seq
        for (x, y) in joins:
            draw.ellipse([x - hw, y - hw, x + hw, y + hw], fill=255)
        if cap == "round" and not closed:
            for (x, y) in (seq[0], seq[-1]):
                draw.ellipse([x - hw, y - hw, x + hw, y + hw], fill=255)
    return mask


# ---------------------------------------------------------------------------- colours

def parse_color(s: str):
    s = s.strip()
    if not s.startswith("#"):
        raise ValueError("only literal colours are supported: " + s)
    h = s[1:]
    if len(h) == 3:
        h = "F" + h
    if len(h) == 4:
        h = "".join(c * 2 for c in h)
    if len(h) == 6:
        h = "FF" + h
    v = int(h, 16)
    return ((v >> 16) & 255, (v >> 8) & 255, v & 255, (v >> 24) & 255)


def _lut(stops):
    stops = sorted(stops, key=lambda s: s[0])
    out = []
    for i in range(256):
        t = i / 255
        if t <= stops[0][0]:
            c = stops[0][1]
        elif t >= stops[-1][0]:
            c = stops[-1][1]
        else:
            for (o0, c0), (o1, c1) in zip(stops, stops[1:]):
                if o0 <= t <= o1:
                    u = 0 if o1 == o0 else (t - o0) / (o1 - o0)
                    c = tuple(c0[j] + (c1[j] - c0[j]) * u for j in range(4))
                    break
        out.append(tuple(int(round(v)) for v in c))
    return out


_PAD = 256 * 16
_RAMP = None
_RADIAL = None


def _ramp():
    global _RAMP
    if _RAMP is None:
        col = [0] * _PAD + list(range(256)) + [255] * _PAD
        im = Image.new("L", (4, len(col)))
        im.putdata([v for v in col for _ in range(4)])
        _RAMP = im
    return _RAMP


def _radial():
    global _RADIAL
    if _RADIAL is None:
        n = 512
        data = []
        for y in range(n):
            dy = y + 0.5 - 256
            for x in range(n):
                dx = x + 0.5 - 256
                data.append(min(255, int(round(math.hypot(dx, dy) / 250 * 255))))
        im = Image.new("L", (n, n))
        im.putdata(data)
        _RADIAL = im
    return _RADIAL


def gradient_layer(g, m, box):
    """Render a gradient spec into an RGBA image covering `box` (x0, y0, x1, y1) in device pixels."""
    x0, y0, x1, y1 = box
    size = (x1 - x0, y1 - y0)
    if g["type"] == "linear":
        sx, sy = _apply(m, (g["startX"], g["startY"]))
        ex, ey = _apply(m, (g["endX"], g["endY"]))
        L2 = (ex - sx) ** 2 + (ey - sy) ** 2 or 1e-9
        ux, uy = (ex - sx) / L2 * 255, (ey - sy) / L2 * 255
        # input_y = PAD + 0.5 + 255 * t, with output pixel centre (x + 0.5 + x0, y + 0.5 + y0)
        c = _PAD + 0.5 + ux * (x0 + 0.5 - sx) + uy * (y0 + 0.5 - sy) - 0.5 * (ux + uy)
        t = _ramp().transform(size, Image.AFFINE, (0, 0, 2, ux, uy, c), resample=Image.BILINEAR)
    elif g["type"] == "radial":
        cx, cy = _apply(m, (g["centerX"], g["centerY"]))
        sc = math.sqrt(abs(m[0] * m[3] - m[1] * m[2]))
        r = g["gradientRadius"] * sc
        k = 250 / r
        t = _radial().transform(size, Image.AFFINE,
                                (k, 0, 256 + k * (x0 - cx), 0, k, 256 + k * (y0 - cy)),
                                resample=Image.BILINEAR, fillcolor=255)
    else:
        raise ValueError("unsupported gradient " + g["type"])
    lut = _lut(g["stops"])
    chans = [t.point([c[j] for c in lut]) for j in range(4)]
    return Image.merge("RGBA", chans)


def _composite(canvas, mask, paint, alpha, m, clip=None):
    if clip is not None:
        mask = ImageChops.multiply(mask, clip)
    bbox = mask.getbbox()
    if not bbox:
        return
    sub = mask.crop(bbox)
    if alpha < 1:
        sub = sub.point(lambda v: int(round(v * alpha)))
    if isinstance(paint, dict):
        layer = gradient_layer(paint, m, bbox)
        a = ImageChops.multiply(layer.getchannel("A"), sub)
        layer.putalpha(a)
    else:
        r, g, b, a = paint
        layer = Image.new("RGBA", sub.size, (r, g, b, 255))
        layer.putalpha(sub.point(lambda v: v * a // 255) if a < 255 else sub)
    canvas.alpha_composite(layer, dest=bbox[:2])


def _float(el, name, default=0.0):
    v = el.get(ANDROID + name)
    return float(v) if v is not None else default


def _gradient_from(el, attr):
    """Find <aapt:attr name="android:{attr}"><gradient/></aapt:attr> child of a path."""
    for child in el:
        if child.tag.endswith("}attr") and child.get("name") == "android:" + attr:
            g = child.find("gradient")
            spec = {"type": g.get(ANDROID + "type", "linear")}
            for k in ("startX", "startY", "endX", "endY", "centerX", "centerY", "gradientRadius"):
                spec[k] = _float(g, k)
            stops = [( _float(it, "offset"), parse_color(it.get(ANDROID + "color"))) for it in g.findall("item")]
            if not stops:
                cols = [g.get(ANDROID + k) for k in ("startColor", "centerColor", "endColor")]
                cols = [c for c in cols if c]
                stops = [(i / (len(cols) - 1), parse_color(c)) for i, c in enumerate(cols)]
            spec["stops"] = stops
            return spec
    return None


def render_vector(xml_text: str, size: int, view=None, ss: int = 4, tint=None):
    """Render VectorDrawable XML to an RGBA image of `size` px. `view` = (x, y, w, h) region of the
    viewport to show (default: whole viewport). `tint` = RGBA colour applied like android:tint (SRC_IN)."""
    root = ET.fromstring(xml_text)
    vw, vh = _float(root, "viewportWidth"), _float(root, "viewportHeight")
    if view is None:
        view = (0, 0, vw, vh)
    w, h = (size, size) if isinstance(size, int) else size
    sc = w * ss / view[2]
    base = (sc, 0.0, 0.0, sc, -view[0] * sc, -view[1] * sc)
    canvas = Image.new("RGBA", (w * ss, h * ss), (0, 0, 0, 0))
    _render_children(root, canvas, base, None)
    if tint is not None:
        r, g, b, a = tint
        al = canvas.getchannel("A")
        canvas = Image.new("RGBA", canvas.size, (r, g, b, 255))
        canvas.putalpha(al.point(lambda v: v * a // 255))
    return canvas.resize((w, h), Image.BOX) if ss > 1 else canvas


def _render_children(node, canvas, m, clip):
    for el in node:
        tag = el.tag
        if tag == "group":
            px, py = _float(el, "pivotX"), _float(el, "pivotY")
            sx, sy = _float(el, "scaleX", 1.0), _float(el, "scaleY", 1.0)
            rot = math.radians(_float(el, "rotation"))
            tx, ty = _float(el, "translateX"), _float(el, "translateY")
            cr, sr = math.cos(rot), math.sin(rot)
            local = (1, 0, 0, 1, -px, -py)
            local = _mul((sx, 0, 0, sy, 0, 0), local)
            local = _mul((cr, sr, -sr, cr, 0, 0), local)
            local = _mul((1, 0, 0, 1, tx + px, ty + py), local)
            _render_children(el, canvas, _mul(m, local), clip)
        elif tag == "clip-path":
            polys = [p for p, _ in flatten(parse_path(el.get(ANDROID + "pathData")), m)]
            cm = fill_mask(polys, canvas.size)
            clip = cm if clip is None else ImageChops.multiply(clip, cm)
        elif tag == "path":
            subs = parse_path(el.get(ANDROID + "pathData", ""))
            lines = flatten(subs, m)
            fill = el.get(ANDROID + "fillColor")
            fpaint = _gradient_from(el, "fillColor") or (parse_color(fill) if fill else None)
            if fpaint is not None:
                eo = el.get(ANDROID + "fillType", "nonZero") == "evenOdd"
                mask = fill_mask([p for p, _ in lines], canvas.size, eo)
                _composite(canvas, mask, fpaint, _float(el, "fillAlpha", 1.0), m, clip)
            stroke = el.get(ANDROID + "strokeColor")
            spaint = _gradient_from(el, "strokeColor") or (parse_color(stroke) if stroke else None)
            sw = _float(el, "strokeWidth", 0.0)
            if spaint is not None and sw > 0:
                scl = math.sqrt(abs(m[0] * m[3] - m[1] * m[2]))
                mask = stroke_mask(lines, canvas.size, sw * scl,
                                   el.get(ANDROID + "strokeLineCap", "butt"),
                                   el.get(ANDROID + "strokeLineJoin", "miter"))
                _composite(canvas, mask, spaint, _float(el, "strokeAlpha", 1.0), m, clip)


# =====================================================================================
# Part 2. Geometry helpers that emit compact Android path data
# =====================================================================================

def _f(v: float) -> str:
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def _p(x, y) -> str:
    return f"{_f(x)},{_f(y)}"


def circle(cx, cy, r) -> str:
    return f"M{_p(cx - r, cy)}A{_f(r)},{_f(r)} 0 1,1 {_p(cx + r, cy)}A{_f(r)},{_f(r)} 0 1,1 {_p(cx - r, cy)}Z"


def rounded_polygon(verts, radii) -> str:
    """Polygon with circular-arc corner rounding (the same construction as androidx RoundedPolygon with
    CornerRounding(radius, smoothing = 0)); cuts that do not fit on an edge are scaled down."""
    n = len(verts)
    info = []
    for i in range(n):
        px, py = verts[i]
        ax, ay = verts[i - 1]
        bx, by = verts[(i + 1) % n]
        ux, uy = ax - px, ay - py
        vx, vy = bx - px, by - py
        lu, lv = math.hypot(ux, uy), math.hypot(vx, vy)
        ux, uy, vx, vy = ux / lu, uy / lu, vx / lv, vy / lv
        theta = math.acos(max(-1.0, min(1.0, ux * vx + uy * vy)))
        tan_half = math.tan(theta / 2)
        cross = (px - ax) * (by - py) - (py - ay) * (bx - px)
        info.append([(ux, uy), (vx, vy), radii[i] / tan_half, tan_half, 1 if cross > 0 else 0])
    scale = [1.0] * n
    for i in range(n):
        j = (i + 1) % n
        L = math.hypot(verts[j][0] - verts[i][0], verts[j][1] - verts[i][1])
        need = info[i][2] + info[j][2]
        if need > L:
            s = L / need
            scale[i] = min(scale[i], s)
            scale[j] = min(scale[j], s)
    pts = []
    for i in range(n):
        (ux, uy), (vx, vy), d, tan_half, sweep = info[i]
        d *= scale[i]
        r = d * tan_half
        px, py = verts[i]
        pts.append(((px + ux * d, py + uy * d), (px + vx * d, py + vy * d), r, sweep))
    out = [f"M{_p(*pts[0][1])}"]
    for i in list(range(1, n)) + [0]:
        tin, tout, r, sweep = pts[i]
        prev = pts[i - 1][1]
        if math.hypot(tin[0] - prev[0], tin[1] - prev[1]) > 1e-3:
            out.append(f"L{_p(*tin)}")
        if r > 1e-6:
            out.append(f"A{_f(r)},{_f(r)} 0 0,{sweep} {_p(*tout)}")
    out.append("Z")
    return "".join(out)


def star(cx, cy, n, r_out, inner, rounding, inner_rounding, rot_deg=-90.0) -> str:
    """androidx RoundedPolygon.star(numVerticesPerRadius = n, innerRadius = inner, ...) scaled to r_out.
    Vertices go clockwise on screen starting at angle rot_deg (outer vertex)."""
    verts, radii = [], []
    for k in range(2 * n):
        a = math.radians(rot_deg + k * 180.0 / n)
        r = r_out if k % 2 == 0 else r_out * inner
        verts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
        radii.append((rounding if k % 2 == 0 else inner_rounding) * r_out)
    return rounded_polygon(verts, radii)


def hands_outline(cx, cy, hub_r, hands, fillet=0.0) -> str:
    """One clockwise contour = union of a hub disc and round-ended hands, with the concave corners where a
    hand leaves the hub softened by `fillet`-radius arcs.
    hands: list of (angle_deg (0 = 12 o'clock, clockwise), length from centre, width); width/2 < hub_r."""
    seq = []
    for ang, length, w in sorted(hands, key=lambda h: h[0] % 360):
        hw = w / 2
        t = math.radians(ang)
        u = (math.sin(t), -math.cos(t))
        n = (-u[1], u[0])  # clockwise-side normal
        tf = math.sqrt((hub_r + fillet) ** 2 - (hw + fillet) ** 2)
        sides = {}
        for sgn in (-1, 1):  # -1: counter-clockwise side, +1: clockwise side
            fc = (cx + u[0] * tf + n[0] * sgn * (hw + fillet), cy + u[1] * tf + n[1] * sgn * (hw + fillet))
            k = hub_r / (hub_r + fillet)
            on_hub = (cx + (fc[0] - cx) * k, cy + (fc[1] - cy) * k)
            on_side = (cx + u[0] * tf + n[0] * sgn * hw, cy + u[1] * tf + n[1] * sgn * hw)
            tip = (cx + u[0] * length + n[0] * sgn * hw, cy + u[1] * length + n[1] * sgn * hw)
            sides[sgn] = (on_hub, on_side, tip)
        seq.append((sides, hw))

    def ang_of(p):
        return math.atan2(p[1] - cy, p[0] - cx)

    out = []
    for i, (sides, hw) in enumerate(seq):
        hub_a, side_a, tip_a = sides[-1]
        hub_b, side_b, tip_b = sides[1]
        if i == 0:
            out.append("M" + _p(*hub_a))
        if fillet > 0:
            out.append(f"A{_f(fillet)},{_f(fillet)} 0 0,0 {_p(*side_a)}")
        out.append(f"L{_p(*tip_a)}A{_f(hw)},{_f(hw)} 0 0,1 {_p(*tip_b)}L{_p(*side_b)}")
        if fillet > 0:
            out.append(f"A{_f(fillet)},{_f(fillet)} 0 0,0 {_p(*hub_b)}")
        nxt = seq[(i + 1) % len(seq)][0][-1][0]
        span = (ang_of(nxt) - ang_of(hub_b)) % (2 * math.pi)
        out.append(f"A{_f(hub_r)},{_f(hub_r)} 0 {1 if span > math.pi else 0},1 {_p(*nxt)}")
    out.append("Z")
    return "".join(out)


def sector(cx, cy, r, a0, a1) -> str:
    """Pie slice from angle a0 to a1 (degrees, 0 = 12 o'clock, clockwise)."""
    def pt(a):
        t = math.radians(a - 90)
        return cx + r * math.cos(t), cy + r * math.sin(t)

    large = 1 if (a1 - a0) % 360 > 180 else 0
    return f"M{_p(cx, cy)}L{_p(*pt(a0))}A{_f(r)},{_f(r)} 0 {large},1 {_p(*pt(a1))}Z"


# =====================================================================================
# Part 3. VectorDrawable XML emitters
# =====================================================================================

def _grad_xml(g, indent="        ") -> str:
    attrs = [f'android:type="{g["type"]}"']
    keys = ("startX", "startY", "endX", "endY") if g["type"] == "linear" else ("centerX", "centerY", "gradientRadius")
    attrs += [f'android:{k}="{_f(g[k])}"' for k in keys]
    items = "".join(f'\n{indent}        <item android:offset="{_f(o)}" android:color="{c}" />' for o, c in g["stops"])
    return (f'\n{indent}<aapt:attr name="android:fillColor">\n{indent}    <gradient\n{indent}        '
            + f"\n{indent}        ".join(attrs) + f">{items}\n{indent}    </gradient>\n{indent}</aapt:attr>")


def vector_xml(paths, size=108, viewport=108, comment=None) -> str:
    """paths: list of dicts {d, fill (str '#AARRGGBB' or gradient dict), evenodd, alpha, name, comment}."""
    uses_aapt = any(isinstance(p.get("fill"), dict) for p in paths)
    head = ['<?xml version="1.0" encoding="utf-8"?>']
    if comment:
        head.append(f"<!-- {comment} -->")
    ns = 'xmlns:android="http://schemas.android.com/apk/res/android"'
    if uses_aapt:
        ns += '\n    xmlns:aapt="http://schemas.android.com/aapt"'
    (w, h), (vw, vh) = [(v, v) if isinstance(v, (int, float)) else v for v in (size, viewport)]
    head.append(f'<vector {ns}\n    android:width="{w}dp"\n    android:height="{h}dp"\n'
                f'    android:viewportWidth="{vw}"\n    android:viewportHeight="{vh}">')
    body = []
    for p in paths:
        if p.get("comment"):
            body.append(f"    <!-- {p['comment']} -->")
        attrs = []
        if p.get("name"):
            attrs.append(f'android:name="{p["name"]}"')
        fill = p.get("fill")
        if isinstance(fill, str):
            attrs.append(f'android:fillColor="{fill}"')
        if p.get("alpha", 1.0) != 1.0:
            attrs.append(f'android:fillAlpha="{_f(p["alpha"])}"')
        if p.get("evenodd"):
            attrs.append('android:fillType="evenOdd"')
        attrs.append(f'android:pathData="{p["d"]}"')
        a = "\n        ".join(attrs)
        if isinstance(fill, dict):
            body.append(f"    <path\n        {a}>{_grad_xml(fill)}\n    </path>")
        else:
            body.append(f"    <path\n        {a} />")
    return "\n".join(head + body + ["</vector>", ""])


def linear(x0, y0, x1, y1, *stops):
    return {"type": "linear", "startX": x0, "startY": y0, "endX": x1, "endY": y1, "stops": list(stops)}


def radial(cx, cy, r, *stops):
    return {"type": "radial", "centerX": cx, "centerY": cy, "gradientRadius": r, "stops": list(stops)}


# =====================================================================================
# Part 4. The WakeNudge mark (the only place where the design is defined)
# =====================================================================================

NIGHT_HI = "#FF3D2F9E"     # background, top-left
NIGHT_LO = "#FF1B1659"     # background, bottom-right
HALO = "8F6BFF"            # pre-dawn violet halo behind the sun (alpha added below); coral here turns muddy
SUN_HI = "#FFFFE070"       # sun, top
SUN_LO = "#FFFF7A55"       # sun, bottom (dawn coral)
HANDS = "#FF1F1A5C"        # night indigo
WHITE = "#FFFFFFFF"

LOBES, DEPTH, ROUNDING = 9, 0.84, 0.5   # cookie: star(9, innerRadius 0.84, rounding 0.5 of radius)
HOUR_DEG, MINUTE_DEG = 210, 0           # 7:00


def cookie(cx, cy, r, depth=DEPTH) -> str:
    return star(cx, cy, LOBES, r, depth, ROUNDING, ROUNDING)


def optical_cy(cy, r, depth=DEPTH) -> float:
    """With an odd lobe count the cookie has a lobe on top and a valley at the bottom; shift it so its
    bounding box (not its pivot) is centred."""
    ys = [y for pts, _ in flatten(parse_path(cookie(0, 0, r, depth)), IDENTITY, tol=0.001) for _, y in pts]
    return cy - (min(ys) + max(ys)) / 2


def clock_hands(cx, cy, r, hub=0.18, hour_len=0.43, hour_w=0.27, min_len=0.65, min_w=0.2, fillet=0.05) -> str:
    """Both hands + hub as one outline, proportional to the cookie radius r."""
    return hands_outline(cx, cy, hub * r, [(HOUR_DEG, hour_len * r, hour_w * r), (MINUTE_DEG, min_len * r, min_w * r)],
                         fillet * r)


MARK_R = 26.0      # foreground cookie radius on the 108 dp canvas: ~48 dp wide after rounding, inside the 66 dp safe zone
MONO_R = 21.0      # themed-icon glyph is drawn smaller: a solid silhouette is optically heavier


def background_xml() -> str:
    return vector_xml([
        {"comment": "Night sky, lit from the top-left.",
         "d": "M0,0H108V108H0Z", "fill": linear(18, 18, 90, 90, (0, NIGHT_HI), (1, NIGHT_LO))},
        {"comment": "Pre-dawn halo behind the sun.",
         "d": circle(54, 54, 42), "fill": radial(54, 54, 42, (0, "#78" + HALO), (1, "#00" + HALO))},
    ], comment="Generated by tools/generate_icons.py - edit the script, not this file.")


def foreground_xml() -> str:
    r = MARK_R
    cy = optical_cy(54, r)
    return vector_xml([
        {"comment": "The sun: a 9-lobed cookie, the shape the app's switches morph into.",
         "d": cookie(54, cy, r), "fill": linear(54, cy - r, 54, cy + r, (0, SUN_HI), (1, SUN_LO))},
        {"comment": "Clock hands at 7:00, hub included, as one shape.",
         "d": clock_hands(54, cy, r), "fill": HANDS},
    ], comment="Generated by tools/generate_icons.py - edit the script, not this file.")


def monochrome_xml() -> str:
    r = MONO_R
    cy = optical_cy(54, r)
    return vector_xml([
        {"comment": "Themed icon: the cookie with the hands cut out (alpha only).",
         "d": cookie(54, cy, r) + clock_hands(54, cy, r), "fill": WHITE, "evenodd": True},
    ], comment="Generated by tools/generate_icons.py - edit the script, not this file.")


# Status-bar glyphs: 24 x 24, white only, 20 dp live area. Slightly deeper lobes and heavier hands than the
# launcher mark so the wobble and the cut-outs survive at 16 px.
STAT_R = 10.0


def stat_alarm_xml() -> str:
    r = STAT_R
    cy = optical_cy(12, r, 0.82)
    return vector_xml([
        {"d": cookie(12, cy, r, depth=0.82) + clock_hands(12, cy, r, hub=0.26, hour_len=0.46, hour_w=0.33,
                                                           min_len=0.64, min_w=0.26),
         "fill": WHITE, "evenodd": True},
    ], size=24, viewport=24, comment="Generated by tools/generate_icons.py - edit the script, not this file.")


def stat_timer_xml() -> str:
    r = STAT_R
    cy = optical_cy(12, r, 0.82)
    return vector_xml([
        {"comment": "The cookie with the elapsed third cut out as a wedge.",
         "d": cookie(12, cy, r, depth=0.82) + sector(12, cy, 6.6, 0, 120), "fill": WHITE, "evenodd": True},
    ], size=24, viewport=24, comment="Generated by tools/generate_icons.py - edit the script, not this file.")


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""

RES = "app/src/main/res"
XML_FILES = {
    f"{RES}/drawable/ic_launcher_background.xml": background_xml,
    f"{RES}/drawable/ic_launcher_foreground.xml": foreground_xml,
    f"{RES}/drawable/ic_launcher_monochrome.xml": monochrome_xml,
    f"{RES}/drawable/ic_stat_alarm.xml": stat_alarm_xml,
    f"{RES}/drawable/ic_stat_timer.xml": stat_timer_xml,
    f"{RES}/mipmap-anydpi/ic_launcher.xml": lambda: ADAPTIVE_XML,
    f"{RES}/mipmap-anydpi/ic_launcher_round.xml": lambda: ADAPTIVE_XML,
}


# =====================================================================================
# Part 5. PNG outputs, all rendered from the XML above
# =====================================================================================

MASKS = {  # 100 x 100 launcher masks (AOSP-style shape overlays)
    "Circle": "M50,0A50,50 0 1,1 50,100A50,50 0 1,1 50,0Z",
    "Squircle": "M50,0C10,0 0,10 0,50S10,100 50,100S100,90 100,50S90,0 50,0Z",
    "Rounded square": "M16,0H84A16,16 0 0,1 100,16V84A16,16 0 0,1 84,100H16A16,16 0 0,1 0,84V16A16,16 0 0,1 16,0Z",
    "Teardrop": "M50,0A50,50 0 0,1 100,50V85A15,15 0 0,1 85,100H50A50,50 0 0,1 50,0Z",
    "Google Play": "M20,0H80A20,20 0 0,1 100,20V80A20,20 0 0,1 80,100H20A20,20 0 0,1 0,80V20A20,20 0 0,1 20,0Z",
}
VISIBLE = (18, 18, 72, 72)  # the part of the 108 dp layers a launcher shows inside its mask


def shape_mask(shape, n):
    s = n / 100
    return fill_mask([p for p, _ in flatten(parse_path(MASKS[shape]), (s, 0, 0, s, 0, 0))], (n, n))


def adaptive_icon(bg, fg, px, shape="Circle", ss=4, mono=None, theme=None):
    """Composite the adaptive icon like a launcher does: inner 72 dp of the layers, clipped to `shape`.
    With `mono` + `theme` = ((bg rgba), (glyph rgba)) it renders the Android 13 themed icon instead."""
    n = px * ss
    if mono is not None:
        img = Image.new("RGBA", (n, n), theme[0])
        img.alpha_composite(render_vector(mono, n, view=VISIBLE, ss=1, tint=theme[1]))
    else:
        img = render_vector(bg, n, view=VISIBLE, ss=1)
        img.alpha_composite(render_vector(fg, n, view=VISIBLE, ss=1))
    if shape:
        img.putalpha(ImageChops.multiply(img.getchannel("A"), shape_mask(shape, n)))
    return img.resize((px, px), Image.BOX)


# Adwaita Sans (an Inter derivative, OFL) for the wordmark and labels; override with $WAKENUDGE_FONT.
_FONT = os.environ.get("WAKENUDGE_FONT", "/usr/share/fonts/adwaita-sans-fonts/AdwaitaSans-Regular.ttf")
_FALLBACK = ["/usr/share/fonts/google-roboto/Roboto-Medium.ttf", "DejaVuSans.ttf"]


def font(size, weight=400):
    try:
        f = ImageFont.truetype(_FONT, size)
        f.set_variation_by_axes([max(14, min(32, size * 0.75)), weight])
        return f
    except Exception:
        for path in _FALLBACK:
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                pass
        return ImageFont.load_default()


def vgradient(size, top, bottom):
    w, h = size
    ramp = Image.linear_gradient("L").resize((w, h))
    a, b = Image.new("RGBA", size, top), Image.new("RGBA", size, bottom)
    return Image.composite(b, a, ramp)


def play_icon(bg, fg):
    """512 x 512 full-bleed square, 32-bit (opaque alpha); Google Play applies its own mask and shadow."""
    img = adaptive_icon(bg, fg, 512, shape=None, ss=4)
    img.putalpha(255)
    return img


MARK_VIEW = (24, 24, 60, 60)  # crop around the mark: the ~48 dp cookie fills ~80 % of the tile


def logo(fg, px=1024):
    return render_vector(fg, px, view=MARK_VIEW, ss=4)


def feature_graphic(fg):
    """1024 x 500: the mark beside the name, on the icon's night sky."""
    W, H = 1024, 500
    name_f, tag_f = font(104, 700), font(31, 450)
    name, tag = "WakeNudge", "The gentle nudge that gets you up."
    tmp = ImageDraw.Draw(Image.new("RGB", (8, 8)))
    text_w = max(tmp.textlength(name, font=name_f), tmp.textlength(tag, font=tag_f) + 3)
    mark = logo(fg, 330)
    mx0, my0, mx1, my1 = mark.getbbox()
    gap = 40
    left = (W - ((mx1 - mx0) + gap + text_w)) / 2            # centre mark + text as one group
    ox, oy = int(round(left - mx0)), int(round(H / 2 - (my0 + my1) / 2))
    cx_mark = ox + (mx0 + mx1) / 2
    bg = vector_xml([
        {"d": f"M0,0H{W}V{H}H0Z", "fill": linear(0, 0, W, H, (0, NIGHT_HI), (1, NIGHT_LO))},
        {"d": circle(cx_mark, H / 2, 300),
         "fill": radial(cx_mark, H / 2, 300, (0, "#78" + HALO), (1, "#00" + HALO))},
    ], size=(W, H), viewport=(W, H))
    img = render_vector(bg, (W, H), ss=2)
    img.alpha_composite(mark, (ox, oy))
    d = ImageDraw.Draw(img)
    tx = ox + mx1 + gap
    d.text((tx, H / 2 + 10), name, font=name_f, fill=(255, 246, 229, 255), anchor="ls")
    d.text((tx + 3, H / 2 + 64), tag, font=tag_f, fill=(207, 198, 255, 255), anchor="ls")
    return img.convert("RGB")


def _label(d, xy, text, size=17, fill=(110, 104, 122), weight=500, anchor="la"):
    d.text(xy, text, font=font(size, weight), fill=fill, anchor=anchor)


def contact_sheet(bg, fg, mono, alarm, timer):
    W, pad = 1600, 48
    H = 1196
    sheet = Image.new("RGBA", (W, H), (250, 248, 252, 255))
    d = ImageDraw.Draw(sheet)
    ink, grey = (28, 27, 32), (110, 104, 122)
    _label(d, (pad, 40), "WakeNudge launcher icon", 34, ink, 650)
    _label(d, (pad, 88), "Adaptive icon on common launcher masks, Android 13 themed icon and status-bar glyphs. "
                         "Rendered from the VectorDrawable XML by tools/generate_icons.py.", 18, grey, 450)
    panels = [  # title, wallpaper gradient, label colour, themed-icon (background, glyph)
        ("Light wallpaper", ((244, 238, 249, 255), (226, 216, 242, 255)), (96, 90, 108),
         ((229, 223, 255, 255), (46, 36, 112, 255))),
        ("Dark wallpaper", ((32, 29, 41, 255), (20, 18, 26, 255)), (178, 172, 190),
         ((45, 42, 58, 255), (208, 200, 255, 255))),
    ]
    y, ph = 130, 400
    for title, (top, bot), lab, theme in panels:
        panel = vgradient((W - 2 * pad, ph), top, bot)
        pm = Image.new("L", panel.size, 0)
        ImageDraw.Draw(pm).rounded_rectangle([0, 0, panel.size[0] - 1, ph - 1], 28, fill=255)
        sheet.paste(panel, (pad, y), pm)
        _label(d, (pad + 28, y + 22), title, 19, lab, 600)
        x = pad + 28
        for shape in ["Circle", "Squircle", "Rounded square", "Teardrop", None]:
            yy = y + 62
            kw = {"shape": shape} if shape else {"shape": "Circle", "mono": mono, "theme": theme}
            sheet.alpha_composite(adaptive_icon(bg, fg, 192, **kw), (x, yy))
            sheet.alpha_composite(adaptive_icon(bg, fg, 96, **kw), (x, yy + 208))
            sheet.alpha_composite(adaptive_icon(bg, fg, 48, **kw), (x + 112, yy + 208))
            sheet.alpha_composite(adaptive_icon(bg, fg, 24, ss=8, **kw), (x + 176, yy + 208))
            name = shape or "Themed icon"
            _label(d, (x, yy + 316), name, 16, lab, 650)
            nx = x + d.textlength(name, font=font(16, 650)) + 10
            _label(d, (nx, yy + 317), "192 · 96 · 48 · 24 px", 15, lab, 420)
            x += 292
        y += ph + 24
    # status bars
    by = y + 48
    _label(d, (pad, y + 8), "Notification glyphs", 19, (60, 56, 70), 600)
    bars = [((28, 27, 32, 255), (255, 255, 255, 255), (232, 228, 240)),
            ((236, 231, 243, 255), (28, 27, 32, 255), (28, 27, 32))]
    for i, (barc, glyph, txt) in enumerate(bars):
        bx = pad + i * 372
        bar = Image.new("RGBA", (352, 56), barc)
        bm = Image.new("L", bar.size, 0)
        ImageDraw.Draw(bm).rounded_rectangle([0, 0, 351, 55], 16, fill=255)
        sheet.paste(bar, (bx, by), bm)
        _label(d, (bx + 22, by + 28), "7:00", 22, txt, 550, anchor="lm")
        gx = bx + 104
        for g in (alarm, timer):
            sheet.alpha_composite(render_vector(g, 24, ss=8, tint=glyph), (gx, by + 16))
            gx += 34
        gx += 30
        for g in (alarm, timer):
            sheet.alpha_composite(render_vector(g, 16, ss=8, tint=glyph), (gx, by + 20))
            gx += 26
    _label(d, (pad, by + 76), "ic_stat_alarm and ic_stat_timer in a status bar: 24 px pair, then 16 px pair.",
           15, grey, 450)
    # magnified pixels
    mx = pad + 780
    _label(d, (mx, y + 8), "Actual pixels, enlarged", 19, (60, 56, 70), 600)
    for k, (g, px) in enumerate([(alarm, 24), (timer, 24), (alarm, 16), (timer, 16)]):
        im = render_vector(g, px, ss=8, tint=(255, 255, 255, 255))
        tile = Image.new("RGBA", im.size, (28, 27, 32, 255))
        tile.alpha_composite(im)
        tile = tile.resize((96, 96), Image.NEAREST)
        sheet.alpha_composite(tile, (mx + k * 112, by))
        _label(d, (mx + k * 112, by + 106), f"{px} px", 14, grey, 500)
    # Google Play
    gx = W - pad - 128
    _label(d, (gx, y + 8), "Google Play", 19, (60, 56, 70), 600)
    sheet.alpha_composite(adaptive_icon(bg, fg, 128, "Google Play"), (gx, by))
    return sheet.convert("RGB")


OUT_PNG = {
    "play": "fastlane/metadata/android/en-US/images/icon.png",
    "feature": "fastlane/metadata/android/en-US/images/featureGraphic.png",
    "logo": "docs/images/logo.png",
    "preview": "docs/images/icon-preview.png",
}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    ap.add_argument("--check", action="store_true", help="verify res/ XML is up to date, write nothing")
    ap.add_argument("--xml-only", action="store_true", help="write the XML, skip PNG rendering")
    args = ap.parse_args(argv)
    root = os.path.abspath(args.root)
    xml = {path: make() for path, make in XML_FILES.items()}
    if args.check:
        stale = [p for p, text in xml.items()
                 if not os.path.exists(os.path.join(root, p)) or open(os.path.join(root, p)).read() != text]
        for p in stale:
            print("stale:", p)
        return 1 if stale else 0
    for path, text in xml.items():
        full = os.path.join(root, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as fh:
            fh.write(text)
        print("wrote", path)
    if args.xml_only:
        return 0

    # Re-read the XML from disk so every PNG is a render of exactly what ships in the APK.
    def res(name):
        with open(os.path.join(root, RES, "drawable", name)) as fh:
            return fh.read()

    bg, fg, mono = res("ic_launcher_background.xml"), res("ic_launcher_foreground.xml"), res("ic_launcher_monochrome.xml")
    alarm, timer = res("ic_stat_alarm.xml"), res("ic_stat_timer.xml")
    outputs = {
        "play": lambda: play_icon(bg, fg),
        "feature": lambda: feature_graphic(fg),
        "logo": lambda: logo(fg),
        "preview": lambda: contact_sheet(bg, fg, mono, alarm, timer),
    }
    for key, make in outputs.items():
        full = os.path.join(root, OUT_PNG[key])
        os.makedirs(os.path.dirname(full), exist_ok=True)
        make().save(full, optimize=True)
        print("wrote", OUT_PNG[key])
    return 0


if __name__ == "__main__":
    sys.exit(main())
