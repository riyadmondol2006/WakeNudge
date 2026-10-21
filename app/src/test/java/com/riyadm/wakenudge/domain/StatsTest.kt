package com.riyadm.wakenudge.domain

import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.domain.stats.WakeStatsCalculator
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {
    private val zone = ZoneId.of("UTC")
    private fun day(d: Int) = LocalDate.of(2026, 10, d).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        d: Int,
        outcome: AlarmOutcome = AlarmOutcome.DISMISSED,
        snoozes: Int = 0,
        durationMin: Long = 2,
        check: WakeCheckStatus = WakeCheckStatus.NONE,
    ) = AlarmEvent(
        alarmId = 1, label = "", scheduledAt = day(d), firedAt = day(d),
        endedAt = if (outcome == AlarmOutcome.RINGING) null else day(d) + durationMin * 60_000,
        outcome = outcome, snoozeCount = snoozes, wakeCheck = check,
    )

    @Test fun `empty history gives zeroed stats`() {
        val s = WakeStatsCalculator.compute(emptyList(), zone)
        assertEquals(0, s.currentStreak)
        assertEquals(0, s.totalWakeUps)
        assertNull(s.averageDismissMs)
        assertEquals(0f, s.successRate, 0f)
    }

    @Test fun `consecutive successful days build the streak`() {
        val s = WakeStatsCalculator.compute((1..5).map { event(it) }, zone)
        assertEquals(5, s.currentStreak)
        assertEquals(5, s.bestStreak)
        assertEquals(1f, s.successRate, 0f)
    }

    @Test fun `a missed alarm breaks the current streak but not the best`() {
        val events = listOf(event(1), event(2), event(3), event(4, AlarmOutcome.MISSED), event(5), event(6))
        val s = WakeStatsCalculator.compute(events, zone)
        assertEquals(2, s.currentStreak)
        assertEquals(3, s.bestStreak)
    }

    @Test fun `emergency stops and failed checks are not successful wake-ups`() {
        val events = listOf(event(1), event(2, AlarmOutcome.EMERGENCY_STOPPED), event(3, check = WakeCheckStatus.FAILED), event(4))
        val s = WakeStatsCalculator.compute(events, zone)
        assertEquals(1, s.currentStreak)
        assertEquals(2, s.successfulWakeUps)
        assertEquals(1, s.checksFailed)
    }

    @Test fun `days without alarms are neutral`() {
        val s = WakeStatsCalculator.compute(listOf(event(1), event(2), event(6), event(7)), zone)
        assertEquals(4, s.currentStreak)
    }

    @Test fun `a day with one failure among several events is a failed day`() {
        val s = WakeStatsCalculator.compute(listOf(event(1), event(2), event(2, AlarmOutcome.MISSED), event(3)), zone)
        assertEquals(1, s.currentStreak)
        assertEquals(1, s.bestStreak)
    }

    @Test fun `ringing events are ignored`() {
        val s = WakeStatsCalculator.compute(listOf(event(1), event(2, AlarmOutcome.RINGING)), zone)
        assertEquals(1, s.totalWakeUps)
        assertEquals(1, s.currentStreak)
    }

    @Test fun `averages cover dismissal time and snoozes`() {
        val s = WakeStatsCalculator.compute(listOf(event(1, snoozes = 2, durationMin = 4), event(2, snoozes = 0, durationMin = 2)), zone)
        assertEquals(3 * 60_000L, s.averageDismissMs)
        assertEquals(1f, s.averageSnoozes, 0f)
    }

    @Test fun `passed checks are counted`() {
        val s = WakeStatsCalculator.compute(listOf(event(1, check = WakeCheckStatus.PASSED), event(2, check = WakeCheckStatus.PASSED)), zone)
        assertEquals(2, s.checksPassed)
    }
}
