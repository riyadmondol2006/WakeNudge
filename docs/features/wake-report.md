# Wake-up report

## What was built

The Report tab's Wake up section now shows a real wake-up report instead of the old Morning screen:

- A week picker (this week by default, back arrow to earlier weeks, forward arrow disabled at this week).
  Weeks start on the user's first day of week (`AppSettings.firstDayOfWeek`).
- Two headline numbers for the shown week: average wake-up time and average time to wake up.
- A chart with one vertical pill per day, from that day's first ring to its dismissal, on a time axis
  fitted to the week, with a dashed average line, today highlighted, day letters below, and a faint
  track with no pill for days without a morning. Missed/emergency-only days get a marker at the first
  ring instead of a pill.
- "View daily report" (and tapping any day in the chart) opens a bottom sheet for that day: every ring
  (time, label, snoozes, missions done or the way out used, how long it took, still-awake check result)
  plus the "How it went" facts for the day.
- Below the week: pending still-awake checks (with cancel where the check does not need proof), the
  "How it went" card, the streak card, and clear history with confirmation. The old Morning screen's
  own top bar is gone; the Report screen's title stands alone, and the old `ui/morning/` files were
  removed once their record moved here.

## Decisions

- **A morning** (in `WakeReportCalculator`, `domain/stats/WakeReportStats.kt`): for each calendar day,
  the wake-up is the earliest ring whose first ring fired between 03:00 and 15:00 local time and which
  ended in a real dismissal. Wake-up time is the dismissal instant; time to wake up is elapsed epoch
  time from that occurrence's first ring to the dismissal, so snoozes count and DST jumps do not distort
  it. Rows sharing alarm and scheduled time are grouped as one occurrence, so a backup ring of the same
  occurrence extends the same wake-up instead of becoming a second one. Days with only missed or
  emergency-stopped morning rings are `NO_WAKE_UP`, a still-ringing morning is `STILL_RINGING` until a
  dismissal decides it, and rings outside the window are listed in the daily report but never make a
  wake-up. Test alarms and timers use reserved non-positive alarm ids and are filtered out; they never
  reach the history in the first place.
- A dismissal whose still-awake check later failed still counts as the day's wake-up (the user did get
  up and dismiss); the failed check is shown in the daily report and still breaks the streak in the
  existing `WakeStats` maths, which is unchanged.
- **Average wake-up time** is a circular mean of clock times, so 23:50 and 00:10 average to 00:00.
  Opposed times with no mean direction (00:00 and 12:00) yield no average.
- **Chart scale** adapts to the week's first-ring and wake-up times: 30 minutes or 15% padding, at
  least a two-hour span, hour-aligned edges, 1/2/3-hour ticks by span, and an axis that wraps past
  midnight when a week genuinely spans it. Both headline scales come from the same pure calculator.
- History is read only (`HistoryRepository.observeSince`, 120 days, as before); no schema, database,
  or engine change. The zone used is the zone in force when the report is built; durations are always
  epoch differences. The calculator itself takes a per-ring zone resolver, and the time-zone-change
  case is covered by JVM tests through it.
- Accessibility: each chart day is a tappable target whose content description gives the day, the
  wake-up time and the time to wake up (or no wake-up / no alarm / still ringing); today is marked by
  colour and a filled circle behind its letter, not colour alone.

## Limits

- Rings are assigned to days by their firing instant in the current zone; a phone that changed zone
  and then shows old weeks re-reads those days in the new zone, except where the per-ring resolver is
  used. Durations are unaffected.
- A morning whose first ring is outside 03:00–15:00 never counts as a wake-up, even if dismissed later.
- The week picker can go back as far as the 120-day history window the screen observes.

## How it was tested

- New JVM tests in `WakeReportStatsTest` (19 tests): week starts, empty and single-morning weeks,
  earliest-dismissed-wins, snooze/backup occurrence grouping, missed/emergency-only days, a ring still
  ringing, timer/test exclusion, the 03:00/15:00 window edges, after-midnight rings, circular averages
  near midnight, DST spring-forward day assignment and a fall-back morning inside the window
  (Pacific/Chatham), a mid-week time-zone change, and chart-scale adaptation and wrapping.
- Existing `StatsTest` still passes; `./gradlew :app:testDebugUnitTest`, `:app:assembleDebug`,
  `:app:assembleRelease` and `:app:lintDebug` were run before reporting (counts in the report message).

## Not verified

- No device run (per the shared brief): TalkBack order, font scale 2.0, RTL, and AMOLED rendering of
  the chart and sheet need the phone steps in the report message.

## Proposed changelog lines

- "Report tab: a weekly wake-up report with average wake-up time, time to wake up, a per-day chart and a daily report for each day."
- "The Morning screen moved into the Report tab; streaks, still-awake checks and clear history are still there, below the week view."
