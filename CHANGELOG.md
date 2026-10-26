# Changelog

All notable changes are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Releases are built and published automatically from `main` and numbered `MAJOR.MINOR.N`, where `N` is the commit count
(see [docs/RELEASING.md](docs/RELEASING.md)); the notes of each GitHub release list the commits it contains.

## Real-life audit

Every part of the app was read again as a half-awake user would break it. What changed:

### Easier wake-up missions
- **Math is much easier at every level.** Easy is like 7 + 5 or 9 − 4, medium (the default) 27 + 6 or 7 × 4, hard
  38 + 47 or 7 × 8 + 23. The editor shows a sample question under the difficulty
- The sliding puzzle is always 3 × 3 (hard was a fully shuffled 15-puzzle); the memory game starts one tile shorter and
  plays a little slower; typing phrases are shorter, medium has no punctuation, and curly quotes or long dashes typed by
  the keyboard count as the plain ones
- The photo mission still matches a spot photographed under a warm lamp when the alarm rings in daylight
- Pattern tracing never asks for a "knight's jump" line that grazes two other dots

### Fixed: alarms that could fail to ring
- After a **force-stop** (or a phone that "cleans" swiped-away apps) Android forgot every alarm and nothing put them
  back until a reboot. The app now re-arms everything the next time it starts
- Switching on one alarm of a switched-off group turned the group on but armed only that one alarm
- After a reboot, an alarm due in the second between re-arming and the missed-alarm check was lost; after an app
  update, an alarm due during the install was never rung or reported
- Deleting a switched-off group made its alarms ring
- A ring that the backup alarm had just taken over could be ended by the ring timeout due in the same second
- On Android 12 with "Alarms & reminders" switched off, alarms could ring up to an hour late while Alarm Health said
  they were not affected; it is now an error, with a notification
- A camera that is refused, broken or busy no longer leaves a QR or photo mission with nothing to press: Continue appears

### Fixed: the wrong thing happening
- A typed time could be saved as a different one (06:45 typed over 07:00 became 06:55): the wheels reported the rows
  they glided past. A locked editor's wheels no longer turn
- Saving in an editor opened before the alarm rang turned a finished one-time alarm back on, undid a skip, or brought
  back a deleted alarm. Double-tapping Save created two alarms
- "Skip next" tapped twice (widget, notification) skipped the following day instead
- A Stop or Dismiss that arrived just after another ring took over ended that ring; volume keys during the silent
  sunrise glow could snooze, or delete a delete-after-use alarm before it rang
- A second alarm waiting behind the first played the first alarm's sound after the first was dismissed
- Rotating the phone or tapping Back in a chain of missions started the chain over; in landscape on a large screen
  the mission area had no height at all
- Restoring a backup could bring back an old alarm's snooze and ring a restored alarm out of nowhere
- Times read backwards (05:10 as 01:50) on phones set to right-to-left languages

### New
- **Low-battery warning:** about 8 hours before an alarm, if the battery is at 20 % or less and not charging, a
  notification says to plug the phone in (Sleep, then "Warn me when the battery is low")
- Switching an alarm on says when it rings ("Rings in 9 h 40 min"), so a 7:00 PM slip is caught at once
- "Pause all" can be undone from the snackbar
- Alarm Health also checks the backup alarm sound channel and Do Not Disturb modes that mute alarms

## Initial public release (1.0.x)

### Alarms
- Unlimited alarms with labels, repeat days, one-time dates, groups/profiles, skip next ring, delete after use
- Alarm options are grouped into six collapsible categories with a live summary each: wake-up challenge, sound and
  light, volume and vibration, snooze, oversleep protection and schedule rules. "New alarm defaults" uses the same groups
- **Strict alarms:** snooze has a switch right on its card, and the Emergency stop (on by default) can be switched off
  per alarm, after a confirmation. Missions the phone cannot perform still offer Continue, and the ring timeout still
  ends the alarm
- Vacation mode and holiday/calendar exceptions in one place, upcoming-alarm heads-up, bedtime reminders and a sleep planner
- Timer, stopwatch with laps, world clock, live countdown, three home-screen widgets and a Quick Settings tile
- Settings is a short grouped hub (alarms, ringing and sound, look and feel, reliability and backup, about)
- Local backup and restore to a user-chosen JSON file, with validation and a confirmation before anything is replaced

### Sound
- Tapping a tone in the picker plays it immediately; the preview never outlives the picker
- 12 selectable tones from gentle to loud (plus two internal ones), your own music, random tone per ring
- Volume ramp up to 10 minutes, a sound that intensifies after 40 s, hearing-safety cap on headphones
- **Extra Loud is a strength slider** (Off to 100 %); it is never applied on headphones while hearing protection is on
- Strong vibration patterns including a recorded rhythm, offline spoken announcements, sunrise mode, backup alarm

### Missions and wake-up check
- Math, memory, typing, QR/barcode, NFC, shake, steps, squats, puzzles (three kinds) and photo matching
- Configurable difficulty and count, chaining, previews, Solve-to-Snooze
- "Still awake?" check with countdown, re-ring and repeat limits

### Reliability
- Exact `setAlarmClock` scheduling, `USAGE_ALARM` audio, foreground service and full-screen lock-screen UI
- Direct Boot, reboot/update/time-zone/DST handling, persisted snooze, backup rings and wake-check
- Hardened against audio-focus loss, dying players and services, ring-screen crashes (with a rescue ring), refused alarm
  registration, full storage, corrupt settings, wall-clock jumps and refused foreground-service starts
- Alarm Health screen with diagnostics, fixes and a real test alarm; morning dashboard with history and streaks
- Room database migrations are explicit and tested; there is no destructive fallback

### Battery
- Widgets refresh on events instead of a 30-minute wakeup per widget; one non-waking alarm covers the date rolling over

### Two builds
- `play`: no network permission and no update code
- `github` (the APK on GitHub Releases): holds only `INTERNET`, used for an optional update notice that checks GitHub at
  most once an hour and can be switched off in About. The Welcome screen, About and Settings link to the source code and the website

### Design
- Material You dynamic colour, light, dark and AMOLED themes, expressive shapes and spring motion, edge-to-edge
- An original adaptive icon, onboarding and settings

### Quality and delivery
- Hundreds of JVM tests (including seeded fuzz tests) for both builds and 130+ instrumented tests, with real-world abuse suites
- Build-time guard for network permissions per build; R8-minified release builds verified on a device
- Every push to `main` builds, verifies and publishes a signed APK and AAB (see [docs/RELEASING.md](docs/RELEASING.md))
- See [docs/TESTING.md](docs/TESTING.md) for what was and was not verified on hardware
