# Google Play listing and release guide

The store text lives in [`fastlane/metadata/android/en-US/`](../fastlane/metadata/android/en-US) so it can be uploaded
with `fastlane supply` or pasted into Play Console. Limits checked: title 22/30, short description 72/80, full
description 3135/4000, release notes 370/500.

| File | Use |
|---|---|
| `title.txt` | **WakeNudge: Alarm Clock** |
| `short_description.txt` | Offline alarm clock with wake-up missions. No ads, accounts or tracking. |
| `full_description.txt` | Long description (keywords appear naturally: alarm clock, wake-up missions, heavy sleeper, QR code / NFC / math alarm, loud alarm, offline, no ads, open source) |
| `changelogs/1.txt` | What's new for version code 1 |
| `images/icon.png`, `images/featureGraphic.png` | 512×512 icon and 1024×500 feature graphic |
| `images/phoneScreenshots/` | Phone screenshots taken from the app |

## App category and tags

Category **Tools** (or **Productivity**). Suggested tags: Alarm clock, Clock, Productivity, Sleep. No ads, no in-app
purchases. Target audience 13+ (an alarm app is not designed for children).

## Data safety form

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not applicable, nothing is transmitted |
| Do you provide a way for users to request that their data is deleted? | Not applicable, nothing is collected. Data is local and removed on uninstall |

This is accurate because the app has no `INTERNET` permission and no SDK that transmits data. Privacy policy URL: host
[`PRIVACY.md`](../PRIVACY.md) at a public URL (for example the rendered file in the repository) and paste it in.

## Permission declarations Play Console will ask about

Approval is a Google decision and cannot be tested locally. These are the honest justifications.

**`USE_EXACT_ALARM`**: Core functionality of the app is an alarm clock. Users set alarms that must ring at an exact time
on a locked phone. The app uses `AlarmManager.setAlarmClock` for user-visible alarms only. (Android 12L and lower use
`SCHEDULE_EXACT_ALARM`, capped with `maxSdkVersion="32"`.)

**`USE_FULL_SCREEN_INTENT`**: The ringing alarm must appear over the lock screen so it can be snoozed or dismissed.
It is used only for a ringing alarm, a timer that finished and the "Still awake?" prompt.

**Foreground service type `mediaPlayback`** (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`): The ring service plays the alarm
sound for as long as the alarm rings. Demo video: set a 1-minute alarm, lock the phone, show it ringing and being
dismissed. Alarm Health → "Ring in 10 seconds" shows this in one take.

**`RECEIVE_BOOT_COMPLETED`**: Re-arm alarms after a restart.

**`CAMERA`, `ACTIVITY_RECOGNITION`, `READ_CALENDAR`, `NFC`**: Optional, user-initiated features (QR/photo missions, steps
mission, calendar exceptions, NFC mission). Requested at the moment of use. The camera feed is processed in memory and
not stored. Features degrade gracefully when permission or hardware is absent (`uses-feature required="false"`).

## Content rating

Questionnaire answers: no violence, no user-generated content shared with others, no location, no purchases, no
ads. Expected rating: Everyone.

## Release checklist

1. `./gradlew :app:testDebugUnitTest :app:lintRelease :app:verifyOfflineManifestRelease` is green.
2. Instrumented tests pass on one real device (see README warning about multiple devices).
3. Bump `versionCode` and `versionName` in `app/build.gradle.kts`; update `CHANGELOG.md` and
   `fastlane/.../changelogs/<versionCode>.txt`.
4. Provide the signing secrets (README → Release signing), tag `vX.Y.Z`, let the Release workflow build the signed `.aab`.
5. Install the signed release build on a real phone and ring a test alarm over the lock screen **before** uploading.
6. Upload the `.aab` and keep `mapping.txt` (attached to the workflow artifacts) for de-obfuscating crash traces.
7. Start with an internal or closed test and check Alarm health on a few manufacturers' phones (Samsung, Xiaomi,
   OnePlus), because vendor battery managers are the main remaining risk.
