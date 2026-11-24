# Privacy Policy

_Last updated: 24 November 2026_

**WakeNudge: Alarm Clock** ("WakeNudge", "the app") is a free, open-source alarm clock. This policy explains what the
app does with information. Short version: **everything stays on your phone.**

- Developer: riyadmondol2006, source code at <https://github.com/riyadmondol2006/WakeNudge>
- Privacy contact: open an issue at <https://github.com/riyadmondol2006/WakeNudge/issues>
- This policy: <https://github.com/riyadmondol2006/WakeNudge/blob/main/PRIVACY.md>

## The short version

- **Network:** the app's only network feature is a check for a newer release, at most once an hour. See "Update check"
  below. You can switch it off.
- There are **no accounts, no ads, no analytics, no crash reporting, no advertising ID and no tracking** of any kind.
- No third-party SDK that collects data is included. The only libraries are open-source Android and Kotlin libraries
  for the user interface, storage, camera and barcode decoding, all running on the device.
- **Microphone:** used only by sleep tracking, only if you switch on "Record snoring" (off by default). Listening happens
  on the phone; at most three short clips a night are kept, on the phone, for 7 days. See "Sleep tracking and the
  microphone" below.
- We (the developer) receive no data from the app, so we hold nothing about you.

## What is stored, and where

Everything below is stored only on your phone, in the app's private storage, which other apps cannot read. It is
created by you using the app and is used only to make the app work:

| Data | Why |
|---|---|
| Your alarms (times, labels, repeat days, sounds, missions), groups, timers, world clocks, exception dates and settings | So your alarms work |
| References to sound files you picked (the file's address, not a copy) | So the alarm can play your own music |
| Alarm history (when each alarm rang, snoozed, was dismissed or missed, how many missions were done, and whether a backup mission, a way out, an Emergency pause, a restart, a mute or a return to the alarm screen happened) | The Report tab's wake-up report, streaks and "How it went" |
| While an alarm rings: a copy of that alarm and its progress (snoozes used, missions done) | So a ring that is killed or cut short by a restart comes back as it was. Removed when the ring ends, and never part of a backup |
| For about a week: why recent alarms rang late or not at all (force stopped, phone off, restarted), worked out from the history and from what Android reports about how the app last stopped | To tell you at the next start, and in Alarm health. Never part of a backup |
| Which alarms you have rehearsed | To offer device admin and removing the way out only after a rehearsal. Never part of a backup |
| A short diagnostics log (scheduling events, errors) | The Alarm health screen. It is bounded and never leaves the phone unless you export and share it |
| For the QR/barcode mission: the text of the code you chose | To check that you scanned the same code |
| For the NFC mission: the identifier of the tag you chose | To check that you tapped the same tag |
| Habits (name, icon, colour, due days, reminder time, order, creation date) and the days you ticked, in `habits.db` | The habit tracker, its streaks, the Report tab's Habits section and reminders. **Part of the backup file** |
| Sleep nights, in `sleep.db`: when each night started and ended and why, whether the microphone was on and when it stopped, and for every 30 seconds a few numbers saying how much the phone moved and, with Record snoring on, how loud the room was and how many snores were heard | The night's estimate and the Report tab's Sleep section. The motion readings themselves are never stored. Deleted after a year. **Never part of a backup** |
| Snore clips (only with Record snoring on): at most three a night, each 10 seconds or less, compressed, with when it was recorded and how loud it was | So you can listen to them. Deleted after 7 days. **Never part of a backup** |
| The last sleep-sounds mix, in `sleep_sounds.db`: which sounds, their levels, the timer and, if you added one, the address of your audio file (not a copy) | So the mix is there next time. **Never part of a backup** |
| For the photo mission: a compact **fingerprint** of the scene (a 32×32 grayscale layout and a colour histogram, a few hundred bytes) and a **tiny reference thumbnail** (about 80 pixels on its longest side, a few kilobytes) | The fingerprint checks that the camera is looking at the same place. The thumbnail shows you in the alarm editor what to photograph. **The full-resolution photo is never saved**, and the fingerprint cannot be turned back into a recognisable picture |

Alarm data is kept in device-protected storage so alarms can ring after a restart before you unlock your phone. Habits
are kept there too, so their reminders can be set again after a restart. Sleep nights and snore clips are kept in
credential-encrypted storage instead: they stay encrypted until you unlock the phone after it starts, and nothing reads
them before then.

None of this is collected, transmitted or shared by the app. Step counts, squat counts and other sensor readings used
during missions are used live and are not stored. A snore clip leaves the phone only if you tap its Share button: it is
then handed, through Android's share sheet, to the one app you pick, and the temporary copy made for that is deleted at
the next clean-up or share.

## Permissions and sensors

Each permission is requested only when the feature that needs it is used. See the
[permission list](https://github.com/riyadmondol2006/WakeNudge/blob/main/docs/PERMISSIONS.md) for every one and why.
In short: the **camera** is used only while a QR or photo mission is on screen, frames are processed in memory and are
never saved or sent; **activity recognition / step counter** and the **accelerometer** are used only during the steps,
shake and squats missions, and the step counter's total is also read twice for a "Still awake?" check that asks for
proof (when it starts and when it is due), to see whether you have walked about; only that number is kept, on the phone,
until the check ends; **calendar** access is optional and read-only, used only to find which days are all-day
events (for example public holidays) in the calendars you pick, and the app reads neither event titles nor
descriptions; **NFC** is used only for the NFC mission. **Activity recognition** is also asked for when you start
sleep tracking on Android 14 and later, which requires it before an app may keep reading the motion sensor in the
background for health; it is used only to feel movement in bed, on the phone. The **microphone** is described below. The
app never asks for your location, contacts, phone, SMS or files. **Device admin** is optional and asks for no device powers at all (it cannot wipe, lock or watch the
phone); being active only stops WakeNudge from being force-stopped or uninstalled until you switch it off. Which alarms
you have rehearsed is remembered on the phone only and is not part of a backup. The torch that can flash for an ignored
alarm is switched on and off without opening the camera.

## Sleep tracking and the microphone

Only when you tap Start, WakeNudge reads the phone's motion sensor and keeps, for every 30 seconds, a few numbers saying
how much the phone moved; the readings themselves are never stored. Sleep stages are an estimate worked out from those
numbers (and from sound, if Record snoring is on), not a measurement and not medical advice.

If you switch on "Record snoring" (off by default; the microphone permission is asked for only then), the app also uses
the microphone while tracking runs: it measures how loud the room is every 30 seconds and listens for snoring on the
phone, with no model download. It keeps at most three short clips (10 seconds or less) a night, compressed, in the app's
private storage. The microphone stops the moment an alarm starts ringing and stays off for the rest of that night, and
the tracking notification says while it is on. Android also shows its own microphone indicator. Nothing is sent
anywhere, and a clip leaves the phone only if you tap its Share button.

## Update check

- WakeNudge holds Android's `INTERNET` permission for one purpose: asking `api.github.com` whether a newer
  release of WakeNudge exists. It is on by default and can be switched off in About, under Updates.
- It runs when you open the app, and at most once an hour. The request carries no account, alarm data, settings, device identifier or advertising
  ID. Like any web request it necessarily exposes your IP address and standard request headers (such as the
  user-agent) to GitHub, which handles them under
  [its own privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
  We cannot see those requests.
- If a newer release exists the app only offers to open the release page in your browser. It does not download or
  install anything by itself.

## Backups and exports

A backup is a JSON file that **you** create and **you** choose where to save, through Android's file picker.
WakeNudge does not upload it and keeps no copy. It contains your alarms (including labels and, for missions, the
QR text, NFC tag identifier, photo fingerprint and thumbnail), groups, world clocks, exception dates, settings, your
habits and the days you ticked them, and optionally your history. It never contains sleep nights, snore clips or the
sleep-sounds mix. Anyone who can read that file can read those things, so store it accordingly. The same applies
to the Alarm health diagnostics export if you choose to share it; that report contains counts, reliability-related
settings and engine events, and no alarm names or labels.

The app opts out of Google's cloud backup and of automatic phone-to-phone transfer, so no copy of your data is made
behind your back. To move to a new phone, use the backup file.

## How long data is kept, and deleting it

Data stays on your phone until you delete it. You can delete individual alarms, timers, history and other items in the
app. Deleting a habit deletes the days you ticked it. In sleep tracking you can delete one snore clip, all snore clips
(Track my sleep, then Delete all snore clips) or a whole night with its clips (the night's detail, then Delete night) at
any time; clips are deleted by themselves after 7 days and nights after a year. To delete everything at once, uninstall
the app or clear its storage in Android settings (Apps, WakeNudge, Storage, Clear storage): that removes all of it, sleep
nights, clips and habits included. Backup files you
saved elsewhere are yours to delete. Because the developer never receives your data, there is nothing to delete on a
server and no account to close.

## Security

Data is kept in the app's private storage and is not readable by other apps. The only network
feature is the optional update check described above. The app's code is open source, so anyone can check these
statements.

## Children

WakeNudge is not directed at children under 13. The app collects no personal information from anyone, including
children.

## Changes

If this policy changes, the new text will be published at the address above, the "last updated" date will change, and
the change will be visible in the repository history and the release notes.

## Contact

Open an issue at <https://github.com/riyadmondol2006/WakeNudge/issues>.
