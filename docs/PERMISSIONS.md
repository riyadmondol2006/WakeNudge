# Permissions

WakeNudge asks for as little as an alarm clock can work with. Nothing here is used for anything but the stated purpose.

There are two builds of the same app (product flavour dimension `distribution`):

| Build | Where it is published | Network |
|---|---|---|
| `play` | Google Play (Android App Bundle) | **None.** The merged manifest has no `INTERNET`, `ACCESS_NETWORK_STATE` or `ACCESS_WIFI_STATE`, and the code contains no update or network code. |
| `github` | APK on GitHub Releases | Exactly one extra permission, `INTERNET`, used once a day at most to ask `api.github.com` whether a newer release exists (on by default, switch it off in About, under Updates). It sends nothing about you or your alarms and only opens the release page in your browser. No in-app installing, so no `REQUEST_INSTALL_PACKAGES`. |

Both rules are enforced on the **merged** manifest of each variant by the `verifyOfflineManifest*` Gradle tasks (part
of `check` and CI): `play` may declare none of the three network permissions, `github` may declare only `INTERNET`.
The shared source manifest (`app/src/main/AndroidManifest.xml`) never declares any of them, and `PlayReadinessTest`
checks that too. The permissions below are identical in both builds unless stated.

## Always granted at install (normal permissions)

| Permission | Why |
|---|---|
| `USE_EXACT_ALARM` (Android 13+) | An alarm clock is the core use. Lets `AlarmManager.setAlarmClock` fire on time and cannot be revoked by the user, so a restrictive setting cannot silently break a wake-up alarm. Play Console asks for a declaration (text in [PLAY_STORE.md](PLAY_STORE.md)) |
| `SCHEDULE_EXACT_ALARM` (Android 12L and lower only, `maxSdkVersion=32`) | Same job on releases before `USE_EXACT_ALARM` existed. Android 12 and 12L grant it by default but the user can revoke it in Special app access, which Alarm health detects |
| `RECEIVE_BOOT_COMPLETED` | Re-arm alarms, snoozes, timers and follow-ups after a restart or app update |
| `WAKE_LOCK` | Keep the CPU awake while an alarm is ringing |
| `VIBRATE` | Vibration patterns |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | The ring service plays the alarm sound as a foreground media-playback service (`foregroundServiceType="mediaPlayback"`). Play Console asks for a declaration and a short demo video |
| `MODIFY_AUDIO_SETTINGS` | Raise the alarm stream to the configured level while ringing, and put it back afterwards; used by the loudness-enhancer effect of Extra Loud mode |
| `NFC` | The NFC mission (the feature is optional: `required=false`) |

## Special access

| Permission | Why | How it is requested |
|---|---|---|
| `USE_FULL_SCREEN_INTENT` | Show the ring screen over the lock screen. Declared in Play Console for apps targeting Android 14+; Google pre-grants it to alarm apps | Shown in onboarding and the Alarm health screen with a shortcut to the system setting if it is ever turned off |

## Runtime permissions (asked only when needed)

| Permission | Feature | When |
|---|---|---|
| `POST_NOTIFICATIONS` | The ring notification with Snooze and Dismiss, timer and bedtime notifications | Onboarding, final step |
| `CAMERA` | QR/barcode mission, photo mission | First time you set up or run one of those missions |
| `ACTIVITY_RECOGNITION` | Steps mission (hardware step counter) | First time you set up or run the steps mission. Declining is fine: the steps mission can count with the motion sensor instead |
| `READ_CALENDAR` | Optional: treat all-day events in calendars you pick (for example public holidays) as exception days. Only event start and end are read, never titles or descriptions | When you switch on calendar exceptions in Settings |

If you decline a permission, the feature that needs it explains what is missing, and a mission that can no longer be
completed offers a way out so it can never block you from stopping an alarm (see "Emergency stop").

## Hardware is never required

`uses-feature android:required="false"` is declared for `camera`, `camera.autofocus`, `nfc`,
`sensor.accelerometer`, `sensor.stepcounter` and `touchscreen`, so the app installs on every device that Play
can target. A mission whose hardware is missing shows an explanation and a Continue button instead of failing
(`MissionInfo.isSupported`, `MissionGate`).

## Not used

No location, contacts, microphone, storage/media, phone, SMS, accounts, Bluetooth, `QUERY_ALL_PACKAGES`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SYSTEM_ALERT_WINDOW`, `AD_ID` (no advertising ID), or
`REQUEST_INSTALL_PACKAGES`. Custom alarm sounds are picked with Android's document picker, which grants access to the
single file you choose. The only `<queries>` entry is the text-to-speech service (`android.intent.action.TTS_SERVICE`),
needed on Android 11+ to find the speech engine for spoken announcements.

## Exported components and who can call them

Only these components are exported; each was reviewed for a hostile or spoofed caller. `PlayReadinessTest` fails if the
list changes, so a new exported component forces a conscious review.

| Component | Why exported | Hostile-caller notes |
|---|---|---|
| `MainActivity` (launcher, `SET_ALARM`, `SHOW_ALARMS`, `SET_TIMER`, `SHOW_TIMERS`) | Launcher and the standard clock intents used by voice assistants and other apps | Extras are clamped (hour 0-23, minute 0-59, label 60 characters, timer 1 second to 24 h). `EXTRA_SKIP_UI` creates an alarm with the user's defaults without the editor, as the platform intends. The caller must be foreground to launch it, so the app visibly opens |
| `TilePreferencesAlias` | Android opens it on a long-press of the Quick Settings tile | Only navigates to the alarm list |
| `SystemEventsReceiver` | Boot, app update, clock and time-zone changes | All actions are system-protected broadcasts except the vendor action `QUICKBOOT_POWERON`, which any app can send. It only triggers an idempotent re-registration of alarms |
| Three widget receivers | App-widget updates | `APPWIDGET_UPDATE` is a protected broadcast, so only the system can send it |
| `NextAlarmTileService` | Quick Settings tile | Protected by `android.permission.BIND_QUICK_SETTINGS_TILE`, which only the system holds |

Everything else of the app's own (`RingActivity`, `WakeCheckActivity`, `RingService`, `AlarmReceiver`,
`ActionReceiver`) is not exported. All `PendingIntent`s are created with `FLAG_IMMUTABLE`. The app defines no content
provider and no custom permission of its own (AndroidX adds one signature-level permission for non-exported dynamic
receivers). Its single runtime receiver, which follows the NFC adapter switching on and off during the NFC mission, is
registered with `RECEIVER_NOT_EXPORTED`.

Libraries add four more exported components, each protected by a permission that only the system can hold, which is the
documented pattern: Glance's `GlanceRemoteViewsService` (`BIND_REMOTEVIEWS`), WorkManager's `SystemJobService`
(`BIND_JOB_SERVICE`), and `DiagnosticsReceiver` and `ProfileInstallReceiver` (`DUMP`). They are visible in the merged
manifest and are not ours to remove.

## Backup and device transfer

`allowBackup="false"` plus explicit exclude rules for every storage domain in both `data_extraction_rules.xml` (Android
12+, cloud backup **and** device-to-device transfer) and `backup_rules.xml` (Android 6-11). Alarms live in
device-protected storage and a raw copy would show alarms that Android has not been told to ring, would carry the
"onboarding done" flag without the permissions, and would carry a camera fingerprint that belongs to the old phone.
The in-app backup file (validated, then re-armed on restore) is the supported way to move to a new phone.

## Related behaviour worth knowing

- Android 17 added hardening around background audio: an app in the background must run a foreground service and also
  hold the exact-alarm permission while using `USAGE_ALARM` audio. WakeNudge does all three. On a test Pixel 6 Pro
  running Android 17 the system logs warn-only lines about background playback, but alarm playback was verified as
  `USAGE_ALARM` with `mutedState:none` (the `USE_EXACT_ALARM` alarm-clock exemption applies). Platform behaviour could
  tighten in a later update; Alarm health is the place that would surface it.
- Battery optimisation exemption is **not** requested automatically and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is not
  declared. A shortcut to the system battery settings page is offered on the Alarm health screen.
