package com.riyadm.wakenudge.widget

import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.TimeFormat
import com.riyadm.wakenudge.domain.scheduling.NextAlarm
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator.Suppression
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Something worth telling the user next to (or instead of) the next alarm. */
sealed interface OutlookNote {
    data class SkippingOnce(val date: LocalDate) : OutlookNote
    data class DayOff(val date: LocalDate) : OutlookNote
    data class Vacation(val resumeEpochDay: Long?) : OutlookNote

    /** No alarm exists at all. */
    data object NoAlarms : OutlookNote

    /** Alarms exist but every one is switched off. */
    data object AllOff : OutlookNote

    /** Enabled alarms exist but none will ring (paused group, a one-time date in the past, ...). */
    data object NothingUpcoming : OutlookNote
}

/** An occurrence that [NextTriggerCalculator.suppressionOfNaturalNext] reported as not going to ring. */
data class SuppressedOccurrence(val time: LocalTime, val suppression: Suppression)

/**
 * What the next-alarm surfaces (widgets, quick-settings tile) say, independent of how it is drawn: the alarm that
 * will really ring next and an honest note when something is being skipped around it.
 */
data class AlarmOutlook(val next: NextAlarm?, val note: OutlookNote?) {

    companion object {
        /**
         * Combines the scheduler's answer with the suppressions of every alarm's natural next occurrence.
         * With a next alarm, a note appears only for a skipped/day-off occurrence that would have rung before it, or
         * for vacation mode (which pauses the alarms that do not ignore it). Without one, the note is the reason.
         */
        fun decide(
            next: NextAlarm?,
            suppressed: List<SuppressedOccurrence>,
            totalAlarms: Int,
            enabledAlarms: Int,
        ): AlarmOutlook {
            val vacation = suppressed.firstNotNullOfOrNull { it.suppression as? Suppression.Vacation }
                ?.let { OutlookNote.Vacation(it.resumeEpochDay) }
            val earliestSkip = suppressed.mapNotNull { occurrence ->
                when (val s = occurrence.suppression) {
                    is Suppression.SkippedOnce -> OutlookNote.SkippingOnce(s.date) to s.date.atTime(occurrence.time)
                    is Suppression.Exception -> OutlookNote.DayOff(s.date) to s.date.atTime(occurrence.time)
                    is Suppression.Vacation -> null
                }
            }.minByOrNull { it.second }

            val note: OutlookNote? = if (next == null) {
                vacation ?: earliestSkip?.first ?: when {
                    totalAlarms == 0 -> OutlookNote.NoAlarms
                    enabledAlarms == 0 -> OutlookNote.AllOff
                    else -> OutlookNote.NothingUpcoming
                }
            } else {
                val ringsAt = next.at.toLocalDateTime()
                earliestSkip?.takeIf { it.second.isBefore(ringsAt) }?.first ?: vacation
            }
            return AlarmOutlook(next, note)
        }
    }
}

/** "Today" / "Tomorrow" / a weekday within the coming week / a plain date, relative to a reference day. */
sealed interface RelativeDay {
    data object Today : RelativeDay
    data object Tomorrow : RelativeDay
    data class Weekday(val day: DayOfWeek) : RelativeDay
    data class OnDate(val date: LocalDate) : RelativeDay

    companion object {
        fun of(today: LocalDate, target: LocalDate): RelativeDay {
            val days = ChronoUnit.DAYS.between(today, target)
            return when {
                days == 0L -> Today
                days == 1L -> Tomorrow
                days in 2L..6L -> Weekday(target.dayOfWeek)
                else -> OnDate(target)
            }
        }
    }
}

/** How an alarm repeats, ready to be turned into words. */
sealed interface RepeatSummary {
    data object Once : RepeatSummary
    data class OnDate(val date: LocalDate) : RepeatSummary
    data object Daily : RepeatSummary
    data object Weekdays : RepeatSummary
    data object Weekends : RepeatSummary

    /** Individual days, ordered starting from the user's first day of the week. */
    data class Days(val days: List<DayOfWeek>) : RepeatSummary

    companion object {
        fun of(alarm: Alarm, firstDay: DayOfWeek): RepeatSummary {
            val mask = alarm.repeatDaysMask and Alarm.EVERY_DAY_MASK
            return when (mask) {
                0 -> alarm.oneTimeDate?.let(::OnDate) ?: Once
                Alarm.EVERY_DAY_MASK -> Daily
                Alarm.WEEKDAYS_MASK -> Weekdays
                Alarm.WEEKEND_MASK -> Weekends
                else -> Days(
                    DayOfWeek.entries.filter { alarm.repeatsOn(it) }
                        .sortedBy { (it.value - firstDay.value + 7) % 7 },
                )
            }
        }
    }
}

/**
 * Formats for the three TextClock views of the clock widget. The app's own 12/24 h preference overrides the system
 * one by giving both of a TextClock's formats the same pattern; the AM/PM marker is an empty string when not wanted.
 */
data class ClockFormats(val time12: String, val time24: String, val marker12: String, val marker24: String) {
    companion object {
        fun of(preference: TimeFormat): ClockFormats = when (preference) {
            TimeFormat.SYSTEM -> ClockFormats("h:mm", "HH:mm", "a", "")
            TimeFormat.H12 -> ClockFormats("h:mm", "h:mm", "a", "a")
            TimeFormat.H24 -> ClockFormats("HH:mm", "HH:mm", "", "")
        }
    }
}

/** Alarms whose group is switched off never ring, however the alarm itself is set. */
fun List<AlarmGroup>.disabledIds(): Set<Long> = filterNot { it.enabled }.mapTo(hashSetOf()) { it.id }
