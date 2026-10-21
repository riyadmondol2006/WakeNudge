<p align="center">
  <img src="docs/images/logo.png" alt="WakeNudge logo" width="160">
</p>

<h1 align="center">WakeNudge: Alarm Clock</h1>

<p align="center">
  A free, open-source alarm clock for Android that makes sure you actually get up.<br>
  No ads. No accounts. No tracking. No internet permission.
</p>

<p align="center">
  <a href="LICENSE">Apache-2.0</a> ·
  Kotlin · Jetpack Compose · Material 3 ·
  Android 8.0+ (API 26) · targets Android 17 (API 37)
</p>

---

WakeNudge combines what people like about Alarmy, Sleep as Android, AMdroid and Google Clock into one calm,
Pixel-style app, with original code and design. Everything runs on the device. The merged app manifest is
checked at build time to make sure it never asks for `INTERNET`, `ACCESS_NETWORK_STATE` or `ACCESS_WIFI_STATE`.

## Features

**Alarms**
- Unlimited alarms with labels, repeat days, one-time dates, groups/profiles (Work, Weekend, …) and per-alarm settings
- Skip next ring, vacation mode, holiday and calendar exceptions, upcoming-alarm heads-up, delete-after-use
- Bedtime reminders and a sleep planner (wake-up time ↔ sleep cycles)
- Timer, stopwatch with laps, world clock, live countdown to the next alarm
- Home-screen widgets (next alarm, alarm list, clock) and a Quick Settings tile
- Local backup and restore to a file you choose (JSON, with validation and a preview before anything is replaced)

**A sound that wakes you**
- 12 bundled tones (plus two internal ones for the intensify layer and backup alarms), loudness-normalised to within about 0.4 LU of each other, and your own local music
- Volume ramp (up to 10 minutes), random tone per ring, strong vibration patterns (or record your own rhythm)
- Extra Loud mode, and the sound intensifies after 40 seconds without interaction
- Offline spoken announcements using the phone's text-to-speech engine
- Sunrise mode (screen brightens gradually) and a separate backup alarm
- Hearing safety: a configurable volume cap on headphones, and the system alarm volume is restored afterwards

**Missions: prove you are awake**
Math, memory, typing, QR/barcode, NFC tag, shake, steps, squats, puzzles (sliding tiles, number grid, pattern trace)
and offline photo matching. Difficulty and count are configurable per alarm, you can chain several missions, preview
each one, and use "Solve to snooze".

**Wake-up check**
After you dismiss, WakeNudge asks "Still awake?" after 1–30 minutes. If you do not answer in time it rings again,
with a repeat limit. The check survives process death and reboots, and you can cancel it explicitly.

**Anti-oversleep, with an exit**
Snooze limits (optionally shrinking), hold-to-dismiss, accidental-dismissal protection, optional edit lock in the last
minutes before an alarm, and configurable backup rings. Every restriction can be bypassed with an **Emergency stop**
(press and hold) so the app can never trap you.

**Reliability**
- Alarms use `AlarmManager.setAlarmClock`, `USAGE_ALARM` audio, a foreground service and a full-screen lock-screen UI
- Built to survive reboots (state lives in device-protected storage so alarms can ring before the first unlock), app updates, time-zone and DST changes, process death and the OS limit of 500 alarms per app
- An **Alarm health** screen diagnoses notifications, full-screen permission, exact-alarm access, battery
  restrictions, silent/Do Not Disturb and audio routing, and has a real **test alarm**
- A morning dashboard with history and streaks

**Design**
Dynamic (Material You) colour, light, dark and AMOLED themes, expressive shapes and spring motion, edge-to-edge,
haptics, large-text and right-to-left support, TalkBack labels, and navigation that adapts to the window size (bottom bar or rail).

## Honest limits

WakeNudge cannot override the operating system. A phone that is powered off, in a state where the OS kills the app
and refuses exact alarms, or a vendor skin that aggressively restricts background work can still stop an alarm.
The Alarm health screen tells you when something on your phone puts that at risk. See
[docs/TESTING.md](docs/TESTING.md) for what has been verified on a real device and what has not.

## Build from source

Requirements: JDK 21 and the Android SDK (platform 37), or an Android Studio release that supports Android Gradle Plugin 9.4.

```bash
git clone <repository-url> WakeNudge
cd WakeNudge
./gradlew :app:assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest        # JVM tests
./gradlew :app:lintDebug                # lint (must be clean of errors)
./gradlew :app:assembleRelease          # minified + resource-shrunk, unsigned unless you configure signing
```

The debug build installs as `com.riyadm.wakenudge.debug` ("WakeNudge Debug") next to a release install.

### Release signing

Create `keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=/path/to/wakenudge.jks
storePassword=…
keyAlias=…
keyPassword=…
```

or set `WAKENUDGE_KEYSTORE_PATH`, `WAKENUDGE_KEYSTORE_PASSWORD`, `WAKENUDGE_KEY_ALIAS`, `WAKENUDGE_KEY_PASSWORD`
(this is what the [release workflow](.github/workflows/release.yml) does).

### Instrumented tests

> **If several devices are connected, do not run `./gradlew connectedDebugAndroidTest`.** It installs on and runs
> against every attached device. Target exactly one:
>
> ```bash
> adb -s <SERIAL> install -r -t app/build/outputs/apk/debug/app-debug.apk
> adb -s <SERIAL> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
> adb -s <SERIAL> shell am instrument -w -r com.riyadm.wakenudge.debug.test/com.riyadm.wakenudge.HiltTestRunner
> ```

The tests ring real alarms and use real audio. Use a test device, not the phone you sleep with.

## Project layout

```
app/src/main/java/com/riyadm/wakenudge/
  domain/    models, scheduling policies (pure Kotlin), use cases
  data/      Room, DataStore, repositories, backup
  engine/    scheduler, ring controller/service, audio, wake-check, timers, recovery
  missions/  the ten mission types and their host
  health/    Alarm health checks and diagnostics export
  widget/    Glance widgets          tile/  Quick Settings tile
  ui/        Compose screens, theme, components
```

More in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Documentation

| | |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How alarms are scheduled, rung and recovered |
| [docs/TESTING.md](docs/TESTING.md) | Test strategy, what was verified on a device, what was not |
| [docs/PERMISSIONS.md](docs/PERMISSIONS.md) | Every permission, why it exists and when it is asked for |
| [PRIVACY.md](PRIVACY.md) | Privacy policy |
| [docs/PLAY_STORE.md](docs/PLAY_STORE.md) | Play Store listing, declarations and release checklist |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute |
| [CHANGELOG.md](CHANGELOG.md) | Release notes |

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
