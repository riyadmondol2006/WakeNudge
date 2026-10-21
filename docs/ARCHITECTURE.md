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
`BEDTIME`, `WATCHDOG`), the owner `id` and an `index` (for example, which backup ring). Two triggers can therefore
never overwrite each other by accident, and any of them can be found and cancelled later.

- Wake-up alarms, snoozes, backup rings and the "still awake?" deadline use `AlarmManager.setAlarmClock`: exempt from
  Doze, shown by the system as the next alarm, and allowed to start the ring UI.
- Timers, bedtime, lead-in notices and similar use `setExactAndAllowWhileIdle`.
- A 6-hour inexact **watchdog** re-validates everything. It is armed by `rescheduleAll` and by the first saved alarm.
- `PendingAlarms` wraps every OS call: `SecurityException`, `IllegalStateException` (the OS limit of 500 alarms per app)
  and others become a `false` return plus `lastFailure`, never a crash.

## Scheduling

`NextTriggerCalculator` is a pure function from an alarm, "now" and a context (vacation, skip, exceptions) to the next
instant. It works in local date-time and converts at the end, so daylight-saving changes behave: a time that falls in a
spring-forward gap is moved forward by the length of the gap, and a time that occurs twice in a fall-back overlap rings
at its first occurrence only.

`AlarmSchedulerImpl.rescheduleAll(reason)` is the one place that rebuilds the world (boot, update, time-zone change,
exact-alarm permission change, restore, watchdog, tile). It is:

- **Fault-isolated**: each alarm is planned in its own `try/catch`, so one corrupt row (say, hour 99) cannot stop the others.
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
intensify. On headphones a hearing-safety cap applies. `playWithFallback` tries the chosen sound, then the default bundled tone, then a
`ToneGenerator` beep on the alarm stream, so a deleted song or unplugged SD card still produces a sound.

Android 17 logs warn-only "background playback would be muted" lines for background apps. Because WakeNudge is an
alarm-clock app holding `USE_EXACT_ALARM` and plays `USAGE_ALARM`, real playback is unmuted (checked with
`dumpsys audio`: `mutedState:none`). This is platform behaviour to keep watching.

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
foreground service.

## Missions

`MissionHost` runs a list of `MissionConfig`s in order and reports progress. Each mission (`math`, `memory`, `typing`,
`scan`, `nfc`, `sensors` for shake/steps/squats, `puzzle`, `photo`) has a pure core (problem generation, matching,
rep counting) tested on the JVM and a thin Compose UI. A mission that cannot be completed on this phone (no camera, no
step sensor, permission refused) shows "Continue" instead of trapping the user.

## Health

`HealthChecker` reads the system (notifications, full-screen intent, exact alarms, battery optimisation, DND, audio
route, scheduler state) into a `HealthSnapshot`; `HealthEvaluator` is a pure function that turns it into
errors/warnings with fixes. The test alarm goes through the real trigger path.

## Offline guarantee

`app/build.gradle.kts` registers `verifyOfflineManifest<Variant>` for every variant: it fails the build when the merged
manifest declares `INTERNET`, `ACCESS_NETWORK_STATE` or `ACCESS_WIFI_STATE`. `ACCESS_NETWORK_STATE`, which some
libraries add, is removed in the manifest with `tools:node="remove"`.

## UI

Material 3 (stable 1.4) with an "expressive" layer built in-house, because the Material 3 Expressive APIs are not public
in the stable artifact: spring specs (`WakeMotion`), blob/cookie shapes from `graphics-shapes`, a custom animated time
picker, wavy progress, and an expressive switch and day chips. Navigation uses type-safe Navigation-Compose routes and a
`NavigationSuiteScaffold` that adapts between bottom bar and rail.
