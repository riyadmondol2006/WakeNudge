# Sleep tracking

The phone lies on the bed; WakeNudge feels how the sleeper moves (and, if asked, listens for snoring) and the next
morning shows an estimate of the night: time asleep, time to fall asleep, time awake after falling asleep, and stages.
Everything stays on the phone.

## What was built

| Part | Where |
|---|---|
| Stage estimate, epoch summaries, snore detector, clip choice, ring rules, week maths (pure Kotlin, JVM-tested) | `domain/sleep/` |
| `sleep.db` (sessions, epochs, clip metadata), its Hilt module, clip files, the one setting | `data/sleep/` |
| Foreground service, tracker, accelerometer, microphone, AAC encoder, clip player, clean-up alarm, share provider | `engine/sleeptrack/` |
| Track my sleep screen (with the calm screen while tracking), night detail, charts, view models | `ui/sleeptrack/` |
| Report tab, Sleep section | `ui/report/SleepReportSection.kt` |
| Strings | `res/values/strings_sleeptrack.xml` |

### How a night goes

1. **Start** on Track my sleep (Report → Sleep → Track my sleep, or Sleep → Track my sleep). The screen asks first, as
   needed, for physical activity (Android 14+ only, see Permissions), the microphone (only if Record snoring is on) and
   notifications (optional, so the tracking notification and its I'm up button show).
2. The service starts in the foreground from that tap. Only one session runs at a time; Start while one runs does nothing.
3. **While tracking** the screen is black with a large dimmed clock, the time tracked, whether the microphone is on, and
   I'm up. The window brightness is lowered (the system setting is never touched) and the screen may switch off as usual;
   the clock moves 8 dp each minute around a small square so nothing burns in. Leaving the screen does not stop tracking.
   The notification (channel `sleep_tracking_v1`, low importance, silent) shows a chronometer, says whether the
   microphone is on, and has I'm up.
4. **It ends** when the user taps I'm up (screen or notification), when an alarm that rang during the session is
   dismissed, after 16 hours, or when Android stops the service. A session left open by a dead process is closed after
   its last stored epoch the next time sleep tracking is opened or started.
5. Sessions under an hour are kept and marked "Short, under an hour"; they do not count in the week's averages and do
   not take a day's bar if a longer night ended that day.

### Alarms come first

The tracker only **reads** `RingSessionStore.session`. It never calls `RingController`, never takes its lock, never
touches `STREAM_ALARM`, the ring service or audio focus. Its collector runs on `Dispatchers.Main` (always dispatched, not
`Main.immediate`), so none of its code ever runs inside the call that changed the ring.

- **A ring starts** (any ring in the `RINGING` phase, alarm, test alarm or timer; the silent sunrise glow does not count):
  the microphone stops at once and stays off for the rest of the session. The service drops the `microphone` type and the
  notification says so. It cannot come back after a snooze anyway: Android only allows starting microphone use from
  the foreground.
- **The ring goes away**: 1.5 s later (the controller writes the outcome as the ring ends) the event is read from the
  history. `RingWatchPolicy.endsSession`: a test alarm ends the night; a dismissal or an Emergency stop ends it; a snooze
  (`RINGING` with `snoozeUntil`) or a ring that timed out unanswered (`MISSED`) keeps tracking; a timer never ends it;
  history that cannot be read counts as dismissed. If another ring is on by then, nothing happens until that one goes.
- Movement keeps being tracked while the alarm rings.
- "Listen" plays on the media stream with transient focus, stops on any focus loss (the alarm takes exclusive focus), stops
  the moment a ring starts, and refuses to start during one.

### Movement

The accelerometer at 10 Hz (`SENSOR_PERIOD_US = 100 000`) with a 20 s batch (`maxReportLatencyUs`), so the sensor hub
holds readings while the CPU sleeps. A wake-up accelerometer is used when the phone has one; otherwise the tracker holds
one partial wake lock (`WakeNudge:sleep-track`, timeout 16 h 10 min, released when the session ends), because a sleeping
CPU lets a non-wake-up batch overflow and lose movement. `EpochAccumulator` folds readings into 30-second epochs as they
arrive: number of readings, mean and largest change of acceleration between consecutive readings, and the number of
changes of at least 0.1 m/s². Readings more than 1.5 s apart are not compared. Epochs are written 30 s after they end,
so batched readings land in the right one. **Raw samples are never stored.**

### Stage estimate (`SleepStageEstimator`)

A transparent rule of thumb, documented in the class and here. The phone cannot see brain waves; the UI says "Estimated
from movement and sound, not measured like a sleep lab. Best with the phone on the mattress." in the report, the night
detail and (longer, with "not medical advice") on Track my sleep.

1. **Enough data**: fewer than 20 epochs (10 min) with readings, or readings for less than half the time in bed →
   "Too little movement data to estimate this night" and no numbers but the time tracked. No reading ≥ 0.1 m/s² in 40 or
   more epochs → "the phone probably wasn't on the bed", nothing estimated.
2. **Movement class per epoch**, relative to this phone tonight (noise floor = 10th percentile of the epochs' mean
   change): *restless* with ≥ 2 clear movements or ≥ 3 × the floor; *twitch* with 1 movement or ≥ 1.6 × the floor;
   otherwise *still*. With the microphone on, a twitch during a sound ≥ 20 dB over the night's quiet level that is not a
   snore counts as restless.
3. **Awake** when ≥ 3 of the 5 epochs around it are restless.
4. **Falling asleep** at the start of the first 20 epochs (10 min) in a row with readings, none restless or awake.
   Before it, and after the last sleeping epoch, everything is awake. **Latency** = session start → that point.
5. **Deep** inside runs of still epochs ≥ 20 min (≥ 30 min in the last third of the sleep), leaving the first 5 min and
   the last 2 min of the run light.
6. **REM** from an hour after falling asleep where, within ±5 epochs, there are ≥ 2 twitches, nothing restless and (with
   the microphone) no snoring.
7. Everything else asleep is **light**. Epochs without readings get no stage and count towards nothing.

**Time asleep** = deep + light + REM. **Awake after falling asleep** = awake epochs between falling asleep and the last
sleeping epoch. A night with no sustained stillness shows "No sustained stillness found" and no latency.

### Snoring (optional, off by default)

The switch "Record snoring" asks for `RECORD_AUDIO` only when switched on (denied → stays off with a message). Its
one-line note: "Listens on this phone only, keeps at most 3 short clips a night for 7 days, and stops when an alarm rings."

- `AudioRecord`, mono 16-bit at 16 kHz (44.1 kHz if refused), from the `UNPROCESSED` source where the phone supports it,
  else `VOICE_RECOGNITION` (no automatic gain, which would make a quiet room look loud). 50 ms frames.
- Per frame (`FrameAnalyzer`): level in dBFS, zero crossings as a frequency, share of energy below 800 Hz (one-pole
  low-pass). The level goes into the epoch (energy-averaged, plus the peak).
- `SnoreDetector`: a frame is snore-like when ≥ 8 dB above the room's quiet level, above −65 dBFS, zero crossings ≤ 900 Hz
  and ≥ 50 % of the energy below 800 Hz. An episode is a run of such frames (gaps ≤ 2 frames bridged) lasting 0.3–3.5 s.
  An episode counts only as the third of a row whose starts are 2–12 s apart with the two intervals within 40 % of each
  other (snoring follows breathing). The quiet level falls quickly and rises 0.01 dB per frame. No model, no download.
- **Clips**: once snoring is confirmed (at most once a minute), the last 10 s are cut 1.5 s after the snore and offered to
  `ClipPolicy`: at most 3 per night, the loudest, at least 5 minutes apart (a louder one within 5 minutes replaces the
  kept one). Compressed on the phone to AAC-LC 32 kbit/s in `.m4a` (≈ 40 kB) with `MediaCodec` + `MediaMuxer`, stored in
  `noBackupFilesDir/sleep_clips/`. Loudness is shown as Soft / Loud / Very loud by how far the peak stood above the room
  (< 15, 15–25, ≥ 25 dB).
- **Deletion**: each clip has Delete; Track my sleep has "Delete all snore clips"; deleting a night deletes its clips.
  Clips older than 7 days are deleted automatically (see Storage).
- **Share** only from a clip's Share button: the clip is copied to `cache/sleep_share/` and offered through
  `SleepClipProvider` (a `FileProvider` subclass, not exported, read permission granted to the chosen app only). The
  copy is deleted at the next clean-up or share.

### Foreground service

`SleepTrackService`, its own class, channel `sleep_tracking_v1`, notification id 7001, request codes 7101 (I'm up), 7102
(open), 7103 (clean-up alarm). The engine uses notification ids 1001–1005 and 20000 + alarm id and up, and request codes
0–5, so nothing collides (checked by `SleepServiceTypesTest`).

| Android | Types passed to `startForeground` |
|---|---|
| 14 and later (incl. 17) | `health`, plus `microphone` while recording |
| 11–13 | `microphone` while recording, else none |
| 8–10 | none |

Started only from the user's tap while the app is in front (`ContextCompat.startForegroundService`). If the start or the
promotion is refused (`SecurityException`, `ForegroundServiceStartNotAllowedException`, a missing prerequisite), it tries
again without the microphone, and if that fails too the screen says "Android didn't let sleep tracking start…" and
nothing crashes. `onTimeout` (Android 15+; these types have no time limit today) ends the night cleanly. `onDestroy` from
outside ends it with what was recorded. `START_NOT_STICKY`: a restart by the system without a request stops at once.

### Storage

- `sleep.db`, `SleepDatabase` version 1, schema exported to `app/schemas/com.riyadm.wakenudge.data.sleep.SleepDatabase/`,
  provided by `SleepModule`. Tables `sleep_session`, `sleep_epoch` (one row per 30 s: 9 numbers), `snore_clip`, with
  cascading deletes. `WakeNudgeDatabase`, its migrations and `DataModule` are untouched.
- **Credential-encrypted storage**, unlike the alarms: nights and recordings stay encrypted until the phone is unlocked.
  Nothing opens it at app start, so a process woken by an alarm before the first unlock never touches it; every sleep
  component is `directBootAware="false"` and the clean-up receiver does nothing while the user is locked.
- **Retention** (`SleepRepository.cleanUp`, run when Track my sleep opens, when a night ends, and from its own alarm):
  nights older than a year, clips older than 7 days, clip files that no row names, and the shared copy. The next run is
  set with an inexact, non-waking `AlarmManager.set(RTC, …)` for one minute after the next clip expires. That alarm does
  not survive a restart; expired clips are never listed or played meanwhile (they are filtered by age) and are deleted
  the next time sleep tracking opens or a night ends.
- **Not in any backup**: the app has `allowBackup="false"` and excludes every domain; the in-app backup file does not
  include sleep data (the backup code is untouched).
- `verifyReflectiveConstructorsRelease` now also checks `SleepDatabase_Impl`.

### Report → Sleep

Week picker like the wake-up report (This week / Last week / the dates, arrows back, forward stops at this week; weeks
start on the app's "week starts on" setting). A night belongs to the day it ended. Average time asleep and time to fall
asleep over the week's nights that have an estimate and are not short. A stacked bar per night (Deep, Light, REM, Awake
from the bottom) on an hours axis of at least 0–8 h; a tracked night without an estimate is an empty outline, never a
guess. Legend, and a full spoken description per day for TalkBack. The week's loudest clip with Listen. Track my sleep
and View night (the latest night of the week), and every night of the week as a row. Empty state with Track my sleep
when nothing was ever tracked.

**Night detail** (a full-screen dialog, so no shared route had to change): the four numbers, the short flag or the
reason there is no estimate, the stage timeline (Awake on top to Deep at the bottom, colours plus lanes), the sound-level
graph with snore marks and "The microphone stopped at 07:00, when an alarm rang", the clips with Listen / Share / Delete,
and Delete night.

## Decisions and deviations

- **Health-type prerequisite: `ACTIVITY_RECOGNITION`**, already declared for the steps mission. The alternative,
  declaring `HIGH_SAMPLING_RATE_SENSORS` (install-time), would avoid a prompt but claims a sampling rate the app does not
  use. If the user denies it on Android 14+, a dialog explains why and opens App info; tracking does not start.
- **The microphone stays off after a ring for the rest of the session**, also after a snooze: Android does not allow
  starting microphone use again from the background, and it is the more private choice.
- **Any ring stops the microphone**, timers included; only alarm rings (and the test alarm) can end the session.
- **A ring that times out unanswered keeps tracking** (the sleeper may still be in bed); the 16-hour limit still applies.
- **The notification opens the Sleep screen** (`MainActivity.ACTION_SHOW_SLEEP`, which already existed), one tap away from
  Track my sleep. A direct link would need a new action in `MainActivity`/`MainViewModel`, which I did not touch.
- **Night detail is a full-screen dialog** inside my own files rather than a new route in `Routes.kt`/`AppRoot.kt`.
- **No diagnostics rows**: logging uses `android.util.Log` (tag `SleepTracker`), so nothing is written to the alarm
  database's diagnostics table.
- The shared string `report_sleep_placeholder` in `strings_report.xml` is now unused (lint says so); I left it for the
  integrator to remove with the shell.

## Permissions (paragraph for docs/PERMISSIONS.md)

| Permission | Why | When |
|---|---|---|
| `FOREGROUND_SERVICE_HEALTH` (normal) | Sleep tracking keeps reading the accelerometer with the screen off, as a `health` foreground service (Android 14+) | Install |
| `ACTIVITY_RECOGNITION` (runtime, already used by the steps mission) | Android 14+ requires it before a `health` foreground service may start. Only used to feel movement in bed, on the phone | Asked when Start is tapped on Android 14+ |
| `RECORD_AUDIO` (runtime) | Sleep tracking's optional "Record snoring": sound level and snore detection on the phone, and at most 3 clips a night | Asked only when Record snoring is switched on; never otherwise |
| `FOREGROUND_SERVICE_MICROPHONE` (normal) | The `microphone` type of the sleep-tracking service, only while Record snoring is on and only until an alarm rings | Install |

`android.hardware.microphone` is declared `required="false"`, so the app still installs on phones without one.

## Privacy (paragraph for PRIVACY.md)

**Sleep tracking.** Only when you tap Start, WakeNudge reads the phone's motion sensor and keeps, for every 30 seconds,
a few numbers saying how much the phone moved; the readings themselves are never stored. If you switch on "Record
snoring" (off by default), it also uses the microphone while tracking runs: it measures how loud the room is every 30
seconds and listens for snoring on the phone, with no model download. It keeps at most three short clips (10 seconds or
less) a night, compressed, in the app's private storage. The microphone stops the moment an alarm starts ringing, and the
tracking notification says while it is on. Everything stays on your phone, encrypted until you unlock it, and is never
part of a backup or sent anywhere; a clip leaves the phone only if you tap its Share button. Clips are deleted
automatically after 7 days and nights after a year. You can delete any clip, all clips (Track my sleep, Delete all snore
clips) or a whole night (its detail, Delete night) at any time; uninstalling the app deletes everything.

## Tests

- **JVM (54 new, all passing)**: `SleepStageEstimatorTest` (13: a typical night's latency, deep only inside the long
  still run, REM only an hour after falling asleep, the exact 3-minute awake bout, scale invariance for noisier sensors,
  the 10-minute and half-coverage thresholds, gaps, no movement, never settling, a single turn staying sleep, short
  sessions, snoring ruling out REM, loud non-snore sound with a twitch counting as awake), `EpochAccumulatorTest` (8:
  batching, late and early readings, gaps, energy averaging), `SnoreDetectorTest` (10: synthetic sines through the frame
  analyser; regular, varying, too long, too short, high-pitched and irregular sound), `SleepPoliciesTest` (16: clip
  choice and expiry, loudness words, ring rules, 16-hour limit, closing stale sessions, weeks, main night per day,
  averages), `ClipEnvelopeTest` (3), `SleepServiceTypesTest` (4: types per Android version, ids clear of the engine's).
- **`ReleaseReadinessTest`** (changed, see below) passes.
- **Instrumented, compiled, not run** (no device used, as the brief asks): `SleepStorageInstrumentedTest` — a night
  stored, estimated and deleted with its clips; 7-day / 1-year retention and stray files with real files; a session left
  open closed after its last epoch; delete all clips keeps the nights; the real AAC encoder writes a ~2 s `audio/mp4a-latm`
  track. It uses an in-memory database and scratch folders, so the app's own nights on the phone are not touched.

### Guard-test changes (`ReleaseReadinessTest`)

1. `RECORD_AUDIO` removed from the banned-permission list (nothing else in that list changed).
2. New `the microphone is used by the sleep tracking service alone, and only as an opt-in`: requires `RECORD_AUDIO`,
   `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_HEALTH` and `ACTIVITY_RECOGNITION`; exactly one service with the
   `microphone` type and exactly one with `health`, both `.engine.sleeptrack.SleepTrackService`, typed
   `health|microphone`, not exported, not Direct Boot aware. So a second microphone user, or the ring service gaining it,
   fails the build.
3. `android.hardware.microphone` added to the optional-hardware list (RECORD_AUDIO implies it as required otherwise).

The exported-components list is unchanged: the service, receiver and provider are all `exported="false"`.

## Not verified

- Nothing here has run on a phone. Thresholds (0.1 m/s², the 1.6× / 3× factors, the snore frame rules) are reasoned from
  typical accelerometer and microphone behaviour, not tuned on real nights.
- Whether Android 17 (API 37) changes anything about `health` / `microphone` foreground services or background sensor
  batching.
- Battery use over a full night with and without the microphone, with and without a wake-up accelerometer.
- How the Pixel 6's accelerometer reports (wake-up or not) and whether batching keeps every epoch with the screen off.
- The AAC encoder on a real device (covered by the instrumented test, not yet run).
- TalkBack, font scale 2.0, 360 dp, RTL, AMOLED were designed for but not looked at on a device.

## Known limits

- Stages are an estimate from movement (and sound): no medical or sleep-lab claim is made anywhere.
- Two people in one bed, a pet, or a very soft mattress blur movement. The phone must be on the mattress.
- The snore detector can take a cough, a partner or a pet for snoring; that is what Listen is for.
- The clean-up alarm does not survive a restart (see Storage).
- After a process death mid-night, the night ends at the last stored epoch, and tracking does not resume by itself.
- A ring that was already on when Start was tapped is not treated as "during the session".

## Verify on a Pixel 6 (Android 17)

Install the debug build (`com.riyadm.wakenudge.debug`) and keep a terminal on `adb logcat -s SleepTracker SleepTrackService`.

1. **Start**: Report → Sleep → Track my sleep. Switch on Record snoring → the microphone prompt appears; allow. Tap
   Start → the physical-activity prompt (first time); allow. Expect the dark screen with the clock, "Microphone on,
   listening for snoring", the green privacy dot in the status bar, the notification "Tracking your sleep" with a
   chronometer and I'm up, and in logcat `session started: microphone on`.
   Check the types: `adb shell dumpsys activity services com.riyadm.wakenudge.debug/com.riyadm.wakenudge.engine.sleeptrack.SleepTrackService`
   shows the service in the foreground with both types: health is 0x100 and microphone 0x80, so the type reads `0x180`
   (`0x00000180`).
   Sensor: `adb shell dumpsys sensorservice | grep -i wakenudge` lists the accelerometer.
2. **Leave the phone** on the mattress, screen off, for at least 15 minutes (play a snoring recording from another device
   nearby for a few minutes if you want clips: rhythmic, one snore every ~4 s). Check epochs arrive: logcat stays quiet
   on errors; after the night the detail shows a timeline.
3. **Ring a test alarm**: wake the phone, open Alarm health → Test alarm → Ring in 1 minute, lock the phone. When it
   rings, expect at once: logcat `microphone stopped: a ring started`, the privacy dot gone,
   `adb shell cmd appops get com.riyadm.wakenudge.debug RECORD_AUDIO` no longer `running`, the notification "An alarm
   rang, so the microphone is off…", and dumpsys showing `foregroundServiceType=0x100` (health only). The alarm sound must
   be unchanged (same volume, ramp, no stutter).
4. **Dismiss it**. About 1.5 s later: logcat `ring -1 gone (test=true …): ending` then `session ending: ALARM_DISMISSED`;
   the notification disappears, the service is gone from dumpsys, and Track my sleep shows Last night with
   "Short, under an hour" (if under an hour). Open View night: stage timeline (or "Too little movement data" for a very
   short test), the sound-level graph with "The microphone stopped at …, when an alarm rang", and the clips.
5. **Snooze path** (optional): start again, set a real alarm 2 minutes ahead, snooze it → logcat `… still tracking`,
   tracking continues (movement only); dismiss the snooze ring → the session ends.
6. **Clips**: in the night detail, Listen plays on the media volume and stops when tapped; Share opens the share sheet
   with an `.m4a`; Delete removes it. `adb shell run-as com.riyadm.wakenudge.debug ls -l no_backup/sleep_clips` lists the
   files (≤ 3 per night, each ≈ 40 kB) and the list shrinks after Delete. Track my sleep → Delete all snore clips empties
   the folder.
7. **Auto-deletion**: `adb shell dumpsys alarm | grep -A4 sleeptrack` (or the package name) shows the clean-up alarm
   for 7 days + 1 minute after the oldest clip. To see it work without waiting, on a test phone only: switch off automatic
   time, set the date 8 days ahead, open Track my sleep → the clips are gone from the screen and from
   `no_backup/sleep_clips`; set automatic time back on.
8. **Refusal**: deny physical activity (App info → Permissions) and tap Start → the explanation dialog with Open
   settings, no crash and no notification.
9. **Process death** (optional, test phone only, since Force stop also clears the app's alarms until it is opened again):
   Force stop in App info mid-session, reopen Track my sleep → the night is closed as stopped, ending at its last stored
   epoch.

## Proposed changelog lines

- Sleep tracking: put your phone on the bed and tap Start on Track my sleep. It estimates time asleep, time to fall
  asleep, time awake and stages from how you move, and stops when you tap I'm up or dismiss your alarm. Stages are
  estimates, not a sleep-lab measurement.
- Record snoring (optional, off by default): listens on the phone while tracking, keeps at most 3 short clips a night for
  7 days, and stops the moment an alarm rings. Nothing leaves your phone unless you tap Share.
- Report → Sleep: the week's nights as stage bars, average sleep time and time to fall asleep, the week's loudest snore,
  and each night in detail.
