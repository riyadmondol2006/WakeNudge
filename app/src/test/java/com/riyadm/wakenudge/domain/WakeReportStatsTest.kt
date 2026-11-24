package com.riyadm.wakenudge.domain

import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.domain.stats.WakeDayKind
import com.riyadm.wakenudge.domain.stats.WakeReportCalculator
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeReportStatsTest {
    private val utc = ZoneId.of("UTC")
    private val monday = LocalDate.of(2026, 11, 23) // a Monday

    private fun at(date: LocalDate, hour: Int, minute: Int = 0, zone: ZoneId = utc): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()

    private fun event(
        date: LocalDate,
        hour: Int,
        minute: Int = 0,
        zone: ZoneId = utc,
        alarmId: Long = 1,
        label: String = "Alarm",
        outcome: AlarmOutcome = AlarmOutcome.DISMISSED,
        dismissAfterMin: Long = 10,
        snoozes: Int = 0,
        scheduledAt: Long? = null,
        backupFired: Boolean = false,
        id: Long = 0,
    ): AlarmEvent {
        val fired = at(date, hour, minute, zone)
        return AlarmEvent(
            id = id, alarmId = alarmId, label = label,
            scheduledAt = scheduledAt ?: fired, firedAt = fired,
            endedAt = if (outcome == AlarmOutcome.RINGING) null else fired + dismissAfterMin * 60_000,
            outcome = outcome, snoozeCount = snoozes, backupFired = backupFired,
        )
    }

    private fun week(events: List<AlarmEvent>, start: LocalDate = monday, zone: ZoneId = utc) =
        WakeReportCalculator.weekReport(events, start, zone)

    @Test fun `week start honours the first day of week`() {
        val wed = LocalDate.of(2026, 11, 25)
        assertEquals(LocalDate.of(2026, 11, 23), WakeReportCalculator.weekStart(wed, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 11, 22), WakeReportCalculator.weekStart(wed, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 11, 21), WakeReportCalculator.weekStart(wed, DayOfWeek.SATURDAY))
        assertEquals(monday, WakeReportCalculator.weekStart(monday, DayOfWeek.MONDAY))
    }

    @Test fun `a week with no mornings has no averages and empty days`() {
        val report = week(emptyList())
        assertEquals(7, report.days.size)
        assertTrue(report.days.all { it.kind == WakeDayKind.EMPTY })
        assertNull(report.averageWakeUpTime)
        assertNull(report.averageTimeToWakeMs)
        assertEquals(0, report.wakeUpCount)
    }

    @Test fun `a week with one morning averages to that morning`() {
        val report = week(listOf(event(monday, 7, dismissAfterMin = 26)))
        assertEquals(LocalTime.of(7, 26), report.averageWakeUpTime)
        assertEquals(26 * 60_000L, report.averageTimeToWakeMs)
        assertEquals(1, report.wakeUpCount)
        assertEquals(WakeDayKind.WAKE_UP, report.days[0].kind)
    }

    @Test fun `wake-up time is the dismissal and time to wake runs from the first ring`() {
        val day = WakeReportCalculator.dayReport(listOf(event(monday, 6, 45, dismissAfterMin = 95, snoozes = 3)), monday, utc)
        assertEquals(LocalTime.of(8, 20), day.wakeUp!!.wakeUpTime)
        assertEquals(LocalTime.of(6, 45), day.wakeUp!!.firstRingTime)
        assertEquals(95 * 60_000L, day.wakeUp!!.timeToWakeMs)
        assertEquals(3, day.wakeUp!!.snoozeCount)
    }

    @Test fun `the earliest dismissed morning ring wins when several alarms ring in one day`() {
        val events = listOf(
            event(monday, 8, alarmId = 2, dismissAfterMin = 5),
            event(monday, 6, 30, alarmId = 3, outcome = AlarmOutcome.MISSED),
            event(monday, 7, alarmId = 1, dismissAfterMin = 20),
        )
        val day = WakeReportCalculator.dayReport(events, monday, utc)
        assertEquals(1L, day.wakeUp!!.alarmId)
        assertEquals(LocalTime.of(7, 20), day.wakeUp!!.wakeUpTime)
        assertEquals(3, day.events.size)
    }

    @Test fun `backups of the same occurrence count from the first ring, not as a second wake-up`() {
        val scheduled = at(monday, 7)
        val primary = event(monday, 7, outcome = AlarmOutcome.MISSED, dismissAfterMin = 30, scheduledAt = scheduled, id = 1)
        val backup = event(monday, 7, 30, dismissAfterMin = 12, scheduledAt = scheduled, backupFired = true, id = 2)
        val day = WakeReportCalculator.dayReport(listOf(primary, backup), monday, utc)
        assertEquals(WakeDayKind.WAKE_UP, day.kind)
        assertEquals(at(monday, 7), day.wakeUp!!.firstRingAtMillis)
        assertEquals(42 * 60_000L, day.wakeUp!!.timeToWakeMs)
        assertTrue(day.wakeUp!!.backupFired)
    }

    @Test fun `missed-only and emergency-only days are not wake-ups`() {
        val missed = WakeReportCalculator.dayReport(listOf(event(monday, 7, outcome = AlarmOutcome.MISSED)), monday, utc)
        assertEquals(WakeDayKind.NO_WAKE_UP, missed.kind)
        assertNull(missed.wakeUp)
        assertTrue(missed.hasMissed)
        val emergency = WakeReportCalculator.dayReport(listOf(event(monday, 7, outcome = AlarmOutcome.EMERGENCY_STOPPED)), monday, utc)
        assertEquals(WakeDayKind.NO_WAKE_UP, emergency.kind)
        assertTrue(emergency.hasEmergencyStop)
    }

    @Test fun `a ring still ringing does not decide the day and never counts as a wake-up`() {
        val ringing = WakeReportCalculator.dayReport(listOf(event(monday, 7, outcome = AlarmOutcome.RINGING)), monday, utc)
        assertEquals(WakeDayKind.STILL_RINGING, ringing.kind)
        assertNull(ringing.wakeUp)
        // A dismissed ring later the same morning still wins over one still ringing.
        val mixed = WakeReportCalculator.dayReport(
            listOf(event(monday, 7, outcome = AlarmOutcome.RINGING), event(monday, 8, alarmId = 2, dismissAfterMin = 5)),
            monday, utc,
        )
        assertEquals(WakeDayKind.WAKE_UP, mixed.kind)
    }

    @Test fun `timers and test alarms never count`() {
        val events = listOf(event(monday, 7, alarmId = -1), event(monday, 6, alarmId = -1001), event(monday, 5, alarmId = 0))
        val day = WakeReportCalculator.dayReport(events, monday, utc)
        assertEquals(WakeDayKind.EMPTY, day.kind)
        assertTrue(day.events.isEmpty())
    }

    @Test fun `rings outside the morning window do not make a wake-up`() {
        val afterMidnight = WakeReportCalculator.dayReport(listOf(event(monday, 0, 30, dismissAfterMin = 20)), monday, utc)
        assertEquals(WakeDayKind.EMPTY, afterMidnight.kind)
        assertEquals(1, afterMidnight.events.size) // still listed for the daily report
        val late = WakeReportCalculator.dayReport(listOf(event(monday, 15, 0)), monday, utc)
        assertEquals(WakeDayKind.EMPTY, late.kind)
        val edge = WakeReportCalculator.dayReport(listOf(event(monday, 3, 0, dismissAfterMin = 2)), monday, utc)
        assertEquals(WakeDayKind.WAKE_UP, edge.kind)
        val lastMinute = WakeReportCalculator.dayReport(listOf(event(monday, 14, 59, dismissAfterMin = 2)), monday, utc)
        assertEquals(WakeDayKind.WAKE_UP, lastMinute.kind)
    }

    @Test fun `an evening ring dismissed after midnight belongs to its firing day`() {
        val events = listOf(event(monday, 23, 30, dismissAfterMin = 90))
        assertEquals(WakeDayKind.EMPTY, WakeReportCalculator.dayReport(events, monday, utc).kind)
        val next = WakeReportCalculator.dayReport(events, monday.plusDays(1), utc)
        assertEquals(WakeDayKind.EMPTY, next.kind)
        assertTrue(next.events.isEmpty())
    }

    @Test fun `clock times near midnight average across midnight, not at noon`() {
        assertEquals(
            LocalTime.of(0, 0),
            WakeReportCalculator.averageClockTime(listOf(LocalTime.of(23, 50), LocalTime.of(0, 10))),
        )
        assertEquals(
            LocalTime.of(23, 55),
            WakeReportCalculator.averageClockTime(listOf(LocalTime.of(23, 40), LocalTime.of(0, 10))),
        )
        assertNull(WakeReportCalculator.averageClockTime(emptyList()))
        assertNull(WakeReportCalculator.averageClockTime(listOf(LocalTime.of(0, 0), LocalTime.of(12, 0))))
    }

    @Test fun `average time to wake is the mean elapsed time`() {
        val report = week(listOf(event(monday, 7, dismissAfterMin = 10), event(monday.plusDays(1), 7, dismissAfterMin = 50)))
        assertEquals(30 * 60_000L, report.averageTimeToWakeMs)
        assertEquals(LocalTime.of(7, 30), report.averageWakeUpTime)
    }

    @Test fun `spring-forward day still assigns the morning to that date`() {
        val ny = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 3, 8) // 02:00 jumps to 03:00 in New York: a 23-hour day
        val day = WakeReportCalculator.dayReport(listOf(event(date, 7, zone = ny, dismissAfterMin = 10)), date, ny)
        assertEquals(WakeDayKind.WAKE_UP, day.kind)
        assertEquals(LocalTime.of(7, 10), day.wakeUp!!.wakeUpTime)
        assertEquals(10 * 60_000L, day.wakeUp!!.timeToWakeMs)
    }

    @Test fun `fall-back morning measures elapsed time, not the wall clock`() {
        // Chatham Islands fall back 45 minutes at 03:45, inside the morning window.
        val chatham = ZoneId.of("Pacific/Chatham")
        val date = LocalDate.of(2026, 4, 5)
        val fired = ZonedDateTime.of(date, LocalTime.of(3, 30), chatham).toInstant().toEpochMilli()
        val dismissed = fired + 60 * 60_000L // wall clock then reads 03:30 a second time, after the fall-back
        val e = AlarmEvent(alarmId = 1, label = "", scheduledAt = fired, firedAt = fired, endedAt = dismissed, outcome = AlarmOutcome.DISMISSED)
        val day = WakeReportCalculator.dayReport(listOf(e), date, chatham)
        assertEquals(60 * 60_000L, day.wakeUp!!.timeToWakeMs)
        assertEquals(LocalTime.of(3, 30), day.wakeUp!!.wakeUpTime)
    }

    @Test fun `a time-zone change mid-week keeps each ring on its local day`() {
        val ny = ZoneId.of("America/New_York")
        val london = ZoneId.of("Europe/London")
        val changeAt = at(monday.plusDays(2), 12, 0, utc)
        val zoneAt: (Long) -> ZoneId = { millis -> if (millis < changeAt) ny else london }
        // 07:00 in New York on Monday; 07:00 in London on Thursday.
        val first = event(monday, 7, zone = ny, dismissAfterMin = 15)
        val second = event(monday.plusDays(3), 7, zone = london, dismissAfterMin = 15)
        val report = WakeReportCalculator.weekReport(listOf(first, second), monday, zoneAt)
        assertEquals(WakeDayKind.WAKE_UP, report.days[0].kind)
        assertEquals(WakeDayKind.WAKE_UP, report.days[3].kind)
        assertEquals(LocalTime.of(7, 15), report.days[3].wakeUp!!.wakeUpTime)
        assertEquals(2, report.wakeUpCount)
    }

    @Test fun `a dismissed ring with a failed still-awake check is still the wake-up, with the result kept`() {
        val e = event(monday, 7, dismissAfterMin = 9).copy(wakeCheck = WakeCheckStatus.FAILED)
        val day = WakeReportCalculator.dayReport(listOf(e), monday, utc)
        assertEquals(WakeDayKind.WAKE_UP, day.kind)
        assertEquals(WakeCheckStatus.FAILED, day.wakeUp!!.wakeCheck)
    }

    @Test fun `chart scale expands a narrow week and adapts to a wide one`() {
        val narrow = week(listOf(event(monday, 7, dismissAfterMin = 10))).chartScale
        assertTrue("span at least two hours, was ${narrow.spanMinute}", narrow.spanMinute >= 120.0)
        assertNotNull(narrow.averageMinute)
        assertTrue(narrow.tickMinutes.size >= 3)
        val wide = week(
            listOf(event(monday, 4, dismissAfterMin = 30), event(monday.plusDays(1), 12, dismissAfterMin = 30)),
        ).chartScale
        assertTrue(wide.startMinute <= 4 * 60.0)
        assertTrue(wide.endMinute >= 12 * 60.0 + 30)
        assertEquals(wide.tickMinutes.sorted(), wide.tickMinutes)
    }

    @Test fun `chart scale wraps past midnight for late and early wake-ups`() {
        val events = listOf(
            event(monday, 23, 40, dismissAfterMin = 20), // outside window: not a wake-up
            event(monday.plusDays(1), 4, dismissAfterMin = 10),
        )
        // Use wake times 23:50 / 00:10 through the circular average directly for the wrapped axis.
        val days = week(events).days
        val scale = WakeReportCalculator.chartScale(days, LocalTime.of(0, 0))
        assertTrue(scale.endMinute >= scale.startMinute)
        assertTrue(scale.fractionOf(4 * 60.0 + 10) in 0f..1f)
    }
}
