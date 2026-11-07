# Architecture

WakeNudge is a single-module Android app written in Kotlin with Jetpack Compose, using MVVM with a small domain layer.
Hilt wires it together, Room stores alarms and history, DataStore stores settings, and coroutines/Flow connect them.

```
ui (Compose, ViewModels)  ─▶  domain (models, policies, use cases)  ◀─  data (Room, DataStore, backup)
            │                          ▲
            └────────────▶  engine (scheduler, ring, audio, wake-check, timers, recovery)
```

The domain layer is plain Kotlin. The scheduling maths, snooze policy, sleep planner and edit-lock policy have no
Android dependencies and are tested on the JVM.

## One rule: every trigger is identified by (kind, id, index)

All OS alarms go through `PendingAlarms`. A trigger is a `PendingIntent` whose data URI encodes
`kind` (`ALARM`, `SNOOZE`, `BACKUP`, `SUNRISE`, `UPCOMING`, `WAKE_CHECK_PROMPT`, `WAKE_CHECK_DEADLINE`, `TIMER`,
`BEDTIME`, `WATCHDOG`, `RING_RETRY`, `BATTERY_CHECK`, `RING_SENTINEL`), the owner `id` and an `index` (for example, which backup ring). Two triggers can therefore
never overwrite each other by accident, and any of them can be found and cancelled later.

- Wake-up alarms, snoozes, backup rings and the "still awake?" deadline use `AlarmManager.setAlarmClock`: exempt from
  Doze, shown by the system as the next alarm, and allowed to start the ring UI.
- Timers, bedtime, lead-in notices and similar use `setExactAndAllowWhileIdle`.
- A 6-hour inexact **watchdog** re-validates everything. It is armed by `rescheduleAll` and by the first saved alarm.
- `PendingAlarms` wraps every OS call: `SecurityException`, `IllegalStateException` (the OS limit of 500 alarms per app)
  and others become a `false` return plus `lastFailure`, never a crash. If `setAlarmClock` itself is refused, an exact and
  then an inexact wake-up is tried instead (`lastFallback`): a slightly imprecise alarm beats none.
- `RING_RETRY` rings an alarm again that was cut off (see "When things go wrong"). Its index carries the ring kind and the
  attempt number (`RingRetry`), so a retry of a retry cannot loop for ever.
- `RING_SENTINEL` is pushed 30 seconds ahead while a ring that keeps coming back is on (see "A ring outlives its process").
- `BATTERY_CHECK` is the evening check before the next alarm (index 0) and a silent re-arm 30 minutes before it (index 1);
  see "Tonight check and ring ledger".

## Scheduling

`NextTriggerCalculator` is a pure function from an alarm, "now" and a context (vacation, skip, exceptions) to the next
instant. It works in local date-time and converts at the end, so daylight-saving changes behave: a time that falls in a
spring-forward gap is moved forward by the length of the gap, and a time that occurs twice in a fall-back overlap rings
at its first occurrence only.

`AlarmSchedulerImpl.rescheduleAll(reason)` is the one place that rebuilds the world (boot, update, time-zone change,
exact-alarm permission change, restore, watchdog, tile). It is:

- **Fault-isolated**: each alarm is planned in its own `try/catch`, so one corrupt row (say, hour 99) cannot stop the others.
- **Safety-first**: arming the alarms comes first and depends on as little as possible. Settings, vacation, the stale-skip
  cleanup, follow-up restoration, timers, bedtime and the watchdog each run in their own guarded step, so a full disk or an
  unreadable settings file can never stop alarms from being armed. If the OS refuses or runs out of room the user gets an
  "An alarm may not ring" notification and an Alarm Health entry (`RuntimeState.lastSchedulingProblem`).
- **Budgeted**: alarms are sorted soonest-first and at most 440 are armed. Lead-in and heads-up triggers are a bonus for
  alarms in the next 36 hours, within a total budget of 470, leaving headroom below the OS limit for snoozes, backups,
  timers and the watchdog.
- **Idempotent**: running it twice is the same as running it once.

## Ringing

`RingController` is a mutex-guarded state machine. `RingSessionStore` is a `StateFlow<RingSession?>` that both the
`RingService` (foreground, `systemExempted` on Android 14+ with `mediaPlayback` as the fallback and below that) and the
UI observe.

- **Collisions**: if two alarms fire together, or an alarm and a timer, an alarm outranks a timer (the timer returns to
  idle) and otherwise the newcomer is queued and rung after the first is handled. Sunrise sessions are replaced.
- **Duplicates**: the same occurrence delivered twice (the OS can replay a broadcast) is ignored if it is already on
  screen or snoozed, so an alarm never rings twice for one occurrence.
- **Outcomes** are written to history (`RINGING`, `DISMISSED`, `SNOOZED`, `MISSED`, …) with the follow-ups they own.
- **Commands**: everything that can end or quiet a ring (snooze, dismiss, hold stop, way out, timeout, key silence, mission
  mute, emergency pause) asks one pure function, `RingPolicy.decide(session, command, proof)`. Only the ring screen has
  proof to offer (a gesture, a hold, finished missions); a notification action, a key or a stale button has none, and
  `RingTarget` carries the event id so a button from an earlier ring of the same alarm matches nothing. `RingController`
  counts missions itself, one step at a time, and that count is what "missions complete" is checked against and what
  history records. Safe mode (below) is the one remaining shortcut.
- **Ring timeout**: an ignored ring ends after `ringTimeoutMinutes`. 0 is "Never": only a dismissal, a snooze or an
  Emergency stop ends it (a backup firing just upgrades it). Timers and the test alarm carry their own fixed timeouts.

### Audio

`AlarmAudioEngine` plays with `USAGE_ALARM`, saves the alarm-stream volume on start and restores it on stop.
`VolumePlanner` computes the ramp (up to 10 minutes), the Extra Loud boost (`LoudnessEnhancer`) and the 40-second
intensify. On headphones a hearing-safety cap applies.

Once started, the engine looks after itself:

- **Fallback chain**: the chosen sound, then the bundled default tone, then the system alarm tone, then an emergency
  `ToneGenerator` beeper on the alarm stream. A sound that takes longer than 4 s to open (a song on a cloud drive, a
  stalled card) is abandoned for the next candidate; a late arrival is released, never played.
- **Generation guard**: every `start`/`stop` bumps a counter, so a sound that finishes loading after its ring ended is
  discarded instead of playing for an alarm that is over.
- **Heartbeat** (1 s): if the player is not playing (paused by another app, interrupted by a call, dead) it is resumed, or
  rebuilt from the fallback chain when it will not resume. A `MediaPlayer` error does the same. More than four rebuilds in
  30 s go straight to the beeper, so a player that dies on start cannot cause a rebuild storm; real playback is retried
  every 15 s while the beeper sounds.
- **Focus**: the engine asks for transient-exclusive focus but never gives way. A loss (call, assistant, music app) is
  logged and only triggers a check that nothing paused the player.
- **Route changes**: an `AudioDeviceCallback` re-applies the volume plan when headphones are plugged or pulled (the alarm
  stream keeps one volume per output). The hearing cap applies only while a headset is really the route (not when the
  alarm is pinned to the phone speaker), and the original volume of each route is restored at the end. There is no
  "becoming noisy" pause: an alarm must not behave like a music player.
- **Playlists**: a track that cannot be opened is skipped; if none can, the fallback chain takes over.
- **Announcements**: the lowered volume while text-to-speech talks expires after 15 s in case the engine never reports
  that it finished.
- Whenever a fallback was needed, `RuntimeState.lastAudioProblem` records it and Alarm Health says so.

Android 17 logs warn-only "background playback would be muted" lines for background apps. Because WakeNudge is an
alarm-clock app holding `USE_EXACT_ALARM` and plays `USAGE_ALARM`, real playback is unmuted (checked with
`dumpsys audio`: `mutedState:none`). This is platform behaviour to keep watching.

### When things go wrong

One rule: **persistence, the notification and the ring screen never decide whether an alarm rings or stops.**

- **Storage.** Reading or writing the database or settings can fail (full disk, locked or corrupt file). `RingController`
  treats history, follow-up bookkeeping and settings as best effort around the sound: the ring starts with safe defaults,
  a snooze is armed with the OS before it is recorded, a dismissal cancels the OS triggers first and always ends the
  session. A trigger for an alarm that the OS holds but the database lost (`RuntimeState.registeredTriggers`) rings from
  its saved copy if it is an alarm a plain one would soften (`AlarmSnapshots`), otherwise as a plain "Alarm"; a trigger whose engine path throws still rings with what the trigger itself carries
  (`TriggerHandler.ringing`). `begin` never throws (it falls back to an emergency session). Housekeeping at process start
  cannot crash the process that an alarm woke. DataStore replaces a corrupt settings file by defaults.
  If Room has to delete a corrupt database the alarm definitions are gone; keep an exported backup.
- **The ring service.** `RingService` returns `START_REDELIVER_INTENT`, so after the OS kills the process mid-ring the
  service is started again with the original request. Its coroutine scope has an exception handler and every step
  (notification, sound, vibration, voice, escalation) is guarded separately. Wake locks live in `RingWakeLocks`: every one
  is timed (the service renews the ring lock while it runs, because a ring can outlast the timeout), a short "handoff"
  lock bridges the gap between the trigger receiver and the service. Destroyed while still
  ringing, the service arms a `RING_RETRY` and drops its orphaned session (otherwise later alarms would queue behind it).
  If the system refuses `startForeground` the service carries on as best it can and lines up a `RING_RETRY` too.
  `onTimeout` (Android 15+ foreground-service time limits) moves the ring to the other service type in the same service,
  so the sound does not stop; with no other type to move to the service stops and rings again from its saved state.
- **The ring screen shares the process with the sound**, so a crash in it would silence the alarm. `RingCrashGuard` chains
  in front of the platform's uncaught-exception handler: if a ring is on it arms a `RING_RETRY` two seconds ahead
  (alarm-clock trigger, exempt from background-start limits) and the platform handler then ends the process. The new
  process rings the same event again (`RingController.onRetry` refuses if it was handled meanwhile or something already
  rings). After two crashes in ten minutes the notification stops launching the screen and carries Snooze/Dismiss itself
  (`RingSession.safeMode`); after five it stops rescuing, except for an alarm that keeps coming back
  (`AntiOversleepSettings.keepsComingBack`), whose rescues back off to once a minute instead.
- **The OS refuses to start the service** (Android 15 forbids media foreground services from boot-completed receivers, which
  `systemExempted` avoids; vendors differ): `RingServiceLauncher` posts a fallback notification that sounds the alarm tone by itself (`ring_fallback_v1`,
  insistent) and retries from a `RING_RETRY` trigger up to three times.
- **Notifications blocked, channel muted or full-screen permission revoked**: the sound does not depend on any of them. The
  service opens the ring screen itself (best effort where Android allows it) and lights the display for a few seconds.
- **The OS refuses an alarm or a snooze**: see `PendingAlarms` above; a snooze that cannot be armed leaves the alarm
  ringing instead of silencing it.
- **The clock changes** (`SystemEventHandler`): the time or time zone being set reschedules everything and then replays the
  boot-time check, so an alarm that the jump made overdue rings (as late as `MissedAlarmRecovery` below allows) or is recorded as missed, instead
  of silently moving to tomorrow. An occurrence that already rang is never repeated (`findOccurrence`).

### A ring outlives its process

`DurableRingRecord` holds what a ring must not lose: the alarm as it fired and everything counted so far. It is JSON in
`RuntimeState` rather than Room, so it is readable before the first unlock and survives a corrupt database, and it is not
part of a backup. It is written when a ring begins and at each mission step (mute and pause when they arrive), not on
every touch, and removed when the ring ends, whichever way. Timers and test alarms have none. The rings queued behind it are saved
next to it. Reading it never throws: a corrupt record counts as no record, and storage trouble never affects the sound.

- **Killed** (the system, Stop in Active apps): a `RING_SENTINEL` trigger is pushed 30 seconds ahead while an alarm that keeps
  coming back rings, or while anything is queued, and it only fires if the process is gone. `RingController.begin` picks
  up the record, so strictness and progress return with the sound. `START_REDELIVER_INTENT` takes the same path. An alarm
  without a sentinel comes back only when something else starts the app, and is reported as missed after an hour.
- **Restarted**: the boot broadcast, the app start and the watchdog all call `reviveDurableRing`. A new boot (`Settings.Global.BOOT_COUNT`,
  or elapsed time going backwards when that is unavailable) uses up one of the alarm's `maxResumes` (3 unless changed, never
  more than 20) and must be within its `resumeWindowMinutes` of when the ring first started (`ResumePolicy`). The ring starts
  from a sentinel armed 2 seconds ahead, not from the receiver, because an alarm trigger is where the system allows the service
  to start. A ring back from a restart stays at full volume with no ramp (`RingSession.soundAlarm`; only a headset's hearing
  cap still applies) and `RingPolicy` refuses it a snooze, so restarting never buys a quieter alarm or more sleep.
- **Past the limit or the window**: no ring. The event closes as missed with its backups cleared, and a notification says so.
- **Force-stopped**: Android cancels every trigger and starts nothing. At the next app start the watchdog being gone in the
  same boot, on the same version, marks this case: the ring is reported as missed. An app update looks similar but rings again.
- **Another alarm arrives after a kill, before the ring is back**: it waits behind the ring that was on, as it would have
  if the process had lived, so its saved progress is not overwritten.

The engine reads concrete settings, never a level. Inactivity, silence and mute timing use `TimeSource.elapsedMillis`, so
changing the clock neither ends nor extends them. `ExitPostMortem` logs how the last process ended when that was a user stop or
a memory kill during a ring. Restoring a backup is refused while a record exists.

### Follow-ups survive everything

Snooze, backup rings and the wake-up check are **persisted** (`AlarmEvent.snoozeUntil`, `pendingBackups`,
`WakeCheck` rows), not just held as OS alarms. After a reboot, app update or process kill, `restoreFollowUps`,
`restoreWakeChecks` and `restoreTimers` re-arm them, and skip follow-ups whose alarm no longer exists. One that came due
while the phone was off still rings within an hour, or within the alarm's own resume window if that is longer.
Disabling or deleting an alarm cancels its snooze, backups and wake-check (`cancelFollowUps`).

`MissedAlarmRecovery` runs after boot and app updates. For alarms that were due while the phone was off it rings now if
the alarm was due within the last 10 minutes or its `resumeWindowMinutes`, whichever is longer (from its saved copy if the
database cannot be read); that ring is an ordinary first ring. Otherwise it records the alarm as missed, posts a "missed alarm"
notification and turns a one-time alarm off. A separate pass resets timers stuck in `RINGING`.

## Direct Boot

Room, DataStore and SharedPreferences live in **device-protected storage**, and the receivers, ring service and ring
activity are `directBootAware`, so alarms can ring after a restart before the first unlock.

## Receivers and DI

Receivers are not `@AndroidEntryPoint`; they reach singletons through a Hilt `EntryPoint`. Starting the ring
service goes through a one-method interface, `RingStarter`, so trigger handling can be tested without starting a
foreground service. `SystemEventHandler` holds what the system-event receiver does (each step guarded on its own) so it can
be tested without a real broadcast.

## Missions

`MissionHost` runs a list of `MissionConfig`s in order and reports progress. Each mission (`math`, `memory`, `typing`,
`scan`, `nfc`, `sensors` for shake/steps/squats, `puzzle`, `photo`) has a pure core (problem generation, matching,
rep counting) tested on the JVM and a thin Compose UI. A mission that cannot be completed on this phone (no camera, no
step sensor, permission refused) shows "Continue" instead of trapping the user.

## Health

`HealthChecker` reads the system (notifications, full-screen intent, exact alarms, battery optimisation, DND, audio
route, scheduler state) into a `HealthSnapshot`; `HealthEvaluator` is a pure function that turns it into
errors/warnings with fixes. The test alarm goes through the real trigger path. Besides the system state it reports what
the engine itself noticed in the last three days: alarms armed the second-best way or not at all, a sound that needed a
fallback (including the emergency beeper), a ring that was cut short by a crash, and Battery Saver.

### Tonight check and ring ledger

About 8 hours before the next alarm (or straight away if it is sooner, and again with the bedtime reminder) `TonightCheck`
asks whether it will really work: its trigger is registered, notifications and the ring channel, full-screen permission,
exact alarms, background restriction, Do Not Disturb, the battery, and every mission the alarm has, not just the one a
rotation would pick (permission, NFC on, code set, sensor; `MissionProbe`). `TonightCheckPolicy` is the pure part. All fine:
nothing is shown. Otherwise one notification names the worst problem with a button for its fix (opened through
`MainActivity`, so `HealthFix.launch` can fall back to a nearby Settings screen) and says how many more there are. A
missing trigger is put back silently with `AlarmScheduler.rearm`, which registers the main trigger again for the time it
is already set for and changes nothing else; a second, silent re-arm runs 30 minutes before the alarm. The check writes
no alarm, does nothing at all while a ring is on, and remembers which alarm time it looked at
(`RuntimeState.tonightCheckedFor`), so reschedules and restarts add no wake-ups. Without notifications the notice cannot
be posted; Alarm Health shows that problem instead. The "Warn me the night before" switch on the Sleep screen turns the
notices off (not the re-arm).

`RingLedger` compares, at each process start and when Alarm Health opens, when recent alarms were due with when they
rang, and explains what it can from the history, how the app's last processes ended (`ExitPostMortem.exits`) and when
the phone last started: force stopped, phone off, closed by Android, delivered more than a minute late, restarted or
stopped while it rang, a backup sound used. A cause is settled when first seen (the boot time changes later) and kept
for a week in `RuntimeState`, not in a backup. An alarm that did not ring on time in the last day gets one notification
in place of its plain "missed" notice; Alarm Health lists the rest.

## Offline guarantee

`app/build.gradle.kts` registers `verifyOfflineManifest<Variant>` for every variant: it fails the build when the merged
manifest declares `ACCESS_NETWORK_STATE` or `ACCESS_WIFI_STATE`. The app holds `INTERNET` and no other network
permission; see "Update check" below. `ACCESS_NETWORK_STATE`, which some libraries add, is removed in the manifest with `tools:node="remove"`.

## UI

Material 3 (stable 1.4) with an "expressive" layer built in-house, because the Material 3 Expressive APIs are not public
in the stable artifact: spring specs (`WakeMotion`), blob/cookie shapes from `graphics-shapes`, a custom animated time
picker, wavy progress, and an expressive switch and day chips. Navigation uses type-safe Navigation-Compose routes and a
`NavigationSuiteScaffold` that adapts between bottom bar and rail.

## Update check

`UpdateManager` (`update/`) asks `api.github.com` for the latest release (at most once an hour, on app open and when
onboarding finishes, never during a ring) and offers a popup that opens the release page in the browser. The user can
switch it off in About, then Updates; debug builds never check by themselves. The main screen only knows the
`UpdateChecker` interface, so its view model is tested without the network, and UI tests swap the GitHub client for a
scripted one (`TestUpdateModule`). A JVM test (`OfflineClaimsTest`) checks that no user-facing text claims the app
cannot go online, and that text about going online names the update check and says it can be switched off.

## Database migrations

Room's schema is exported to `app/schemas/`. Every schema change bumps `WakeNudgeDatabase.version`, adds an explicit
`Migration` in `data/db/Migrations.kt` and is covered by `DatabaseMigrationTest`, which builds a real database of the
previous version, migrates it and checks that no alarm is lost. There is deliberately no destructive fallback:
an alarm clock must never lose alarms because the app updated. (Settings live in DataStore as one JSON document; new
fields only need a default.)

## Alarm options, as the user sees them

Alarm options are grouped into the same six categories in the editor and in "New alarm defaults"
(`ui/edit/EditCategories.kt`): wake-up challenge, sound and light, volume and vibration, snooze, oversleep protection and
schedule rules. Choosing a tone in the sound picker plays it immediately (`SoundPreviewer`, alarm stream, stopped
whenever the picker goes away). Extra Loud is a strength (`VolumeSettings.extraLoudPercent`) that `VolumePlanner`
turns into a proportion of the full boost, and it is never applied on headphones while hearing protection is on.

