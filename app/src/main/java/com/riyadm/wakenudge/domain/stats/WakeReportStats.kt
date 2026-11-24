package com.riyadm.wakenudge.domain.stats

import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** How one calendar day ended up, for the wake-up report. */
enum class WakeDayKind {
    /** At least one ring in the morning window ended in a real dismissal. */
    WAKE_UP,

    /** Rings happened in the morning window, but none was dismissed (missed and/or emergency-stopped). */
    NO_WAKE_UP,

    /** A ring in the morning window is still going; the day is not decided yet. */
    STILL_RINGING,

    /** No counted ring was assigned to this day. */
    EMPTY,
}

/** The wake-up of one morning: the earliest dismissed morning ring of its day. */
data class MorningWakeUp(
    val eventId: Long,
    val alarmId: Long,
    val label: String,
    /** Epoch millis of the first ring of this occurrence, before any snooze or backup ring. */
    val firstRingAtMillis: Long,
    /** Epoch millis of the dismissal. */
    val wakeUpAtMillis: Long,
    /** Elapsed time from the first ring to the dismissal (snoozes and backup rings included). */
    val timeToWakeMs: Long,
    val snoozeCount: Int,
    val missionsDone: Int,
    val missionsTotal: Int,
    val wakeCheck: WakeCheckStatus,
    val backupFired: Boolean,
    val fallbackUsed: Boolean,
    val firstRingTime: LocalTime,
    val wakeUpTime: LocalTime,
)

/** One calendar day in the wake-up report. */
data class WakeDayReport(
    val date: LocalDate,
    val kind: WakeDayKind,
    /** All counted rings whose first ring falls on [date], earliest first. Ringing ones are included. */
    val events: List<AlarmEvent>,
    val wakeUp: MorningWakeUp? = null,
    /** Earliest counted ring of the day at any hour, for markers on days without a wake-up. */
    val firstRingTime: LocalTime? = null,
    val hasMissed: Boolean = false,
    val hasEmergencyStop: Boolean = false,
    val hasRinging: Boolean = false,
) {
    val isWakeUp: Boolean get() = kind == WakeDayKind.WAKE_UP
}

/** Time axis for the week chart, in minutes of day; values may leave 0..1439 when the axis wraps past midnight. */
data class WakeChartScale(
    val startMinute: Double,
    val endMinute: Double,
    val tickMinutes: List<Double>,
    /** The average wake-up time placed on this axis, or null when there is no average or it does not fit. */
    val averageMinute: Double?,
) {
    val spanMinute: Double get() = endMinute - startMinute

    /** 0 at the top of the chart ([endMinute]), 1 at the bottom ([startMinute]). */
    fun fractionOf(minuteOfDay: Double): Float {
        if (spanMinute <= 0.0) return 0.5f
        var m = minuteOfDay
        // Bring a clock time onto a wrapped axis (e.g. 00:10 onto an axis that runs 23:00..01:00).
        while (m < startMinute - 12 * 60) m += 24 * 60
        while (m > endMinute + 12 * 60) m -= 24 * 60
        return ((endMinute - m) / spanMinute).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        /** An axis shown when a week has no morning to plot. */
        val DEFAULT = WakeChartScale(6 * 60.0, 10 * 60.0, listOf(6 * 60.0, 8 * 60.0, 10 * 60.0), null)
    }
}

/** A whole week of wake-ups, starting on [start] (the user's first day of week). */
data class WeekWakeReport(
    val start: LocalDate,
    val days: List<WakeDayReport>,
    /** Circular mean of the wake-up clock times; null when the week has no wake-up. */
    val averageWakeUpTime: LocalTime?,
    /** Mean elapsed time from first ring to dismissal; null when the week has no wake-up. */
    val averageTimeToWakeMs: Long?,
    val chartScale: WakeChartScale,
) {
    val end: LocalDate get() = start.plusDays(6)
    val wakeUpCount: Int get() = days.count { it.isWakeUp }
}

/**
 * Wake-up statistics for the Report tab, counted only from the alarm history on this phone.
 *
 * A morning is one calendar day. Its wake-up is the earliest ring of that day that fired between 03:00 and
 * 15:00 local time and ended in a real dismissal ([AlarmOutcome.DISMISSED] with an end time). The wake-up
 * time is when it was dismissed; the time to wake up runs from that occurrence's first ring to the
 * dismissal, so snoozes and backup rings of the same occurrence count towards it. Days whose morning rings
 * were only missed or emergency-stopped are not wake-ups. Timers and test alarms never reach the history,
 * and are filtered out here as well (their alarm ids are not real alarm ids).
 *
 * All day and clock-time maths goes through [zoneAt], the zone in force at each ring, so a time-zone change
 * mid-week and DST days keep each ring on the day and at the local time where it really rang. Durations are
 * always elapsed epoch time, never wall-clock subtraction.
 */
object WakeReportCalculator {

    /** Morning window for a day's first ring, local time: [03:00, 15:00). */
    val WINDOW_START: LocalTime = LocalTime.of(3, 0)
    val WINDOW_END: LocalTime = LocalTime.of(15, 0)

    /** The first day of the week containing [date], when weeks start on [firstDayOfWeek]. */
    fun weekStart(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate {
        val shift = (date.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        return date.minusDays(shift.toLong())
    }

    fun dayReport(events: List<AlarmEvent>, date: LocalDate, zone: ZoneId): WakeDayReport =
        dayReport(events, date) { zone }

    fun dayReport(events: List<AlarmEvent>, date: LocalDate, zoneAt: (Long) -> ZoneId): WakeDayReport {
        val counted = countedEvents(events)
        val dayEvents = counted
            .filter { localDateOf(it.firedAt, zoneAt) == date }
            .sortedWith(compareBy({ it.firedAt }, { it.id }))
        return buildDay(date, dayEvents, zoneAt)
    }

    fun weekReport(events: List<AlarmEvent>, start: LocalDate, zone: ZoneId): WeekWakeReport =
        weekReport(events, start) { zone }

    fun weekReport(events: List<AlarmEvent>, start: LocalDate, zoneAt: (Long) -> ZoneId): WeekWakeReport {
        val counted = countedEvents(events)
        val byDate = counted.groupBy { localDateOf(it.firedAt, zoneAt) }
        val days = (0L..6L).map { offset ->
            val date = start.plusDays(offset)
            buildDay(date, byDate[date].orEmpty().sortedWith(compareBy({ it.firedAt }, { it.id })), zoneAt)
        }
        val wakeUps = days.mapNotNull { it.wakeUp }
        val averageTime = averageClockTime(wakeUps.map { it.wakeUpTime })
        val averageDuration = wakeUps.map { it.timeToWakeMs }.takeIf { it.isNotEmpty() }?.average()?.roundToLong()
        return WeekWakeReport(
            start = start,
            days = days,
            averageWakeUpTime = averageTime,
            averageTimeToWakeMs = averageDuration,
            chartScale = chartScale(days, averageTime),
        )
    }

    /**
     * Circular mean of clock times: 23:50 and 00:10 average to 00:00, not to noon. Returns null for no
     * times, and for times so opposed (e.g. 00:00 and 12:00) that no mean direction exists.
     */
    fun averageClockTime(times: List<LocalTime>): LocalTime? {
        if (times.isEmpty()) return null
        var x = 0.0
        var y = 0.0
        for (time in times) {
            val angle = 2.0 * PI * (time.toSecondOfDay() / 86_400.0)
            x += cos(angle)
            y += sin(angle)
        }
        x /= times.size
        y /= times.size
        if (sqrt(x * x + y * y) < 1e-9) return null
        var angle = atan2(y, x)
        if (angle < 0) angle += 2.0 * PI
        val minute = (angle / (2.0 * PI) * 1440.0).roundToLong().mod(1440L)
        return LocalTime.of((minute / 60).toInt(), (minute % 60).toInt())
    }

    /** A time axis that fits this week's first rings and wake-ups, with hour ticks and sensible limits. */
    fun chartScale(days: List<WakeDayReport>, averageWakeUpTime: LocalTime?): WakeChartScale {
        val wakeUps = days.mapNotNull { it.wakeUp }
        if (wakeUps.isEmpty()) return WakeChartScale.DEFAULT
        val raw = wakeUps.flatMap { listOf(minuteOfDay(it.firstRingTime), minuteOfDay(it.wakeUpTime)) }
        // Unwrap an axis that crosses midnight: early-morning times join the late-evening ones past 24:00.
        val wraps = raw.any { it >= 18 * 60 } && raw.any { it <= 6 * 60 }
        val points = raw.map { if (wraps && it < 12 * 60) it + 24 * 60 else it }
        var start = points.min()
        var end = points.max()
        val pad = maxOf(30.0, (end - start) * 0.15)
        start -= pad
        end += pad
        if (end - start < MIN_SPAN_MINUTES) {
            val mid = (start + end) / 2.0
            start = mid - MIN_SPAN_MINUTES / 2.0
            end = mid + MIN_SPAN_MINUTES / 2.0
        }
        if (!wraps) {
            start = start.coerceAtLeast(0.0)
            end = end.coerceAtMost(24 * 60.0)
            if (end - start < MIN_SPAN_MINUTES) {
                // Re-expand inside the day after clamping.
                val mid = ((start + end) / 2.0).coerceIn(MIN_SPAN_MINUTES / 2.0, 24 * 60 - MIN_SPAN_MINUTES / 2.0)
                start = mid - MIN_SPAN_MINUTES / 2.0
                end = mid + MIN_SPAN_MINUTES / 2.0
            }
        }
        start = kotlin.math.floor(start / 60.0) * 60.0
        end = kotlin.math.ceil(end / 60.0) * 60.0
        val step = when {
            end - start <= 240 -> 60.0
            end - start <= 720 -> 120.0
            else -> 180.0
        }
        val ticks = generateSequence(start) { it + step }.takeWhile { it <= end + 0.001 }.toList()
        val averageMinute = averageWakeUpTime?.let { avg ->
            var m = minuteOfDay(avg)
            if (wraps && m < 12 * 60) m += 24 * 60
            m.takeIf { it in start..end }
        }
        return WakeChartScale(start, end, ticks, averageMinute)
    }

    /** Real alarms only: test alarms and timers use reserved non-positive ids and never count. */
    fun countedEvents(events: List<AlarmEvent>): List<AlarmEvent> = events.filter { it.alarmId > 0 }

    private fun buildDay(date: LocalDate, dayEvents: List<AlarmEvent>, zoneAt: (Long) -> ZoneId): WakeDayReport {
        val occurrences = occurrencesOf(dayEvents)
        val morning = occurrences.filter { occ ->
            val firstRingTime = localTimeOf(occ.firstRingAtMillis, zoneAt)
            firstRingTime >= WINDOW_START && firstRingTime < WINDOW_END
        }
        val wakeOccurrence = morning
            .filter { it.wakeUpAtMillis != null }
            .minWithOrNull(compareBy({ it.firstRingAtMillis }, { it.representative.id }))
        val wakeUp = wakeOccurrence?.let { occ ->
            val wakeAt = occ.wakeUpAtMillis!!
            MorningWakeUp(
                eventId = occ.representative.id,
                alarmId = occ.representative.alarmId,
                label = occ.representative.label,
                firstRingAtMillis = occ.firstRingAtMillis,
                wakeUpAtMillis = wakeAt,
                timeToWakeMs = (wakeAt - occ.firstRingAtMillis).coerceAtLeast(0),
                snoozeCount = occ.members.maxOf { it.snoozeCount.coerceAtLeast(0) },
                missionsDone = occ.members.maxOf { it.missionsDone.coerceAtLeast(0) },
                missionsTotal = occ.members.maxOf { it.missionsTotal.coerceAtLeast(0) },
                wakeCheck = occ.members.firstOrNull { it.id == occ.dismissedEventId }?.wakeCheck
                    ?: occ.representative.wakeCheck,
                backupFired = occ.members.any { it.backupFired },
                fallbackUsed = occ.members.any { it.fallbackUsed },
                firstRingTime = localTimeOf(occ.firstRingAtMillis, zoneAt),
                wakeUpTime = localTimeOf(wakeAt, zoneAt),
            )
        }
        val hasMissed = morning.any { occ -> occ.members.any { it.outcome == AlarmOutcome.MISSED } }
        val hasEmergency = morning.any { occ -> occ.members.any { it.outcome == AlarmOutcome.EMERGENCY_STOPPED } }
        val hasRinging = morning.any { occ -> occ.members.any { it.outcome == AlarmOutcome.RINGING } }
        val kind = when {
            wakeUp != null -> WakeDayKind.WAKE_UP
            hasRinging -> WakeDayKind.STILL_RINGING
            morning.isNotEmpty() -> WakeDayKind.NO_WAKE_UP
            else -> WakeDayKind.EMPTY
        }
        return WakeDayReport(
            date = date,
            kind = kind,
            events = dayEvents,
            wakeUp = wakeUp,
            firstRingTime = dayEvents.minOfOrNull { it.firedAt }?.let { localTimeOf(it, zoneAt) },
            hasMissed = hasMissed,
            hasEmergencyStop = hasEmergency,
            hasRinging = hasRinging,
        )
    }

    /** One occurrence of an alarm: rows sharing alarm and scheduled time are one ring, however they were stored. */
    private data class Occurrence(
        val representative: AlarmEvent,
        val members: List<AlarmEvent>,
        val firstRingAtMillis: Long,
        val wakeUpAtMillis: Long?,
        val dismissedEventId: Long?,
    )

    private fun occurrencesOf(dayEvents: List<AlarmEvent>): List<Occurrence> =
        dayEvents
            .groupBy { event -> if (event.scheduledAt > 0) event.alarmId to event.scheduledAt else event.alarmId to -event.id }
            .values
            .map { members ->
                val sorted = members.sortedWith(compareBy({ it.firedAt }, { it.id }))
                val dismissed = sorted.filter { it.outcome == AlarmOutcome.DISMISSED && it.endedAt != null }
                Occurrence(
                    representative = sorted.first(),
                    members = sorted,
                    firstRingAtMillis = sorted.minOf { it.firedAt },
                    wakeUpAtMillis = dismissed.mapNotNull { it.endedAt }.maxOrNull(),
                    dismissedEventId = dismissed.maxByOrNull { it.endedAt ?: Long.MIN_VALUE }?.id,
                )
            }

    private fun localDateOf(millis: Long, zoneAt: (Long) -> ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zoneAt(millis)).toLocalDate()

    private fun localTimeOf(millis: Long, zoneAt: (Long) -> ZoneId) =
        Instant.ofEpochMilli(millis).atZone(zoneAt(millis)).toLocalTime()

    private fun minuteOfDay(time: LocalTime): Double = time.hour * 60.0 + time.minute + time.second / 60.0

    private const val MIN_SPAN_MINUTES = 120.0
}
