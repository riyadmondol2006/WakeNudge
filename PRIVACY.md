# Privacy Policy

_Last updated: 21 October 2026_

**WakeNudge: Alarm Clock** is built so that your data never leaves your phone.

## The short version

- The app has **no internet permission**. It cannot send anything anywhere, and the build fails if that ever changes.
- There are **no accounts, no ads, no analytics, no crash reporting and no tracking** of any kind.
- No third-party SDKs that collect data are included. The only libraries are open-source Android and Kotlin
  libraries for the user interface, storage, camera and barcode decoding, all running on the device.

## What is stored, and where

Everything below is stored only on your phone, in the app's private storage:

| Data | Why |
|---|---|
| Your alarms, groups, timers, world clocks, exception dates and settings | So your alarms work |
| Alarm history (when each alarm rang, snoozed, was dismissed or missed, missions done) | The morning dashboard and streaks |
| A short diagnostics log (scheduling events, errors) | The Alarm health screen. It is bounded and never leaves the phone unless you export it |
| For the QR/barcode mission: the text of the code you chose | To check that you scanned the same code |
| For the NFC mission: the identifier of the tag you chose | To check that you tapped the same tag |
| For the photo mission: a compact **fingerprint** of the scene (a 32×32 grayscale layout and a colour histogram, a few hundred bytes) | To check that the camera is looking at the same place. **The photo itself is not saved**, and the fingerprint cannot be turned back into a recognisable picture |

Alarm data is kept in device-protected storage so alarms can ring after a restart before you unlock your phone.

## Permissions and sensors

Each permission is requested only when the feature that needs it is used. See [docs/PERMISSIONS.md](docs/PERMISSIONS.md)
for the full list. In short: the **camera** is used only while a QR or photo mission is on screen, frames are
processed in memory and never saved or sent; **activity recognition / step counter** and the **accelerometer** are
used only during the steps, shake and squats missions; **calendar** access is optional and read-only, used only to
find days you marked as exceptions, and only the calendars you pick; **NFC** is used only for the NFC mission.

## Backups and exports

A backup is a JSON file that **you** create and **you** choose where to save, through Android's file picker.
WakeNudge does not upload it and keeps no copy. Anyone who can read that file can read your alarms and settings, so
store it accordingly. The same applies to the Alarm health diagnostics export if you choose to share it.

## Your control

Uninstalling the app or clearing its storage in Android settings removes everything it stored. Backup files you saved
elsewhere are yours to delete.

## Children

The app collects no personal information from anyone, including children.

## Changes

If this policy ever changes, the change will be in the repository's history and in the release notes.

## Contact

Open an issue in the project's repository.
