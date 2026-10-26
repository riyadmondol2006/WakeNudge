# Contributing

Thanks for helping make WakeNudge more reliable. Bug reports about alarms that did not ring are the most valuable
contributions: please use the [bug report form](https://github.com/riyadmondol2006/WakeNudge/issues/new/choose) and
include the phone model, Android version, vendor skin and the **Alarm Health** report (Alarm Health, then Copy or Share).

Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Ground rules

1. **Stay offline.** No analytics, no remote config, no network-capable dependency, no new network permission. The app
   holds only `INTERNET`, for the optional update check. The build enforces this; do not work around it.
2. **A mission must never trap the user.** Anything that can be impossible on some phone needs a way out, and the
   Emergency stop must work wherever the alarm has it switched on (it is on by default; a strict alarm may turn it off).
3. **Restore what you change.** Anything modifying system state (alarm volume, brightness) must put it back.
4. **No placeholders.** No TODO-shaped UI, no mock buttons, no dead settings.

## Gradle tasks

There is one app and the usual `debug` and `release` build types.

```bash
./gradlew :app:assembleDebug                  # debug APK
./gradlew :app:assembleRelease                # minified release APK (signed only if signing is configured)
./gradlew :app:testDebugUnitTest              # JVM unit and fuzz tests
./gradlew :app:lintDebug                      # lint
./gradlew :app:verifyOfflineManifestDebug     # fails if the merged manifest has a network permission other than INTERNET
```

## Before a pull request

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyOfflineManifestDebug
```

must pass. The [pull request template](.github/PULL_REQUEST_TEMPLATE.md) lists the rest.

For anything touching `engine/`, also run the instrumented tests on **one** device, and mention which device and Android
version. **Do not run `./gradlew connectedDebugAndroidTest` with several devices attached**: it installs on and runs
against every one of them. Use `adb -s <SERIAL>` as shown in the README's "Instrumented tests" section. These tests ring
real alarms with real audio, so use a test device, not the phone you sleep with.

## Style and tests

- Match the surrounding code: naming, comment density, idiom. Comments explain *why*, not *what*.
- Put pure logic in `domain/` or a mission's core so it can be tested on the JVM.
- A bug fix needs a test that fails without it. Alarm bugs are time-dependent; use the `TimeSource` abstraction, never
  `System.currentTimeMillis()`, in scheduling code.
- Strings go in `res/values/strings*.xml`, with plurals where a number is shown.
- Run the app with large fonts (up to 200%), RTL and TalkBack at least once for UI changes.
- Do not claim something was tested on a real device unless it was. Add anything new that you could not verify to the
  "Not verified" table in [docs/TESTING.md](docs/TESTING.md).

## Commit messages

Short imperative subject, then a paragraph on *why* if it is not obvious.

## Security

If you find a way to make an alarm silently fail, or to read the user's data from another app, please use
[Report a vulnerability](https://github.com/riyadmondol2006/WakeNudge/security/policy) rather than a public issue. See
[SECURITY.md](SECURITY.md).

By contributing you agree that your contribution is licensed under the Apache License 2.0.
