# Changelog

All notable changes are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Releases are built and published automatically from `main` and numbered `MAJOR.MINOR.N`, where `N` is the commit count
(see [docs/RELEASING.md](docs/RELEASING.md)); the notes of each GitHub release list the commits it contains.

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
