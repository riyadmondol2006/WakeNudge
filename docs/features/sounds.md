# Sleep sounds and alarm music

All bundled audio remains original, synthesized by `tools/generate_tones.py` with no samples,
borrowed melodies, downloads, or third-party audio. NOTICE remains accurate. Eight 32-second
miniatures add piano-like keys, lo-fi percussion, marimba, synth, plucked strings, choir-like
additive pads, brass-like harmonics and music-box tones. New tracks appear under Music, retaining
gentle/standard/loud character labels and the existing integrated LUFS normalizer/limiter.
Circular note-tail overlap preserves the rhythmic loop. Vorbis quality 3 keeps assets small;
bitexact encoder flags and fixed seeds make regeneration reproducible with the same ffmpeg version.
Run `python3 tools/generate_tones.py app/src/main/res/raw --music-only` to rebuild only new music.

The Sleep sounds screen offers nine independent runtime DSP sources, up to three simultaneous
sources with individual levels, plus one optional user file and its own volume. It saves the last
mix and timer in its own Room database, sleep_sounds.db (schema 1). OpenDocument grants are persisted. File selection and
timer changes are available when stopped; source selection and levels can change while playing.
Original wave glyphs and Material 3 colors follow the app theme. The scrollable two-column grid
wraps labels and exposes selected state and sound names to TalkBack.

## DSP recipes

At 24 kHz, a seeded PRNG drives continuous filters. State persists across every buffer; playback
seeds are fresh. White uses broad noise with slew limiting; pink sums six octave one-poles;
brown uses a DC-leaking integrator. Rain subtracts a low-pass bed for brightness. Ocean uses
slow sinusoidal swells over low-pass noise; wind combines two slow modulation rates. Stream adds
two slowly wandering sine resonances to filtered noise. Fan mixes a 60 Hz oscillator and filtered
noise. Fire adds randomly excited exponential crackle decays over a dark bed. Every source has a
250 ms initial ramp and a bounded sample slew. Mixer headroom reserves one third per layer;
output slew limiting smooths changes to a running mix. These are synthetic soundscapes, not
recordings or claims about sleep quality.

## Playback and isolation

`SleepSoundsService` is an unexported mediaPlayback foreground service with USAGE_MEDIA,
separate from the alarm engine. Notification/channel: 730001 / sleep_sounds_v1; request codes
730001 (stop) and 730002 (open). These do not use engine trigger PendingIntents or notification ranges.
The service reads RingSessionStore only, checks it before PCM writes and custom file start,
and stops/relinquishes focus on any ring (including a test, sunrise or timer), permanent focus
loss, or AUDIO_BECOMING_NOISY. Transient loss ducks to 15%; gain restores levels. Disconnecting
headphones stops playback, preserving the mix for manual restart. No automatic resume after a ring.

Timer choices: 15/30/45/60/90 minutes, or the next enabled alarm returned by AlarmScheduler.
The next alarm instant is captured at playback start; later schedule edits do not extend the timer.
A monotonic deadline drives the final 30-second linear fade, including the optional file. No
upcoming alarm means playback refuses to start and explains the error. The service is not sticky:
process death, reboot or OS stopping the service ends playback. Custom audio loops its own file;
only synthesized noise is non-looping. Grant revocation or decode errors stop playback safely.

## Phone verification (integrator; no device was used here)

1. Install the release APK. Sleep → Sleep sounds. Select Rain, Wind and Fan. A fourth source
   is disabled. Adjust each slider; deselect one, choose Ocean. Play and navigate away/lock.
   Confirm continuous media audio, notification remaining time and Stop action.
2. Stop, add a local audio file through the system picker, set its level, play. Restart the app
   after stopping: mix, timer and file remain. Confirm revoked/deleted files report an error.
3. Select 15 minutes and play. Listen at 14:30: all sources and the file fade smoothly for
   30 seconds, then notification disappears. Also test each timer choice. No shortened debug
   timer is shipped; JVM tests cover intermediate fade gains.
4. Enable a real alarm a few minutes ahead; select Until my next alarm, then Play. Confirm
   fade during the last 30 seconds. Repeat with no enabled alarm: playback fails visibly.
5. Start a mix and use Alarm health → test alarm. Confirm the alarm rings normally, sleep
   sounds immediately cease, the sleep notification disappears, and no sleep audio returns
   after dismissing. Repeat with the custom file active and with a scheduled locked-screen ring.
6. Play through wired headphones, then unplug: playback stops without switching to speaker.
   Repeat with Bluetooth disconnect. Restart manually; confirm the last mix is restored.
7. Start another media app/call: permanent focus loss stops; transient loss ducks and recovers.
   Verify media volume keys affect sleep sounds and no alarm volume setting changes.
8. Inspect 360 dp width, font scale 2.0, RTL, light/dark/AMOLED and TalkBack. Tiles announce
   their names and in-mix state; sliders announce which sound they adjust. Check notification
   access denied and Android 8/14/17 background playback behavior.
9. In the alarm picker inspect Music, preview every new track for two loops (64 seconds),
   compare gentle/standard/loud levels, save each and ring it. Listen for seams and clipping.

## Proposed changelog

- Add nine generated sleep soundscapes, three-source mixes, personal audio and fading sleep timers.
- Add eight original looping music tracks to alarm sound choices.
- Stop sleep playback for alarms, permanent audio focus loss and headphone disconnection.

## Integration notes

The alarm database and its migrations remain unchanged. The new schema is exported under
`app/schemas/com.riyadm.wakenudge.engine.sounds.SleepSoundsDatabase/`. A dedicated Hilt module
provides it. `app/build.gradle.kts` adds only its constructor to the release reflection guard;
`app/proguard-rules.pro` keeps that constructor. The manifest adds only the service block.
The existing AudioPoliciesTest selectable-tone guard changes from 12 to 20; no guard is removed.
New JVM tests cover seed reproducibility, arbitrary buffer partition continuity, finite bounded
samples, maximum adjacent-sample jumps, calibrated RMS (0.06–0.22 over 11 seconds after warm-up)
and the 30-second fade envelope. A new instrumented UI test exercises the three-source cap with
large fonts and RTL; it is compiled, not executed.

A default-version unit run exposes an existing unrelated BackupManagerTest substring assertion:
versionName 1.0.90 includes the fixture's skipped version 1.0.9. Final JVM validation uses the
supported -Pwakenudge.versionName=1.0.0 override, with no change to that test or backup code.

Settings are local to this installation and are not included in the alarm backup format. Device
backup remains disabled by the app's existing policy. Acoustic quality, overnight battery use,
real audio focus, headset routing, notification presentation, TalkBack and theme/layout behavior
need the phone checks above; none has been claimed as tested on hardware.

## Validation results

- JVM XML: 97 suites, 949 tests, zero failures/errors/skips (includes two new DSP tests).
  First DSP run failed at brown-noise RMS 0.363; source gains were calibrated and the full suite passed.
- Passed: testDebugUnitTest, assembleDebug, assembleRelease, compileDebugAndroidTestKotlin,
  verifyOfflineManifestDebug and verifyReflectiveConstructorsRelease. This validation used
  `-Pwakenudge.versionName=1.0.0 -Pwakenudge.versionCode=90` to avoid the existing backup test's
  version substring issue. No production version file changed.
- All eight music files regenerated to byte-identical SHA-256 hashes with the same encoder.
  Each decodes to exactly 32 seconds; combined source asset size 834,117 bytes. Original 14
  OGG assets are untouched. Loudness measurements for all 22 assets are in tools/tones_report.json.
- No device, adb command, browser screenshot or hardware playback test was used.
- Comparable unsigned, minified release APKs with versionName 1.0.90 / versionCode 90:
  baseline d278f97: **5,354,383 bytes**; feature: **6,220,043 bytes**;
  exact increase **865,660 bytes** (under 2.5 MB). Both release builds passed R8's constructor guard.
- `:app:lintDebug` passed in its own Gradle call with the same fixed test version. Lint caught
  direct ViewModel construction inside the instrumented test's composable; moving test setup
  outside composition resolved it. The corrected instrumented test compiles successfully.
