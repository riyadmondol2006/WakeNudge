# Habit tracker

## What is included

- Up to 30 habits, each with a required name (40 UTF-16 code units maximum), an emoji/icon, one of eight colours, a weekly schedule, an optional reminder time, a creation date and a user-controlled order.
- The Habits screen has add/edit sheets, preset and free-text icons, up/down reordering, confirmed deletion, today's check-in, current and best streaks, and the current week.
- A check-in is one row per habit and local date. A due day can be ticked or unticked from its creation date through today, but only within today and the preceding six days. Future days, non-due days and older days cannot be changed. Notification **Done** uses the same repository rule.
- Report → Habits has a Monday-start week picker, earlier/next navigation bounded at the current week, seven day states, editable recent days, current/best streaks and per-habit weekly completion. Its empty state opens the Habits screen with `nav.openHabits`.
- The day UI exposes full dates and states to TalkBack. The streak uses a custom drawn flame. Day editing uses check-box semantics; colours and reorder/edit/delete controls have labels. The editor's weekdays wrap into two rows so their touch targets fit at 360 dp.

## Rules and architecture

`domain/habits/` contains serializable models, repository contracts, check-in rules and the pure streak calculator. A streak counts consecutive completed **due** days. Non-due days are skipped, and an unfinished today does not break the current streak; it becomes a break once it is in the past. Best streak is the longest completed run since creation.

**Schedule edits are retrospective:** the habit's current weekday mask is applied to its entire history. This deliberately avoids storing schedule revisions. Existing check-ins are retained, including ones on days that are no longer due, but those check-ins do not affect streaks or completion while excluded by the current mask.

`data/habits/` owns the independent Room database `habits.db`, schema version 1, its DAO/repository and Hilt module. The schema is exported under `app/schemas/com.riyadm.wakenudge.data.habits.HabitDatabase/1.json`. Check-ins have a `(habitId, epochDay)` primary key and cascade on habit deletion. The main alarm database and migrations are unchanged.

`ui/habits/` contains both view models, shared day/streak UI, the Habits screen and the notification destination activity. `ui/report/HabitReportSection.kt` contains only the report section. Week completion is completed due days divided by due days from the selected week's Monday through today (or Sunday for an earlier week), rounded down; a week with no elapsed due days shows 0%.

## Reminders

Reminders are ordinary, gentle notifications on channel `habit_reminders_v1`, never full-screen alarms. They do not use or share the ring service, alarm audio stream, ring controller or ring locks. **Done** records the notification's local date and dismisses it; **Open** and tapping the notification open the Habits screen. An already-completed habit is not notified again.

Scheduling uses `AlarmManager.setWindow(RTC_WAKEUP, …, 10 minutes, …)`. Android may deliver within that window or later under power management, so reminder times are not exact. Only the next due occurrence is scheduled; delivery schedules the following one. Boot, locked boot, package replacement, manual time changes and time-zone changes re-arm all reminders through the non-exported habit system receiver. The database and scheduling preferences use device-protected storage so locked-boot re-arming can read them. The notification activity itself is not direct-boot aware.

The isolated identifier ranges are:

- Notification IDs: base 140000, using the low 16 bits of the habit ID.
- Reminder PendingIntent request codes: 700000–704095.
- Done PendingIntent request codes: 710000–714095.
- Open PendingIntent request codes: 720000–724095.

When app notifications, runtime notification permission or the habit channel are disabled, the Habits screen explains that reminders cannot show and links to the app's notification settings. Saving a reminder on Android 13+ requests notification permission when needed.

## Backup format

Backup format version remains 1. Every new export adds an optional top-level `habits` object:

```json
"habits": {
  "habits": [
    {
      "id": 1,
      "name": "Read",
      "icon": "📚",
      "colorIndex": 0,
      "daysMask": 127,
      "reminderMinutes": 480,
      "createdEpochDay": 20000,
      "sortOrder": 0
    }
  ],
  "checkIns": [{ "habitId": 1, "epochDay": 20001 }]
}
```

`WakeJson` continues to ignore unknown keys, so older app versions can read a new file. Restoring a file **without** `habits` leaves current habits untouched. A file with `habits` replaces all habits and check-ins, including with an empty collection. Sanitisation limits counts, text lengths, palette indices, weekday masks, reminder minutes, dates and ordering; it assigns safe IDs, relinks check-ins, removes pre-creation/orphan/duplicate check-ins and reports skipped/clamped data through existing backup warnings. Reminders are re-armed after restore.

The alarm and habit stores are separate databases. Restore rolls each store back on failure where possible, but there is no atomic transaction spanning both databases; rollback across them is therefore best-effort.

## Automated verification

- JVM tests cover due-day/edit windows, mid-week creation, current and best streaks, unticking, retrospective schedule edits, reminder planning across DST gaps/overlaps and time-zone changes, notification Done behavior, backup round trips, old files without habits, missing habit fields and hostile values.
- `HabitRepositoryInstrumentedTest` covers the seven-day window, future rejection, unticking, cascading deletion, reordering and replacement through real Room. It is compiled with `:app:compileDebugAndroidTestKotlin`; it was not executed because this task prohibits phone/adb use.
- Focused habit and backup JVM suites passed during implementation. Final full-suite, debug/release build and lint results are recorded in the implementation handoff.

## Exact phone verification

1. **Add and edit:** Install the debug build and open Report → Habits → **Add a habit**. Enter `Read`, choose 📚 and a colour, select Monday/Wednesday/Friday, enable a reminder and save. Confirm the new card shows the selected due days. Use Edit to rename it, move it down after adding a second habit, then move it back. Start deleting it and cancel; confirm nothing is removed.
2. **Today and recent days:** On a due day, tap the large check button and confirm today's circle, current streak and best streak become 1. Tap again to clear them. In Report → Habits, tap the same recent due day and confirm both screens agree. Confirm future days, non-due days, days before creation and days older than the recent seven-day window do not toggle.
3. **Reminder and notification Done:** Set a due-day reminder 2–3 minutes ahead, save, allow notifications and leave the app. Wait at least 15 minutes because delivery is inexact. Confirm a normal notification arrives without a full-screen ring or alarm sound. Tap **Done**, open Habits and confirm today is ticked and its streak increased. Delete the habit and confirm its notification is dismissed.
4. **Open action:** With another reminder notification visible, tap **Open**, then repeat with the notification body. Both must open Habits without starting an alarm.
5. **Past-day streak:** Use an every-day habit created at least four days ago with no check-ins. Tick today, yesterday and the day before; current/best streak must be 3. Untick yesterday; current must become 1 while best remains 3. Re-tick yesterday; current must return to 3. Leave today unticked after completing the preceding two days; current must remain 2 until the day ends.
6. **Week report:** Open Report → Habits and use the left arrow to inspect earlier weeks. Confirm the right arrow is disabled at this week. Check done/missed/not-due/future states and the completion percentage against manual counts. Rotate the phone and repeat with system font scale 2.0, a 360 dp-wide display/window, RTL layout, and dark/light/AMOLED themes; check TalkBack labels and that all editor controls remain reachable.
7. **Notification-off state:** Disable WakeNudge notifications in system settings and return to a habit with a reminder. Confirm the warning appears and its button opens WakeNudge notification settings. Re-enable notifications and confirm the warning disappears.
8. **Lifecycle re-arming:** Set a reminder a few minutes ahead, reboot the phone and verify it still notifies. Repeat after an app update, a manual clock change and a time-zone change. For time zones, verify the next reminder follows the habit's local wall-clock time in the new zone.
9. **Backup round trip:** Create two habits with different schedules and several check-ins. Open Settings → Backup and restore → **Save to a file** and save a backup. Delete/change both habits, then choose **Restore**, select that file and confirm replacement. Verify names, icons, colours, order, schedules, reminder times and every check-in/streak match the exported state. Confirm subsequent reminders still fire.
10. **Old backup compatibility:** Restore a backup exported by the previous app version (without a `habits` key). Confirm existing habits and check-ins are unchanged while the other backed-up data restores normally.

## Known limits

- No habit widgets or quick-settings tile.
- Only the most recent seven days can be edited; older history remains visible but read-only.
- Schedule edits apply to all history rather than retaining historical schedules.
- Reminders are inexact and depend on notification permission/channel settings and Android power management.
- Physical UI, accessibility, notification delivery, reboot/time-zone behavior and the instrumented Room test still require the phone checklist above.

## Proposed changelog lines

- Add a habit tracker with weekly schedules, seven-day check-ins, streaks, weekly reports and gentle reminder notifications.
- Include habits and check-ins in local backups while preserving compatibility with older backup files.
