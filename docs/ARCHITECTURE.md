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
`BEDTIME`, `WATCHDOG`, `RING_RETRY`), the owner `id` and an `index` (for example, which backup ring). Two triggers can therefore
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
`RingService` (foreground, `mediaPlayback`) and the UI observe.

- **Collisions**: if two alarms fire together, or an alarm and a timer, an alarm outranks a timer (the timer returns to
  idle) and otherwise the newcomer is queued and rung after the first is handled. Sunrise sessions are replaced.
- **Duplicates**: the same occurrence delivered twice (the OS can replay a broadcast) is ignored if it is already on
  screen or snoozed, so an alarm never rings twice for one occurrence.
- **Outcomes** are written to history (`RINGING`, `DISMISSED`, `SNOOZED`, `MISSED`, …) with the follow-ups they own.

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
  session. A trigger for an alarm that the OS holds but the database lost (`RuntimeState.registeredTriggers`) rings as a
  plain "Alarm"; a trigger whose engine path throws still rings with what the trigger itself carries
  (`TriggerHandler.ringing`). `begin` never throws (it falls back to an emergency session). Housekeeping at process start
  cannot crash the process that an alarm woke. DataStore replaces a corrupt settings file by defaults.
  If Room has to delete a corrupt database the alarm definitions are gone; keep an exported backup.
- **The ring service.** `RingService` returns `START_REDELIVER_INTENT`, so after the OS kills the process mid-ring the
  service is started again with the original request. Its coroutine scope has an exception handler and every step
  (notification, sound, vibration, voice, escalation) is guarded separately. Wake locks live in `RingWakeLocks`: every one
  is timed, a short "handoff" lock bridges the gap between the trigger receiver and the service. Destroyed while still
  ringing, the service arms a `RING_RETRY` and drops its orphaned session (otherwise later alarms would queue behind it).
  If the system refuses `startForeground` the service carries on as best it can and lines up a `RING_RETRY` too.
  `onTimeout` (Android 15+ foreground-service time limits) hands the ring to a fresh service.
- **The ring screen shares the process with the sound**, so a crash in it would silence the alarm. `RingCrashGuard` chains
  in front of the platform's uncaught-exception handler: if a ring is on it arms a `RING_RETRY` two seconds ahead
  (alarm-clock trigger, exempt from background-start limits) and the platform handler then ends the process. The new
  process rings the same event again (`RingController.onRetry` refuses if it was handled meanwhile or something already
  rings). After two crashes in ten minutes the notification stops launching the screen and carries Snooze/Dismiss itself
  (`RingSession.safeMode`); after five it stops rescuing.
- **The OS refuses to start the service** (Android 15 forbids media foreground services from boot-completed receivers; vendors
  differ): `RingServiceLauncher` posts a fallback notification that sounds the alarm tone by itself (`ring_fallback_v1`,
  insistent) and retries from a `RING_RETRY` trigger up to three times.
- **Notifications blocked, channel muted or full-screen permission revoked**: the sound does not depend on any of them. The
  service opens the ring screen itself (best effort where Android allows it) and lights the display for a few seconds.
- **The OS refuses an alarm or a snooze**: see `PendingAlarms` above; a snooze that cannot be armed leaves the alarm
  ringing instead of silencing it.
- **The clock changes** (`SystemEventHandler`): the time or time zone being set reschedules everything and then replays the
  boot-time check, so an alarm that the jump made overdue rings (up to 10 minutes late) or is recorded as missed, instead
  of silently moving to tomorrow. An occurrence that already rang is never repeated (`findOccurrence`).

### Follow-ups survive everything

Snooze, backup rings and the wake-up check are **persisted** (`AlarmEvent.snoozeUntil`, `pendingBackups`,
`WakeCheck` rows), not just held as OS alarms. After a reboot, app update or process kill, `restoreFollowUps`,
`restoreWakeChecks` and `restoreTimers` re-arm them, and skip follow-ups whose alarm no longer exists.
Disabling or deleting an alarm cancels its snooze, backups and wake-check (`cancelFollowUps`).

`MissedAlarmRecovery` runs after boot and app updates. For alarms that were due while the phone was off it rings now if
the alarm was due within the last 10 minutes; otherwise it records the alarm as missed, posts a "missed alarm"
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

## Offline guarantee

`app/build.gradle.kts` registers `verifyOfflineManifest<Variant>` for every variant: it fails the build when the merged
manifest declares `INTERNET`, `ACCESS_NETWORK_STATE` or `ACCESS_WIFI_STATE`. `ACCESS_NETWORK_STATE`, which some
libraries add, is removed in the manifest with `tools:node="remove"`.

## UI

Material 3 (stable 1.4) with an "expressive" layer built in-house, because the Material 3 Expressive APIs are not public
in the stable artifact: spring specs (`WakeMotion`), blob/cookie shapes from `graphics-shapes`, a custom animated time
picker, wavy progress, and an expressive switch and day chips. Navigation uses type-safe Navigation-Compose routes and a
`NavigationSuiteScaffold` that adapts between bottom bar and rail.
