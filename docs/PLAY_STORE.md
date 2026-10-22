# Google Play listing and release guide

Everything needed to publish WakeNudge on Google Play without surprises. It records the audit of the app against Play's
requirements (checked on **21 October 2026**), the store text, and the exact answers to paste into Play Console. Where a
Play policy page was read, the claim is tied to it; where wording may change, it says **verify in Play Console**.

Play Console form names and layout change over time. Treat the field names below as a guide and the answers as the
substance.

## 1. What goes to Play

| | |
|---|---|
| Artifact | Android App Bundle from the `play` flavour: `./gradlew :app:bundlePlayRelease` |
| Package name | `com.riyadm.wakenudge` (the GitHub APK uses the same one) |
| Network | None. No `INTERNET`, no `ACCESS_NETWORK_STATE`, no update or network code. Enforced by `verifyOfflineManifestPlayRelease` and `PlayReadinessTest` |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 37 / 37 |
| ABIs | arm64-v8a, armeabi-v7a, x86, x86_64 (only a few tiny AndroidX libraries ship native code) |
| Size | About 9.7 MB bundle; the universal APK is about 5 MB, so a device download is no larger |
| Signing | Upload with the CI upload key and enrol in **Play App Signing** (required for new apps). Google then re-signs what users download, so a Play install and a GitHub APK have different signatures and cannot update each other. Uninstall one before installing the other |
| Versioning | CI sets `versionCode` to the commit count and `versionName` to `1.0.<count>` |
| Deobfuscation | R8 `mapping.txt` is embedded in the bundle (`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`), so Play Console deobfuscates crashes and ANRs automatically. The file is about 77 MB uncompressed, well below Play's limits |

The Play listing text must not advertise the GitHub updater, and it does not.

## 2. Audit results (21 October 2026)

| # | Item | Verdict | Evidence |
|---|---|---|---|
| 1 | Target API level | Pass | Play requires new apps and updates to target Android 16 (API 36) or higher from 31 August 2026 (extension to 1 November 2026 on request), per <https://developer.android.com/google/play/requirements/target-sdk> (page last updated 16 Oct 2026). WakeNudge targets API 37 |
| 2 | 16 KB memory pages | Pass | Play requires 16 KB support for apps targeting Android 15+ from 1 February 2027 (<https://developer.android.com/guide/practices/page-sizes>). The play release APK passes `zipalign -c -P 16 -v 4`. All 8 native libraries of the 64-bit ABIs (`libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`, `libimage_processing_util_jni.so`, `libsurface_util_jni.so` in arm64-v8a and x86_64) have ELF `LOAD` alignment `0x4000` (`llvm-readelf -lW`). `android:extractNativeLibs="false"`. `BundleConfig.pb` is written by bundletool 1.18.3 with `uncompressed_native_libraries` enabled and alignment value 2, which should be the 16 KB page-alignment setting; it could not be confirmed with `bundletool dump config` (not installed here), so check it in Play Console's bundle explorer |
| 3 | 64-bit | Pass | arm64-v8a and x86_64 libraries present |
| 4 | Debuggable / test-only | Pass | Release manifest has neither `debuggable` nor `testOnly` (`aapt2 dump xmltree`) |
| 5 | R8 / shrinking | Pass | `isMinifyEnabled` and `isShrinkResources` on. The release build succeeds with no R8 missing-class errors. Generated `$$serializer` classes, Room `WakeNudgeDatabase_Impl`, Hilt entry points, Glance and CameraX consumer rules are present in `configuration.txt`/`mapping.txt`. A release build signed with a throwaway key was installed on a Pixel 6 Pro (Android 17), went through onboarding, saved an alarm, and rang a test alarm through the real path (foreground service type `mediaPlayback`, `types=0x00000002`) and was dismissed; no crash in the crash log buffer |
| 6 | Advertising ID | Pass | `com.google.android.gms.permission.AD_ID` is absent from both merged manifests, so the Advertising ID declaration is **No** |
| 7 | Network permissions | Pass | `play` merged manifest: no `INTERNET`, `ACCESS_NETWORK_STATE` (removed with `tools:node="remove"`, it is pulled in by WorkManager through Glance) or `ACCESS_WIFI_STATE`. `github`: only `INTERNET` is added. `usesCleartextTraffic="false"` is now explicit. No network security config is needed: the system default already rejects user-installed CAs and cleartext for this target, and pinning `api.github.com` would only add breakage when GitHub rotates certificates |
| 8 | Exported components | Pass, see [PERMISSIONS.md](PERMISSIONS.md#exported-components-and-who-can-call-them) | Every component states `android:exported`; 7 are exported, each justified; `PendingIntent`s are all `FLAG_IMMUTABLE`; one runtime receiver (NFC state) registered `RECEIVER_NOT_EXPORTED`; no providers or custom permissions of our own; four library components are exported behind system-only permissions |
| 9 | Large screens | Pass | No `screenOrientation`, no `resizeableActivity="false"`, no aspect-ratio limits. Android 17 stops honouring such restrictions on screens of 600 dp and up anyway. `configChanges` cover size and density changes (foldables). No tablet screenshots exist yet, see section 4 |
| 10 | `showWhenLocked` / `turnScreenOn` | Pass | Only on `RingActivity` and `WakeCheckActivity` (not exported), which is what alarm clocks are for. Code also sets the window flags on API 26 |
| 11 | Hardware features | Pass | Camera, autofocus, NFC, accelerometer, step counter and touchscreen are `required="false"`, so the app is installable on phones without them. `aapt2 dump badging` shows no other implied feature |
| 12 | Backup | Decision recorded | See section 7 |
| 13 | Predictive back | Pass | `android:enableOnBackInvokedCallback="true"` added (the default at this target, now explicit). All back handling uses `OnBackPressedDispatcher` / `BackHandler` |
| 14 | Lint (release config, both flavours) | Pass | `:app:lintPlayRelease` and `:app:lintGithubRelease`: 0 errors, 30 warnings, 2 hints, identical for both. None are policy checks: no `ExactAlarm`, `ScheduleExactAlarm`, `FullScreenIntentPolicy`, `QueryAllPackages`, `UnspecifiedRegisterReceiverFlag`, `OldTargetApi` or `ForegroundServicePermission` finding. The warnings are `InlinedApi` (3, constants that are inlined and harmless), `PluralsCandidate` (12), `UseKtx` (9), `UnusedResources` (3 strings in `strings_screens.xml`), `PrivateResource` (2), `ModifierParameter` (1), `AutoboxingStateCreation` (2 hints) |
| 15 | Privacy policy in the app | **Needs a code change** | Play requires a privacy policy link in the Console and also "a privacy policy link or text within the app itself" (<https://support.google.com/googleplay/android-developer/answer/10144311>). The About screen has privacy text but no link. Add a row that opens the policy URL (`AboutScreen.kt`, section "Privacy"). This repo's `ui/**` is owned by another change set, so it is listed in the hand-off |
| 16 | Health apps declaration | Judgment call | See section 5 |

## 3. Store listing text

The text lives in [`fastlane/metadata/android/en-US/`](../fastlane/metadata/android/en-US) so it can be uploaded with
`fastlane supply` or pasted into Play Console. Limits are enforced by `PlayReadinessTest` (run with
`./gradlew :app:testPlayDebugUnitTest --tests '*PlayReadinessTest'`) and were also measured directly.

| Field | Limit | Now | File |
|---|---|---|---|
| App name | 30 | 22 | `title.txt`: **WakeNudge: Alarm Clock** |
| Short description | 80 | 72 | `short_description.txt` |
| Full description | 4000 | 3137 | `full_description.txt` |
| Release notes ("What's new") | 500 | 370 (`1.txt`), 33 (`default.txt`) | `changelogs/` |

Wording rules applied (from Play's metadata policy, <https://support.google.com/googleplay/android-developer/answer/9866151>):
no emojis or symbols in the short description, no repeated punctuation or all-caps emphasis, no ranking, award or
price-promotion claims, no calls to action such as "download now", no third-party trademarks. The test fails if
a list of well-known competitor product names, an emoji, or the words GitHub or "check for updates" appear in the
listing text. Keywords appear in natural sentences: alarm clock, wake-up missions, heavy sleeper, QR code / NFC /
math alarm, loud alarm, offline, no ads, open source.

Facts in the text were checked against the code: 10 mission types, 12 bundled tones graded from gentle to loud, hearing
cap on headphones, offline-voice preference for announcements. "No internet permission" is true of the Play build only.

### Release notes per version code

Play reads release notes per locale from `changelogs/<versionCode>.txt`. CI sets `versionCode` to the commit count, so
the number is only known at build time, and `1.txt` will never match a real upload. Proposal for CI:

1. Keep `changelogs/default.txt` as the fallback (it exists: "Bug fixes and small improvements.").
2. At release time, write the real note for that build into `changelogs/<versionCode>.txt` (for example from the
   release's commit subjects, trimmed to 500 characters), and if none was written, copy `default.txt` to that name.
3. For the very first upload, use the text of `changelogs/1.txt` ("First release...") as the note in the Console.

### Graphics (measured 21 October 2026)

| Asset | Requirement | Now |
|---|---|---|
| Icon | 512 x 512, 32-bit PNG with alpha, at most 1024 KB | `images/icon.png` 512 x 512 RGBA, 53 KB |
| Feature graphic | 1024 x 500, JPEG or 24-bit PNG, no alpha | `images/featureGraphic.png` 1024 x 500 RGB |
| Phone screenshots | 2 to 8, JPEG or 24-bit PNG, each side 320 to 3840 px, long side at most twice the short side | `images/phoneScreenshots/` 8 files, all 1080 x 2160 RGB (exactly 2:1, which is allowed; it is also at the 8-file maximum) |
| 7-inch and 10-inch tablet screenshots | Optional to publish. Play's page describes a minimum of 4 for large-screen eligibility, 1080 to 7680 px, 16:9 or 9:16 | **None exist.** The app uses adaptive navigation and handles tablets, but nothing has been captured. Capture 4 or more from a tablet or emulator if large-screen visibility matters. Verify the exact large-screen rules in Play Console |
| Promo video | Optional | None |

Screenshots will be refreshed later; keep the file names so the numbering stays stable.

### Basics to enter

- Category: **Tools** (alternative: Productivity). Tags (pick from Play's list): alarm clock, clock, productivity, sleep.
- Contact details: a public email address is required in Console, and it is the user's to provide. Website is optional.
  The privacy contact in [PRIVACY.md](../PRIVACY.md) is the GitHub issue tracker.
- Privacy policy URL: **`https://github.com/riyadmondol2006/WakeNudge/blob/main/PRIVACY.md`**. It answered HTTP 200 on
  21 October 2026 for the version then on `main`, so keep `PRIVACY.md` on `main` current before the first upload. Play
  requires a publicly reachable, non-PDF page. If the separate project website goes live with a `/privacy` page, the
  URL can be switched to it later; do not enter it until it is verified to load.
- Price: free. No in-app purchases. No ads.

## 4. App content forms (Play Console, Policy > App content)

| Form | Answer |
|---|---|
| Privacy policy | URL above |
| Ads | **No**, the app contains no ads |
| App access | All functionality is available without any login, subscription, location, or other special access. (The camera, NFC, steps and calendar missions need hardware or a permission but nothing that blocks a reviewer.) |
| Content rating (IARC) | Category: **Utility, Productivity, Communication or Other**. Violence: no. Sexual content or nudity: no. Profanity: no. Controlled substances: no. Gambling: no. Fear or horror: no. Users can interact or exchange content: **no**. Shares user location: **no**. Digital purchases: **no**. Unrestricted web access: **no**. Expected result: lowest rating (Everyone / 3+ in most regions). Answer the live questionnaire honestly; it can change |
| Target audience | Age groups **13 to 15, 16 to 17, 18 and over**. Not designed for children and does not appeal to them, so Families policies do not apply. Do not tick any group under 13 |
| Data safety | Section 6 |
| Government app | No |
| Financial features | None (no payments, loans, crypto, banking) |
| News app | No |
| Health apps | Section 5 |
| COVID-19 | Not applicable |
| Advertising ID | **No**, the app does not use the advertising ID (`AD_ID` is not in the manifest) |
| Account deletion | Not shown / not applicable: the app has no accounts |
| Photo and video permissions | Not applicable: no `READ_MEDIA_*` permissions |
| Exact alarm permission | Declaration text below |
| Full screen intent | Declaration text below |
| Foreground service permissions | Declaration text below |

### Health apps declaration

The Health apps form is required for every app, including those with no health features
(<https://support.google.com/googleplay/android-developer/answer/14738291>). Play's categories include "Activity and
fitness" (apps that monitor and record physical activity, exercise routines and workouts) and "Sleep management"
(apps dedicated to improving and tracking sleep). WakeNudge does not record, store or display any step, exercise or sleep
data: steps and squats are counted live only to dismiss an alarm and are discarded, the history stores alarm outcomes,
and the Sleep planner is a bedtime calculator based on sleep-cycle length. There is no Health Connect integration.

Recommended answer: **"My app doesn't provide any health features."** This is a judgment call because Play's text does
not mention alarm apps. If Play review disagrees, change the declaration to Activity and fitness (and Sleep management)
with no Health Connect data types; no app change is needed.

## 5. Permission declarations: text to paste

Approval is a Google decision and cannot be tested locally. These are accurate descriptions of what the code does.

### Exact alarm permission (`USE_EXACT_ALARM`)

Policy basis: Play limits `USE_EXACT_ALARM` to apps whose core functionality needs exact alarms, with alarm and timer
apps and calendar apps as the qualifying categories, and requires a Console declaration
(<https://support.google.com/googleplay/android-developer/answer/9888170>). Category to choose: **Alarm clock or timer**.

> WakeNudge is an alarm clock. Its core, user-facing function is to ring at the exact time the user sets, including
> when the phone is locked, silent or in Doze. We use USE_EXACT_ALARM so that user-created alarms, snoozes, timers and
> the user-enabled follow-ups of an alarm (a backup ring, the "Still awake?" check and bedtime reminders) fire at the
> exact minute and cannot be broken by a special-access toggle. User alarms are registered with
> AlarmManager.setAlarmClock(), so they appear in the system's next-alarm UI. The permission is not used for syncing,
> polling, ads, analytics or any background work that is not a user-set alarm or timer. On Android 12L and lower the app
> declares SCHEDULE_EXACT_ALARM with maxSdkVersion 32 for the same purpose.

If the declaration were ever rejected, the fallback is to drop `USE_EXACT_ALARM` and rely on `SCHEDULE_EXACT_ALARM`
granted by the user; the Alarm health screen already detects and explains a missing exact-alarm grant. Note that
Android 17 background-audio hardening expects the exact-alarm permission for alarm audio from a background app.

### Full-screen intent (`USE_FULL_SCREEN_INTENT`)

Policy basis: for apps targeting Android 14+, Play pre-grants the permission only if core functionality is setting an
alarm or receiving calls, and asks for a declaration (<https://support.google.com/googleplay/android-developer/answer/9888170>).
Category to choose: **Alarm**.

> WakeNudge is an alarm clock. When an alarm or timer that the user set goes off, the app shows its alarm screen over
> the lock screen with a full-screen intent so the user can snooze, dismiss, or complete the wake-up mission they chose.
> The notification uses the alarm category and high importance. The full-screen intent is used only for a ringing alarm,
> a finished timer, and the "Still awake?" prompt the user turned on. It is never used for ads, promotions or any
> content that is not a user-set alarm or timer. If the permission is switched off, the Alarm health screen explains it
> and links to the system setting.

### Foreground service: `mediaPlayback` (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`)

Policy basis: apps targeting Android 14+ declare each foreground service type on the App content page with a
description, the user impact if the system interrupts it, and a link to a demo video that shows the user starting the
feature; `mediaPlayback` is on the list (<https://support.google.com/googleplay/android-developer/answer/13392821>).
Use case: **Media playback** (background audio).

- Description:
  > While a user-set alarm or timer is ringing, WakeNudge runs RingService as a foreground service of type
  > mediaPlayback. It plays the alarm sound the user chose (with optional vibration and spoken announcement) until the
  > user dismisses or snoozes, or after the ring timeout (10 minutes by default). It starts only when an alarm or timer
  > the user created fires, or when the user taps "Test alarm" in Alarm health, and it stops when the alarm ends.
- User impact if deferred or interrupted:
  > The alarm would be silent or would stop, and the user could oversleep. Reliable audio while the screen is off is the
  > purpose of the app.
- Demo video (about 30 to 60 seconds; use the phone's screen recorder, upload to YouTube as **unlisted**, paste the
  link). The video must show the user starting the feature, so it should include setting the alarm:
  1. Open WakeNudge and tap **+** to create an alarm 1 to 2 minutes from now (or open **Alarm health** and tap
     **Ring in 10 seconds**).
  2. Show the alarm in the list and lock the phone with the power button.
  3. Wait. The screen turns on over the lock screen, the sound plays, and the notification with **Snooze** and
     **Dismiss** is visible in the shade.
  4. Tap **Dismiss** (or complete the mission). The sound stops and the notification disappears.

### Other permissions (no Console form, keep these answers ready for a review message)

- `RECEIVE_BOOT_COMPLETED`: re-arm alarms, snoozes and timers after a restart, app update, clock or time-zone change.
- `CAMERA`: optional QR/barcode and photo missions. Requested when the user sets up or runs one. Frames are analysed in
  memory and never saved or sent; only a small scene fingerprint and a tiny reference thumbnail are stored locally.
- `ACTIVITY_RECOGNITION`: optional steps mission, requested when used. Declining falls back to the motion sensor.
- `READ_CALENDAR`: optional. Reads only the start and end of all-day events in calendars the user picks, to skip
  repeating alarms on holidays. No titles or descriptions are read. Nothing leaves the phone.
- `NFC`: optional NFC mission (`required="false"`).
- `POST_NOTIFICATIONS`: alarm, timer and bedtime notifications.
- `VIBRATE`, `WAKE_LOCK`, `MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE`: ringing and vibration. `MODIFY_AUDIO_SETTINGS` is
  used for the loudness-enhancer effect of Extra Loud mode and to raise and then restore the alarm volume.

## 6. Data safety form

Play defines **collect** as "transmitting data from your app off a user's device"
(<https://support.google.com/googleplay/android-developer/answer/10787469>). Data that is only processed locally and is not sent off the
device does not have to be disclosed. The Play build has no internet permission, so it cannot transmit anything.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not shown or not applicable: nothing is collected or transmitted |
| Do you provide a way for users to request that their data be deleted? | Not shown or not applicable. Data is local and removed by uninstalling or clearing storage |
| Privacy policy | Required even when nothing is collected. Enter the URL from section 3 |
| Independent security review / Play Families | No |

Data the app keeps locally (and does not collect): alarms, settings, history, the diagnostics log, QR text, NFC tag id, a
photo fingerprint and a tiny reference thumbnail (never the full photo), references to chosen sound files. The user
exports them only by choice through the file picker.

The GitHub build is not distributed through Play, so this form describes the Play build only. The GitHub build's
update check (at most once an hour) is described in [PRIVACY.md](../PRIVACY.md).

## 7. Backup and data transfer (decision)

`android:allowBackup="false"`, plus explicit exclude rules for every domain (including the `device_*` domains where the
alarm data lives) in both `data_extraction_rules.xml` (Android 12+, cloud backup and device-to-device transfer) and
`backup_rules.xml` (Android 6 to 11). Reasons:

- The app's data is in device-protected storage. A raw copy on a new phone would list alarms that `AlarmManager` was
  never told about; nothing re-arms them until the app is opened.
- The "onboarding done" flag would travel without the notification, full-screen and camera permissions, which then
  must be granted again.
- The photo mission's scene fingerprint belongs to the old phone's camera, and chosen sound files are stored as URIs
  that do not resolve on another phone.
- Google documents that `allowBackup="false"` alone does not reliably stop device-to-device transfer on every
  manufacturer's phones (<https://developer.android.com/identity/data/autobackup>), so both XML files exclude everything explicitly.
- The supported way to move is the in-app backup file (**Settings > Backup and restore**), which is validated and re-arms
  alarms on restore. The store text says so.

## 8. Pre-launch report and review risk

Google's pre-launch crawler installs the app on virtual and real devices, usually without camera, NFC or step sensors,
and taps through the screens. Findings from reading the code paths and from a smoke run on a Pixel 6 Pro (Android 17):

- No crash or ANR found on the paths tested: first launch, onboarding, creating and saving an alarm, ringing a test alarm
  through the foreground service, dismissing it from the notification, and opening Alarm health.
- Missing hardware is handled: every mission checks `MissionInfo.isSupported` in the editor and again when ringing
  (`MissionGate`), and shows an explanation with a **Continue** button instead of failing. `CameraAnalysisView` catches
  a missing camera and calls `onUnavailable`. Sensor effects do nothing when the sensor is absent.
- Denied permissions are handled: `PermissionGate` offers **Allow** or **Open app settings**; the full-screen and battery
  shortcuts are wrapped in `runCatching`.
- There is no mission trap: defaults are no missions and a plain tap to dismiss, the ring times out after 10 minutes, and
  **Emergency stop** (press and hold) bypasses missions (on by default; a user can switch it off for a strict alarm, in which case a mission this phone cannot perform still offers Continue and the ring timeout still ends the alarm). Back is swallowed only while an alarm rings.
- Things worth knowing, none blocking: the system logs `AppOps: attributionTag  not declared in manifest` lines while
  the app runs (platform noise, no effect); the Android 17 logs show background-audio warnings that do not mute alarm
  audio (see [PERMISSIONS.md](PERMISSIONS.md)).

Hand-off items for code owners (not changed here): the About screen needs a privacy policy link (item 15); intent
handling in `MainViewModel.handleIntent` reads extras without a guard, so a hostile app that sends a malformed
extras Bundle to the exported launcher activity could crash the process, and wrapping the body in `runCatching`
would fix it; `SystemEventsReceiver` may be set to `exported="false"` (system broadcasts still reach it) to remove the
only spoofable action (`QUICKBOOT_POWERON`); and the GitHub build's strings that say "No internet permission" (onboarding
and About) become untrue there, so they should be flavour-specific.

## 9. Release checklist

1. `./gradlew :app:testPlayDebugUnitTest :app:lintPlayRelease :app:verifyOfflineManifestPlayRelease` is green
   (`PlayReadinessTest` is part of the unit tests).
2. Instrumented tests pass on one real device (see the README warning about multiple devices).
3. CI builds the signed `play` bundle (`bundlePlayRelease`) from `main`; versioning is automatic. Update `CHANGELOG.md`
   and the release note for that version code (section 3).
4. Install the signed release build on a real phone and ring a test alarm over the lock screen **before** uploading.
5. Upload the `.aab`. `mapping.txt` is embedded; no separate upload is needed.
6. First release only, in Play Console, in this order: create the app; complete **App content** (section 4) and
   the declarations (section 5); fill the store listing (section 3); set up Play App Signing and upload the bundle to a
   testing track.
7. Start with an internal or closed test and check Alarm health on a few manufacturers' phones (Samsung, Xiaomi,
   OnePlus), because vendor battery managers are the main remaining risk.

### Things only the account owner can do or verify

- **Personal developer accounts created after 13 November 2023** must run a closed test with at least 12 testers opted
  in for 14 consecutive days before production access (<https://support.google.com/googleplay/android-developer/answer/14151465>).
  Check which kind of account this is; start the test early if it applies.
- **Android developer verification** is being rolled out (first enforcement 30 September 2026 in Brazil, Indonesia,
  Singapore and Thailand; global in 2027, per <https://developer.android.com/developer-verification>). Google Play
  registers most Play apps automatically, but the GitHub APK is distributed outside Play under the same package name.
  Verify in Play Console that `com.riyadm.wakenudge` is registered and that the GitHub APK's signing key is covered.
- Enter a public developer contact email, and decide on the developer name shown on the listing.
- Verify the form names, categories and wording of the declarations against the live Console before submitting.
