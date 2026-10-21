# Testing

An alarm clock is judged on the one morning it fails, so testing is split by what can be proven where.

| Layer | Where it runs | What it proves |
|---|---|---|
| **JVM unit tests** (297) | Your computer | Scheduling maths (including DST), snooze/edit-lock/sleep-planner policies, volume and hearing-safety planning, stats, every mission's core logic, backup encode/decode/sanitise, health evaluation, widget/tile text |
| **Fuzz tests** (12, part of the 297) | Your computer | 20,000 seeded random alarm/clock/zone combinations never crash the scheduler or yield a time in the past; corrupted and hostile backup files are rejected or sanitised, never half-applied |
| **Instrumented engine tests** (55) | A real device | The real `AlarmManager`, Room, DataStore and audio stack: registration, firing, snooze, wake-check, timers, collisions, recovery, concurrency |
| **Instrumented UI tests** (17) | A real device | Ring screen, mission flow, slide-to-dismiss (incl. RTL), font scales 0.85–2.0, hostile labels, unusable missions |

Run the JVM tests with `./gradlew :app:testDebugUnitTest`. For the instrumented tests see the warning in the README:
**target exactly one device**.

## "How will real people break it?" suites

`AbuseFuzzTest`, `AbuseInstrumentedTest` and `RealWorldInstrumentedTest` encode the ways alarms actually fail. Each test
began life as a question; the ones that found real bugs are marked ✱ and were fixed.

- ✱ Deleting or disabling an alarm while it is snoozed or has backup rings pending left a **ghost snooze** that rang later.
- ✱ **One corrupt alarm** (hour 99 in a restored file) aborted scheduling of every alarm.
- ✱ **More than 500 alarms** crashed the app on the OS alarm limit.
- ✱ **Two alarms in the same minute**, or an alarm and a timer together, hijacked each other.
- ✱ A timer stuck in `RINGING` after the process died stayed stuck.
- ✱ An **expired vacation mode** flag persisted and silently kept alarms off.
- ✱ **Slide to dismiss was impossible in right-to-left languages.**
- ✱ The editor **re-enabled** an alarm the user had switched off; label truncation could **split an emoji**.
- ✱ After a reboot or app update, **snoozes and backup rings were lost** (found by reinstalling on the device mid-snooze).
- ✱ A replayed trigger for an occurrence that is already on screen or snoozed rang again.
- ✱ A fresh install had no safety-net watchdog until the first reboot.
- The app force-killed mid-ring (chaos test): the backup alarm still rings.
- Rapid snooze/dismiss mashing, double-delivered triggers, concurrent settings writes, missions that cannot be completed
  on this phone, permission revoked mid-flow, huge and hostile labels, 200 % font, RTL, time-zone jumps, DST gaps.

## Verified on a real device

Hardware: **Google Pixel 6 Pro, Android 17 (API 37)**. No other physical device was used.

- Alarm registered with `setAlarmClock`; fired 86 ms after its scheduled time with the screen off and the phone locked;
  full-screen ring UI shown over the lock screen; `USAGE_ALARM` audio playing with `mutedState:none`; vibration;
  40-second intensify; snooze; snooze surviving an app reinstall; dismiss releasing audio, service and notification;
  alarm-stream volume restored to its previous value.
- Test alarm from Alarm health; slide-to-dismiss (short drag does nothing, full drag dismisses).
- Chaos: `kill -9` of the app during a ring → the backup alarm fires with the "Backup alarm" UI and crash recovery is logged.
- Math mission logic (wrong answer rejected, right answer advances); previews of memory, typing, puzzle, shake and
  squat UIs; the photo mission correctly refuses a featureless reference picture.
- Onboarding, all tabs, settings, editor, Alarm health, backup export and restore (including the system file picker).
- **The minified release build** (R8, debug-signed, installed side by side): launch, onboarding, alarm creation with
  exact registration, test alarm ring over the lock screen with unmuted `USAGE_ALARM` audio, slide-to-dismiss, backup
  export (valid JSON with un-obfuscated field names) and restore (alarm returns and is re-armed).
- Widgets and the Quick Settings tile are **registered** with Android (three `AppWidgetProvider`s and the tile service
  resolve). Their layouts are covered by JVM tests of the data they show.

## Not verified. Please be sceptical here

These could not be done safely or at all in the development setup. They are the first things to test on your own phone.

| Scenario | Why it matters / status |
|---|---|
| Real **power-off and reboot**, including ringing **before first unlock** (Direct Boot) | Designed and unit/instrumented-tested via the recovery code path, but not demonstrated with a real power cycle |
| **Forced deep Doze** (`dumpsys deviceidle force-idle`) | Needs changing device power state; `setAlarmClock` is Doze-exempt by design. Not forced, only ordinary screen-off idle |
| **Time-zone change and DST transition** on a device | Maths covered by 20k fuzz cases and DST unit tests; the broadcast path was not triggered on the device |
| **Other manufacturers** (Samsung, Xiaomi, OnePlus, Huawei, …) | Their battery managers can still kill background work. Alarm health helps detect settings but cannot override a vendor |
| **Other Android versions** (8.0–16) | Only Android 17 on hardware. minSdk-26 code paths (e.g. no `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`) are branch-tested, not run |
| Human **shaking, squats and steps** | Counters are unit-tested with synthetic sensor traces; real motion was not performed |
| **NFC** tag read over the lock screen | The mission logic is tested; no tag was scanned |
| **QR/barcode** decode with a printed code; **photo match** against a detailed real scene | Decoder/matcher tested on generated images only |
| **TalkBack** end to end | Content descriptions and large-text/RTL are tested; a TalkBack walkthrough was not done |
| **Headphones / Bluetooth** routing | Policies tested on the JVM; no real headset was connected |
| **Tablets and foldables** | Layout adapts by window size; not seen on such hardware |
| Widgets and tile **placed and interacted with** | Registered, not added to a home screen or tile layout (that would change the owner's launcher) |
| Android 17 **background-audio hardening** | Currently warn-only for alarm apps; a later platform update could enforce more |
| Google Play review of exact-alarm and full-screen-intent declarations | Policy outcome cannot be tested locally. See [PLAY_STORE.md](PLAY_STORE.md) |

## Device safety rules used for this project

- Exactly one physical device was used, identified by model **and** serial before every command, with `adb -s <serial>`
  on each call. Another attached device was never touched.
- No factory reset, bootloader unlock or system-settings change. Test alarms and backups made during testing were deleted.
