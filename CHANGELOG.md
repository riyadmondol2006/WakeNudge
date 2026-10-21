# Changelog

All notable changes are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project uses [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-10-21

First release.

### Alarms
- Unlimited alarms with labels, repeat days, one-time dates, groups/profiles, skip next ring, delete after use
- Vacation mode, holiday and calendar exceptions, upcoming-alarm heads-up, bedtime reminders and a sleep planner
- Timer, stopwatch with laps, world clock, live countdown, three home-screen widgets and a Quick Settings tile
- Local backup and restore to a user-chosen JSON file, with validation and a confirmation before anything is replaced

### Alarm engine
- Exact `setAlarmClock` scheduling, `USAGE_ALARM` audio, foreground service and full-screen lock-screen UI
- Direct Boot support, reboot/update/time-zone/DST handling, persisted snooze, backup rings and wake-check
- Extra Loud mode, volume ramp up to 10 minutes, 40 s intensify, hearing-safety cap, random and custom sounds,
  vibration patterns including a recorded rhythm, offline TTS announcements, sunrise mode and backup alarm
- Alarm health screen with diagnostics, fixes and a real test alarm; morning dashboard with history and streaks

### Missions
- Math, memory, typing, QR/barcode, NFC, shake, steps, squats, puzzles (three kinds) and photo matching
- Configurable difficulty and count, chaining, previews, Solve-to-Snooze, never-trap fallbacks and Emergency stop
- "Still awake?" wake-up check with countdown, re-ring and repeat limits

### Design
- Material You dynamic colour, light, dark and AMOLED themes, expressive shapes and spring motion, edge-to-edge
- 12 selectable loudness-normalised tones (plus two internal ones), an original adaptive icon, onboarding and settings

### Quality
- 297 JVM tests (including seeded fuzz tests) and 72 instrumented tests, with real-world abuse suites
- Build-time guard against network permissions; R8-minified release build verified on a device
- See [docs/TESTING.md](docs/TESTING.md) for what was and was not verified on hardware
