# Contributing

Thanks for helping make WakeNudge more reliable. Bug reports about alarms that did not ring are the most valuable
contributions: please include the phone model, Android version, vendor skin and the **Alarm health** report
(Alarm health → Copy or Share).

## Ground rules

1. **Stay offline.** No `INTERNET` permission, no analytics, no remote config, no network-capable dependency. The build
   enforces this; do not work around it.
2. **A mission must never trap the user.** Anything that can be impossible on some phone needs a way out, and the
   Emergency stop must always work.
3. **Restore what you change.** Anything modifying system state (alarm volume, brightness) must put it back.
4. **No placeholders.** No TODO-shaped UI, no mock buttons, no dead settings.

## Workflow

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyOfflineManifestDebug
```

must pass before a pull request. For anything touching `engine/`, also run the instrumented tests on **one** device
(see the warning in the README about `connectedDebugAndroidTest`), and mention which device and Android version.

- Match the surrounding code: naming, comment density, idiom. Comments explain *why*, not *what*.
- Put pure logic in `domain/` or a mission's core so it can be tested on the JVM.
- A bug fix needs a test that fails without it. Alarm bugs are time-dependent; use the `TimeSource` abstraction, never
  `System.currentTimeMillis()`, in scheduling code.
- Strings go in `res/values/strings*.xml`, with plurals where a number is shown.
- Run the app with large fonts (up to 200%), RTL and TalkBack at least once for UI changes.

## Commit messages

Short imperative subject, then a paragraph on *why* if it is not obvious.

## Security

If you find a way to make an alarm silently fail, or to read the user's data from another app, please open a private
report rather than a public issue first.

By contributing you agree that your contribution is licensed under the Apache License 2.0.
