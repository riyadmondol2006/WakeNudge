package com.riyadm.wakenudge.domain.scheduling

import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.VacationMode
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.inject.Inject

/** Everything besides the alarm itself that decides whether a given day may ring. */
data class SchedulingContext(
    val vacation: VacationMode = VacationMode(),
    /** Epoch days that suppress alarms flagged [Alarm.respectExceptions] (manual dates + calendar holidays). */
    val exceptionDays: Set<Long> = emptySet(),
    /** Whether the alarm's group (if any) is switched on. */
    val groupEnabled: Boolean = true,
)

/**
 * Pure, side-effect-free scheduling maths. Wall-clock semantics: an alarm at 07:00 rings at 07:00 local time,
 * whichever zone or DST offset is in force on the day. A time that falls into a spring-forward gap is moved forward
 * by the gap (java.time's resolution), a time that occurs twice in a fall-back overlap rings at its first occurrence.
 */
class NextTriggerCalculator @Inject constructor() {

    /** The next moment strictly after [now] at which [alarm] should ring, or null when it never will. */
    fun next(alarm: Alarm, now: ZonedDateTime, context: SchedulingContext = SchedulingContext()): ZonedDateTime? =
        occurrences(alarm, now, context).firstOrNull()

    /** Up to [count] upcoming occurrences, for previews such as "next rings: Mon 07:00, Tue 07:00". */
    fun upcoming(
        alarm: Alarm,
        now: ZonedDateTime,
        context: SchedulingContext = SchedulingContext(),
        count: Int = 5,
    ): List<ZonedDateTime> = occurrences(alarm, now, context).take(count).toList()

    /**
     * What suppresses the occurrence that would otherwise ring next, for UI labels ("Skipped: Christmas Day").
     * Returns null when the next plain occurrence is not suppressed.
     */
    fun suppressionOfNaturalNext(alarm: Alarm, now: ZonedDateTime, context: SchedulingContext): Suppression? {
        if (!alarm.enabled || !context.groupEnabled) return null
        val plain = occurrences(alarm, now, SchedulingContext(), ignoreSkip = true).firstOrNull() ?: return null
        val date = plain.toLocalDate()
        return when {
            alarm.isRepeating && alarm.skipEpochDay == date.toEpochDay() -> Suppression.SkippedOnce(date)
            alarm.isRepeating && alarm.respectExceptions && date.toEpochDay() in context.exceptionDays ->
                Suppression.Exception(date)
            !alarm.ignoreVacation && context.vacation.enabled &&
                (context.vacation.resumeEpochDay?.let { date.toEpochDay() < it } ?: true) ->
                Suppression.Vacation(context.vacation.resumeEpochDay)
            else -> null
        }
    }

    sealed interface Suppression {
        data class SkippedOnce(val date: LocalDate) : Suppression
        data class Exception(val date: LocalDate) : Suppression
        data class Vacation(val resumeEpochDay: Long?) : Suppression
    }

    private fun occurrences(
        alarm: Alarm,
        now: ZonedDateTime,
        context: SchedulingContext,
        ignoreSkip: Boolean = false,
    ): Sequence<ZonedDateTime> {
        if (!alarm.enabled || !context.groupEnabled) return emptySequence()
        val zone = now.zone
        val time = alarm.time

        // First day on which alarms may ring again; null means "no restriction".
        val vacationActive = context.vacation.enabled && !alarm.ignoreVacation
        val firstAllowedDay: Long? = if (vacationActive) {
            context.vacation.resumeEpochDay ?: return emptySequence() // indefinite vacation
        } else {
            null
        }

        fun at(date: LocalDate): ZonedDateTime = ZonedDateTime.of(date, time, zone)
        fun allowed(date: LocalDate) = firstAllowedDay == null || date.toEpochDay() >= firstAllowedDay

        val pinned = alarm.oneTimeDate
        return when {
            pinned != null && !alarm.isRepeating -> sequence {
                val candidate = at(pinned)
                if (candidate.isAfter(now) && allowed(pinned)) yield(candidate)
            }

            !alarm.isRepeating -> sequence {
                // "Next time the clock shows hh:mm" — a single occurrence, pushed past vacation if needed.
                var date = now.toLocalDate()
                repeat(HORIZON_DAYS) {
                    val candidate = at(date)
                    if (candidate.isAfter(now) && allowed(date)) {
                        yield(candidate)
                        return@sequence
                    }
                    date = date.plusDays(1)
                }
            }

            else -> sequence {
                val today = now.toLocalDate()
                for (offset in 0..HORIZON_DAYS) {
                    val date = today.plusDays(offset.toLong())
                    if (!alarm.repeatsOn(date.dayOfWeek)) continue
                    if (!allowed(date)) continue
                    val epochDay = date.toEpochDay()
                    if (!ignoreSkip && alarm.skipEpochDay == epochDay) continue
                    if (alarm.respectExceptions && epochDay in context.exceptionDays) continue
                    val candidate = at(date)
                    if (candidate.isAfter(now)) yield(candidate)
                }
            }
        }
    }

    private companion object {
        const val HORIZON_DAYS = 400
    }
}
