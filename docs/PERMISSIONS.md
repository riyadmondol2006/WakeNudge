# Permissions

WakeNudge asks for as little as an alarm clock can work with. Nothing here is used for anything but the stated purpose,
and **the app declares no network permission**: `INTERNET`, `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` are
rejected at build time by the `verifyOfflineManifest*` Gradle tasks (part of `check` and CI).

## Always granted at install (normal permissions)

| Permission | Why |
|---|---|
| `USE_EXACT_ALARM` | An alarm clock is a core "alarm clock" app use. Lets `AlarmManager.setAlarmClock` fire on time and cannot be revoked by the user, so a restrictive setting cannot silently break a wake-up alarm |
| `SCHEDULE_EXACT_ALARM` (Android 12L and lower only, `maxSdkVersion=32`) | Same job on releases before `USE_EXACT_ALARM` existed |
| `RECEIVE_BOOT_COMPLETED` | Re-arm alarms, snoozes, timers and follow-ups after a restart or app update |
| `WAKE_LOCK` | Keep the CPU awake while an alarm is ringing |
| `VIBRATE` | Vibration patterns |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | The ring service plays the alarm sound as a foreground media-playback service |
| `MODIFY_AUDIO_SETTINGS` | Raise the alarm stream to the configured level while ringing, and put it back afterwards |
| `NFC` | The NFC mission (the feature is optional: `required=false`) |

## Special access

| Permission | Why | How it is requested |
|---|---|---|
| `USE_FULL_SCREEN_INTENT` | Show the ring screen over the lock screen | Shown in onboarding and the Alarm health screen with a shortcut to the system setting if it is ever turned off |

## Runtime permissions (asked only when needed)

| Permission | Feature | When |
|---|---|---|
| `POST_NOTIFICATIONS` | The ring notification with Snooze and Dismiss, timer and bedtime notifications | Onboarding, final step |
| `CAMERA` | QR/barcode mission, photo mission | First time you set up or run one of those missions |
| `ACTIVITY_RECOGNITION` | Steps mission (hardware step counter) | First time you set up or run the steps mission |
| `READ_CALENDAR` | Optional: treat days marked on your calendar as exceptions | When you switch on calendar exceptions in Settings |

If you decline a permission, the feature that needs it explains what is missing, and a mission that can no longer be
completed offers a way out so it can never block you from stopping an alarm (see "Emergency stop").

## Not used

No location, contacts, microphone, storage/media, phone, SMS, accounts, Bluetooth or internet permissions.
Custom alarm sounds are picked with Android's document picker, which grants access to the single file you choose.

## Related behaviour worth knowing

- Android 17 added hardening around background audio. On a test Pixel 6 Pro running Android 17 the system logs warn-only
  lines about background playback, but alarm playback was verified as `USAGE_ALARM` with `mutedState:none` (the
  `USE_EXACT_ALARM` alarm-clock exemption applies). Platform behaviour could tighten in a later update; Alarm health
  is the place that would surface it.
- Battery optimisation exemption is **not** requested automatically. It is offered as an optional shortcut on the Alarm
  health screen.
