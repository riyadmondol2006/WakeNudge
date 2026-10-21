# Privacy Policy

_Last updated: 21 October 2026_

**WakeNudge: Alarm Clock** ("WakeNudge", "the app") is a free, open-source alarm clock. This policy explains what the
app does with information. Short version: **everything stays on your phone.**

- Developer: riyadmondol2006, source code at <https://github.com/riyadmondol2006/WakeNudge>
- Privacy contact: open an issue at <https://github.com/riyadmondol2006/WakeNudge/issues>
- This policy: <https://github.com/riyadmondol2006/WakeNudge/blob/main/PRIVACY.md>

## The short version

- **Google Play version:** the app has **no internet permission**. It cannot send anything anywhere, and the build
  fails if that ever changes.
- **GitHub version** (an APK downloaded from the project's GitHub Releases page, not from Google Play): the only
  network feature is a once-a-day check for a newer release. See "Update check in the GitHub version" below. You can
  switch it off.
- There are **no accounts, no ads, no analytics, no crash reporting, no advertising ID and no tracking** of any
  kind in either version.
- No third-party SDK that collects data is included. The only libraries are open-source Android and Kotlin libraries
  for the user interface, storage, camera and barcode decoding, all running on the device.
- We (the developer) receive no data from the app, so we hold nothing about you.

## What is stored, and where

Everything below is stored only on your phone, in the app's private storage, which other apps cannot read. It is
created by you using the app and is used only to make the app work:

| Data | Why |
|---|---|
| Your alarms (times, labels, repeat days, sounds, missions), groups, timers, world clocks, exception dates and settings | So your alarms work |
| References to sound files you picked (the file's address, not a copy) | So the alarm can play your own music |
| Alarm history (when each alarm rang, snoozed, was dismissed or missed, how many missions were done) | The morning dashboard and streaks |
| A short diagnostics log (scheduling events, errors) | The Alarm health screen. It is bounded and never leaves the phone unless you export and share it |
| For the QR/barcode mission: the text of the code you chose | To check that you scanned the same code |
| For the NFC mission: the identifier of the tag you chose | To check that you tapped the same tag |
| For the photo mission: a compact **fingerprint** of the scene (a 32×32 grayscale layout and a colour histogram, a few hundred bytes) and a **tiny reference thumbnail** (about 80 pixels on its longest side, a few kilobytes) | The fingerprint checks that the camera is looking at the same place. The thumbnail shows you in the alarm editor what to photograph. **The full-resolution photo is never saved**, and the fingerprint cannot be turned back into a recognisable picture |

Alarm data is kept in device-protected storage so alarms can ring after a restart before you unlock your phone.

None of this is collected, transmitted or shared by the app. Step counts, squat counts and other sensor readings used
during missions are used live and are not stored.

## Permissions and sensors

Each permission is requested only when the feature that needs it is used. See the
[permission list](https://github.com/riyadmondol2006/WakeNudge/blob/main/docs/PERMISSIONS.md) for every one and why.
In short: the **camera** is used only while a QR or photo mission is on screen, frames are processed in memory and are
never saved or sent; **activity recognition / step counter** and the **accelerometer** are used only during the steps,
shake and squats missions; **calendar** access is optional and read-only, used only to find which days are all-day
events (for example public holidays) in the calendars you pick, and the app reads neither event titles nor
descriptions; **NFC** is used only for the NFC mission. The app never asks for your location, contacts, microphone,
phone, SMS or files.

## Update check in the GitHub version

This section applies **only** to the APK from GitHub Releases. The Google Play version has no such feature and no
internet permission.

- The GitHub version holds Android's `INTERNET` permission for one purpose: asking `api.github.com` whether a newer
  release of WakeNudge exists. It is on by default and can be switched off in About, under Updates.
- It runs at most once a day. The request carries no account, alarm data, settings, device identifier or advertising
  ID. Like any web request it necessarily exposes your IP address and standard request headers (such as the
  user-agent) to GitHub, which handles them under
  [its own privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
  We cannot see those requests.
- If a newer release exists the app only offers to open the release page in your browser. It does not download or
  install anything by itself.

## Backups and exports

A backup is a JSON file that **you** create and **you** choose where to save, through Android's file picker.
WakeNudge does not upload it and keeps no copy. It contains your alarms (including labels and, for missions, the
QR text, NFC tag identifier, photo fingerprint and thumbnail), groups, world clocks, exception dates and settings, and
optionally your history. Anyone who can read that file can read those things, so store it accordingly. The same applies
to the Alarm health diagnostics export if you choose to share it; that report contains counts, reliability-related
settings and engine events, and no alarm names or labels.

The app opts out of Google's cloud backup and of automatic phone-to-phone transfer, so no copy of your data is made
behind your back. To move to a new phone, use the backup file.

## How long data is kept, and deleting it

Data stays on your phone until you delete it. You can delete individual alarms, timers, history and other items in the
app. Uninstalling the app or clearing its storage in Android settings removes everything it stored. Backup files you
saved elsewhere are yours to delete. Because the developer never receives your data, there is nothing to delete on a
server and no account to close.

## Security

Data is kept in the app's private storage and is not readable by other apps. The Google Play version cannot transmit
it because it has no internet permission. The app's code is open source, so anyone can check these statements.

## Children

WakeNudge is not directed at children under 13. The app collects no personal information from anyone, including
children.

## Changes

If this policy changes, the new text will be published at the address above, the "last updated" date will change, and
the change will be visible in the repository history and the release notes.

## Contact

Open an issue at <https://github.com/riyadmondol2006/WakeNudge/issues>.
