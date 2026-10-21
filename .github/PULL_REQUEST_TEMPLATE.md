## What and why

<!-- What does this change, and why? Link the issue it fixes (Fixes #123). -->

## Checklist

- [ ] `./gradlew :app:testPlayDebugUnitTest :app:lintPlayDebug :app:verifyOfflineManifestPlayDebug :app:verifyOfflineManifestGithubDebug` passes locally
- [ ] A bug fix comes with a test that fails without it (scheduling code uses `TimeSource`, never `System.currentTimeMillis()`)
- [ ] **Offline rule:** no new permission, network-capable dependency, analytics or remote config. The Play flavour still has no network permission
- [ ] **A mission must never trap the user:** anything that can be impossible on some phone has a way out, and the Emergency stop still works
- [ ] Anything that changes system state (alarm volume, brightness, ...) puts it back
- [ ] New strings are in `res/values/strings*.xml` (with plurals where a number is shown) and the UI was tried with large fonts, RTL and TalkBack
- [ ] No placeholders: no TODO-shaped UI, mock buttons or dead settings
- [ ] Docs and `CHANGELOG.md` updated if behaviour changed

## Instrumented tests (only if `engine/` or the ring flow changed)

- [ ] I ran them on **exactly one** device, using `adb -s <SERIAL>` (not `connectedPlayDebugAndroidTest`, which runs on every attached device)

Device and Android version:

<!-- e.g. Pixel 6 Pro, Android 17. They ring real alarms with real audio: use a test device. -->

By submitting this pull request I agree that my contribution is licensed under the Apache License 2.0.
