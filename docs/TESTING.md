# Testing

An alarm clock is judged on the one morning it fails, so testing is split by what can be proven where.

| Layer | Where it runs | What it proves |
|---|---|---|
| **JVM unit tests** (841) | Your computer | Scheduling maths (including DST), snooze/edit-lock/sleep-planner policies, volume and hearing-safety planning, stats, every mission's core logic, backup encode/decode/sanitise, health evaluation, widget/tile text, and the ring state machine, trigger handling, scheduler, system-event handling and crash rescue against storage that fails (full disk, unreadable database, OS that refuses) |
| **Fuzz tests** (14, part of the JVM tests) | Your computer | 20,000 seeded random alarm/clock/zone combinations never crash the scheduler or yield a time in the past; corrupted and hostile backup files are rejected or sanitised, never half-applied; random commands from stale buttons, keys and forged claims never end a ring whose missions are unfinished |
| **Instrumented engine tests** (about 130, with the migration tests) | A real device | The real `AlarmManager`, Room, DataStore and audio stack: registration, firing, snooze, wake-check, timers, collisions, recovery, concurrency; plus the reliability audit: real playback attacked (focus stolen, player paused/killed/erroring, slow or missing sound, playlists, headphones), the real `RingService` without any ring screen, and failing storage, a refusing OS, clock changes and crashes mid-ring |
| **Instrumented UI tests** (about 70, 25 of them for the update popup and the About screen) | A real device | Ring screen, mission flow, slide-to-dismiss (incl. RTL), font scales 0.85–2.0, hostile labels, unusable missions getting a backup mission (never Continue), Emergency and the way out by click action at font scale 2.0 |

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
- ✱ Reliability audit (found by reading the engine, then pinned by tests; the JVM ones were checked by breaking the fix and watching the test fail):
  - A **ring that ended while its sound was still loading** started playing afterwards for an alarm that was already over.
  - A **MediaPlayer error on the default tone** left the alarm silent; a **player paused by another app, or dead**, stayed that way.
  - A **playlist track that had been deleted** ended the sound when the previous one finished.
  - A **song that never finishes opening** (cloud drive, stalled card) delayed the alarm for as long as it blocked.
  - **Headphones plugged or pulled mid-ring** left the volume (and the hearing cap) as it was for the old output.
  - A **full disk or unreadable database** at the moment an alarm fired, snoozed or was dismissed could stop it from ringing
    (or from stopping); an alarm the OS held but the database had lost was ignored.
  - A **corrupt settings file** failed every read, and with it the ring.
  - A **snooze the OS refused** silenced the alarm and never brought it back.
  - A **crash in the ring screen** took the sound down with it, and a ring service destroyed mid-ring left an orphaned session
    that made every later alarm wait behind it.
  - A **refused foreground-service start** left only a silent notification.
  - An alarm **made overdue by the clock being set forward** was moved to tomorrow by the reschedule instead of ringing.
  - A **refused `setAlarmClock`** left the alarm unarmed with nobody told.
- ✱ Second audit: every engine path and screen read again as a half-awake user would break it (each fix has a test):
  - **A force-stop** (or a phone maker's "clean up") made Android forget every alarm, and nothing re-armed them until a
    reboot. The next app start now notices and re-registers everything.
  - Switching on one alarm of a **switched-off group** armed only that alarm, although the whole group came back on;
    **deleting a switched-off group** made its alarms ring.
  - A **typed time** was saved as another one: the wheels reported every row they glided past (06:45 typed over 07:00
    was saved as 06:55, reproduced on the device before the fix). A locked editor's wheels still turned.
  - An editor left open while its alarm rang **re-enabled** the finished one-time alarm on save, undid a skip, or
    re-created an alarm that had deleted itself; a double-tapped Save created **two alarms**.
  - **"Skip next" tapped twice** skipped the following day too; moving an alarm later kept a skip meant for the old time.
  - A **Stop or Dismiss that arrived just after another ring took over** ended that ring (missions skipped); volume keys
    during the **silent sunrise glow** armed a snooze, or deleted a delete-after-use alarm before it rang.
  - The **ring timeout and the backup** fall due in the same second by default: the timeout could end the ring the
    backup had just taken over, and stopping the service could drop the backup's start.
  - A **queued second alarm** kept the first alarm's sound, vibration and voice after the first was dismissed.
  - After a crash, Android **redelivered rings the user had already handled**, and they rang again.
  - A timer **started again while it rang** was reset to idle when the ring ended, so its countdown never rang.
  - **Delete-after-use** alarms never got their still-awake check.
  - **Rotating, or tapping Back,** mid-way through a chain of missions started it over; in landscape on a large screen
    the mission area had no height.
  - A QR or photo mission whose **camera was refused, broken or busy** had no Continue button.
  - A **restore** could bring back an old alarm's snooze and ring a restored alarm with the same id.
  - After a **reboot**, an alarm due in the second before the reschedule was lost; after an **app update**, one due
    during the install was never rung or reported.
  - On **Android 12** with "Alarms & reminders" off, alarms became inexact (up to an hour late) while Alarm Health said
    they were not affected.
  - Missions that were **too hard to be fair half asleep**: maths (easy was 87 + 56), a fully shuffled 15-puzzle,
    pattern lines that pass 5 dp from other dots, 90-character typing phrases, a photo match that failed when the
    reference was taken under a lamp and the alarm rang in daylight.
- ✱ Third audit (JVM tests in `ThirdAuditFixesTest`, each watched failing first; not yet run on a device):
  - A **snooze across a restart or force-stop** stayed silent if the alarm first rang more than an hour earlier: start-up
    housekeeping, which runs before the reschedule, closed the ring as missed and the restored snooze found nothing to ring.
  - An **ignored "Still awake?" re-ring** rang again hours later, when the next reschedule re-armed a spent deadline.
  - **"Wake now"** on a snoozed test alarm did nothing and stuck its notification; with an unreadable history it cancelled
    a real snooze without ringing.
  - **Dismiss on a snoozed alarm's notification** skipped the still-awake check and did not delete a delete-after-use alarm.
  - A **timer finishing with a full disk or an unreadable database** stayed silent.
- `RingTrapTest` takes each level's settings and every mission through its real exits after any number of restarts, and every
  limit a restored file can hold through restarts to the end: it fails if a ring has no way to end. It also takes every
  level, every mission and every reason a mission can fail (`FallbackReason`) through the backup mission; every level's way
  out (hold, or the chain after its delay, also after a restart); safe mode, where a mission alarm ends only with the plain
  screen's sums; six Emergency pauses; and, with the way out removed, says explicitly that the only exits left are the
  backup mission for a detected failure and the restart cap.
- `FallbackChainTest`: the backup length for every reason at every level, never empty or endless whatever a file says, no
  step harder than MEDIUM, no multi-digit multiplication over 2,000 seeds. `WayOutAndEmergencyTest`: the hold, the chain
  opening after its delay and counted one task at a time across a restart, the way out removed, pause counting (two silent,
  then the sound stays) and its expiry. `CallPauseTest`, `SensorAndCameraWatchTest` (silent sensors, a black camera with
  the torch tried, a dark room that is not a dead camera). `NoContinueTest` fails if a Continue string or `MissionWayOut`
  comes back.
- `ResurfacePolicyTest`: the pull-back table second by second (1, 1, 1, 5, 10 s backoff and its reset after a minute, a
  ring that never had a screen, only a heads-up without the permission or while locked, nothing over a call, a pause or a
  permission dialog, the screen lit 3 s after it goes off, an SOS of three presses holding everything off for a minute).
  `VolumeFloorTest`: the floor against the hearing cap (the cap wins). `MuteAndCalmTest`: the mute budget spent one at a
  time and saved through a restart without the mute coming back, a mute that ends after 20 s without progress however much
  the screen is touched, a key silence spending the same budget, and touches that no longer calm an alarm.
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

- **After the usability changes and the merge into one build: the full instrumented suite, 181 tests, on the device.**
  The first run shared the phone with another project's instrumented tests, which held the UI-automation connection
  and the screen: 161 passed. Of the 20 failures, 19 never reached an assertion ("no compose hierarchies", "UiAutomation
  already registered"), and one was the tone preview losing its sound while the other app had the phone. Re-run once
  the other tests had stopped, those six classes passed (39 of 40, then the remaining class 9 of 9). A second repeat was cut short the same way when the phone's Settings app came to the front,
  so every test has passed on the device, but not all in one uninterrupted run.
- **Before that, on the `play` build: 165 tests on the device** (the engine,
  reliability, abuse, migration, category, settings switch-row, time-picker, audit-fix, ring-screen and mission-flow
  classes, including the 47 added by the reliability
  audit: audio focus loss, dying players, a real `RingService`, failing storage, refused starts, clock changes and crash
  rescue). The GitHub-only update tests (25: popup, switch, links, backup restore) ran on the `github` build. One audio
  test asserted a diagnostics note a few milliseconds before the engine wrote it and failed once in a while; the
  assertions now wait for the note, and the class passed on four repeated runs. Room's migrations from versions 1 and 2
  are tested on a real database.
- The second audit's fixes on the device: the typed-time bug was reproduced with the old wheel (06:45 typed over 07:00
  reported 06:58, then 06:55) and passes with the fix; a simulated force-stop (every trigger cancelled) is repaired by
  the next app start; the group, skip, schedule-change, reboot-recovery and fired-snooze cases pass against the real
  AlarmManager and Room. Another project's instrumented tests were running on the same phone at the time, which
  caused a few "no compose hierarchy" failures; each of those classes passed when run again on its own.
- Strict alarms (before the way out and Emergency changed): the ring screen offered the press-and-hold stop by default and
  hid it when an alarm had it switched off; turning it off asked for confirmation; the snooze switch on its card works
  without opening the card.

## Not verified. Please be sceptical here

These could not be done safely or at all in the development setup. They are the first things to test on your own phone.

| Scenario | Why it matters / status |
|---|---|
| An **hours-long ring** with the ring timeout set to Never | The state machine, the backup interplay and the wake-lock renewal are JVM-tested with a moved clock and a mock lock; a real multi-hour ring on a device (renewal in `dumpsys power`, playback, vendor battery managers, Android 15+ service limits) was not observed |
| The **database updates to versions 4 and 5** on a real install, and a notification action on a mission alarm | The migration has an instrumented test (`DatabaseMigrationTest`, compiled but not yet run on a device), its defaults are compared to the model on the JVM, and the command rules are JVM-tested; installing over a release with real alarms and tapping the real notification were not done |
| A real **process kill during a ring** (`START_REDELIVER_INTENT`), **Stop in Active apps** and a real **crash of the ring screen** (`RingCrashGuard` rescue) | The service flag is checked with `dumpsys`, and the saved ring, the sentinel and the rescue are armed and replayed in tests (`DurableRingTest`, and `RingServiceInstrumentedTest`, compiled but not yet run on a device), but the OS restarting a killed process, the sentinel firing for real and the gap in sound were not observed |
| **`systemExempted`** as the ring service's type on a real phone, and **`ExitPostMortem`** reading real exit reasons | The choice of type and the fallback's order are JVM-tested and the type in use has an instrumented test (not yet run). Whether Android accepts it from a boot start, and how sound behaves under Android 17's audio hardening with it, were not observed. The wording Android uses for a memory-limiter kill is from memory |
| The **evening check** posting its notice and its fix button, the **silent re-arm**, and the **ledger** explaining a real force stop, power-off or late delivery | Policies and wake-up counts are JVM-tested (`TonightCheckTest`, `RingLedgerTest`); `TonightCheckInstrumentedTest` (compiled, not yet run on a device) fires the real trigger. Which exit reason and description Android records for `am force-stop` and Active apps Stop was not observed, so how the ledger names those is from the documentation; before Android 11 it cannot tell a force stop at all |
| **Android 15+ foreground-service limits** (media services from a boot receiver; `onTimeout`) | The refused-start retry and fallback notification are tested with a simulated refusal; the real restriction and a real timeout were not provoked |
| **Notifications blocked, ring channel muted or full-screen permission revoked** | The sound path does not use any of them (tested with no ring screen); the decision and the direct launch of the screen are tested, but the system was not put in those states |
| Real **headphones or Bluetooth** plugged and pulled mid-ring | The route logic is tested by feeding it a pretend headset; a real `AudioDeviceCallback` event and the per-output alarm volume were not observed |
| A **corrupt database file** | Room deletes and recreates it, so the alarm definitions are lost (only an exported backup restores them); the engine's behaviour with an unreadable database is tested, a corrupt file was not produced |
| Real **power-off and reboot**, including ringing **before first unlock** (Direct Boot), and a **ring coming back after one** | Designed and tested with a simulated restart (`DurableRingTest`: as many as the alarm's limit come back, at full volume with no snooze, the next one and anything past the window are reported as missed; `MissedAlarmRecoveryTest` for an alarm due while off), but not demonstrated with a real power cycle. A real **force-stop** and its missed notice at the next start are the same |
| **Forced deep Doze** (`dumpsys deviceidle force-idle`) | Needs changing device power state; `setAlarmClock` is Doze-exempt by design. Not forced, only ordinary screen-off idle |
| **Time-zone change and DST transition** on a device | Maths covered by 20k fuzz cases and DST unit tests; the broadcast path is exercised by calling the handler (`SystemEventHandler`), but the system was never asked to change its clock or zone |
| **Other manufacturers** (Samsung, Xiaomi, OnePlus, Huawei, …) | Their battery managers can still kill background work. Alarm health helps detect settings but cannot override a vendor |
| **Other Android versions** (8.0–16) | Only Android 17 on hardware. minSdk-26 code paths (e.g. no `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`) are branch-tested, not run |
| Human **shaking, squats and steps** | Counters are unit-tested with synthetic sensor traces; real motion was not performed |
| The **backup mission** on a real phone: camera permission revoked before a locked ring, NFC off, the lens covered for a minute, a dead sensor | `FallbackChainTest`, `SensorAndCameraWatchTest` and `RingTrapTest` cover the rules; `RingAndMissionUiTest` and `RobustUiTest` (no Continue node, a keypad appears) are compiled, not yet run on a device. The permission dialog over the lock screen was not observed |
| **Emergency** on a real phone: the phone app or the lock-screen Emergency call button, a real incoming call during a ring, the safe ring screen after two crashes | The pause, its count and the call budget are JVM-tested (`WayOutAndEmergencyTest`, `CallPauseTest`); opening the dialer, the bouncer and `AudioManager` mode changes were not observed, nor `SafeRingActivity` on the lock screen |
| **Coming back to the ring screen** on a real phone: Home, Recents, a swiped card, Clear all, split screen, opening the app, a ring back from a kill or a restart, both navigation modes, the screen switched off, a 5-press SOS, the camera double-press, Circle to Search under `FLAG_SECURE` | `ResurfacePolicyTest` covers the rules on the JVM; `RingServiceInstrumentedTest` (Home, Recents and a swipe in Recents with the permission granted by `appops`, and the screen staying away without it) is compiled, not yet run on a device. Whether Android allows the start during Overview, and whether a re-alert disturbs the lock-screen Emergency dialer, were not observed |
| The **volume lock** and **forced speaker** on a real phone: keys, the shade slider and Settings turning the alarm down, headphones with the hearing cap | `VolumeFloorTest` covers the floor and the cap; the heartbeat that holds it and the routed-device check were not observed with real audio |
| **Focus Tap** half asleep, and **mission rotation** over real mornings | `FocusGameTest`, `MissionRotationTest` (a month, across daylight-saving changes) and `RotationRingTest` cover the rules; `FocusTapUiTest` (touch, screen reader, RTL, font scale 2.0) is compiled but not yet run on a device. Whether it takes under a minute first thing in the morning, or can be done on reflex, was not tried |
| **NFC** tag read over the lock screen | The mission logic is tested; no tag was scanned |
| **QR/barcode** decode with a printed code; **photo match** against a detailed real scene | Decoder/matcher tested on generated images only |
| **TalkBack** end to end | Content descriptions and large-text/RTL are tested; a TalkBack walkthrough was not done |
| **Headphones / Bluetooth** routing | Policies tested on the JVM; no real headset was connected (see also the row above) |
| **Tablets and foldables** | Layout adapts by window size; not seen on such hardware |
| Widgets and tile **placed and interacted with** | Registered, not added to a home screen or tile layout (that would change the owner's launcher) |
| Android 17 **background-audio hardening** | Currently warn-only for alarm apps; a later platform update could enforce more |

## Device safety rules used for this project

- Exactly one physical device was used, identified by model **and** serial before every command, with `adb -s <serial>`
  on each call. Another attached device was never touched.
- No factory reset, bootloader unlock or system-settings change. Test alarms and backups made during testing were deleted.
