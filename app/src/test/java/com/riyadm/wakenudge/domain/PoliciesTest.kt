package com.riyadm.wakenudge.domain

import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.scheduling.EditLockPolicy
import com.riyadm.wakenudge.domain.scheduling.SleepPlanner
import com.riyadm.wakenudge.domain.scheduling.SnoozePolicy
import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliciesTest {

    @Test fun `snooze limit is enforced and zero means unlimited`() {
        val limited = SnoozeSettings(enabled = true, maxCount = 3)
        assertTrue(SnoozePolicy.canSnooze(limited, 0))
        assertTrue(SnoozePolicy.canSnooze(limited, 2))
        assertFalse(SnoozePolicy.canSnooze(limited, 3))
        assertTrue(SnoozePolicy.canSnooze(limited.copy(maxCount = 0), 500))
        assertFalse(SnoozePolicy.canSnooze(limited.copy(enabled = false), 0))
        assertEquals(1, SnoozePolicy.remaining(limited, 2))
        assertNull(SnoozePolicy.remaining(limited.copy(maxCount = 0), 2))
    }

    @Test fun `decreasing snooze shrinks each time but never below a minute`() {
        val s = SnoozeSettings(minutes = 10, decreasing = true, decreaseStepMinutes = 3)
        assertEquals(listOf(10, 7, 4, 1, 1), (0..4).map { SnoozePolicy.nextIntervalMinutes(s, it) })
        assertEquals(listOf(10, 10, 10), (0..2).map { SnoozePolicy.nextIntervalMinutes(s.copy(decreasing = false), it) })
    }

    @Test fun `snooze minutes are clamped to a sane range`() {
        assertEquals(1, SnoozePolicy.nextIntervalMinutes(SnoozeSettings(minutes = 0), 0))
        assertEquals(60, SnoozePolicy.nextIntervalMinutes(SnoozeSettings(minutes = 500), 0))
    }

    private fun locked(window: Int) = Alarm(id = 5, enabled = true, antiOversleep = AntiOversleepSettings(editLockMinutes = window))

    @Test fun `edit lock engages only inside the protected window`() {
        val now = 1_000_000_000L
        assertNull(EditLockPolicy.lockedMinutes(locked(0), now + 60_000, now, false))
        assertNull(EditLockPolicy.lockedMinutes(locked(15), now + 16 * 60_000, now, false))
        assertEquals(15, EditLockPolicy.lockedMinutes(locked(15), now + 15 * 60_000, now, false))
        assertEquals(1, EditLockPolicy.lockedMinutes(locked(15), now + 30_000, now, false))
        assertNull(EditLockPolicy.lockedMinutes(locked(15), null, now, false))
    }

    @Test fun `edit lock holds while the alarm is ringing or snoozed`() {
        assertEquals(15, EditLockPolicy.lockedMinutes(locked(15), null, 0, activeOrSnoozed = true))
        assertNull(EditLockPolicy.lockedMinutes(locked(0), null, 0, activeOrSnoozed = true))
    }

    @Test fun `sleep planner counts back whole 90 minute cycles plus fall-asleep time`() {
        val options = SleepPlanner.bedtimesFor(LocalTime.of(7, 0), fallAsleepMinutes = 15)
        assertEquals(listOf(6, 5, 4), options.map { it.cycles })
        assertEquals(LocalTime.of(21, 45), options[0].time) // 9 h + 15 min before 07:00
        assertEquals(LocalTime.of(23, 15), options[1].time)
        assertEquals(LocalTime.of(0, 45), options[2].time)
        assertEquals(540, options[0].sleepMinutes)
    }

    @Test fun `sleep planner from bedtime gives the matching wake times`() {
        val options = SleepPlanner.wakeTimesFor(LocalTime.of(23, 0), fallAsleepMinutes = 15, cycles = listOf(5))
        assertEquals(LocalTime.of(6, 45), options.single().time)
    }

    @Test fun `bedtime goal and reminder times wrap around midnight`() {
        assertEquals(LocalTime.of(22, 45), SleepPlanner.bedtimeForGoal(LocalTime.of(7, 0), 8 * 60, 15))
        assertEquals(LocalTime.of(23, 40), SleepPlanner.reminderTime(LocalTime.of(0, 10), 30))
    }

    @Test fun `alarm day masks round-trip`() {
        val mask = Alarm.maskOf(listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY))
        val a = Alarm(repeatDaysMask = mask)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), a.repeatDays)
        assertTrue(a.repeatsOn(DayOfWeek.SUNDAY))
        assertFalse(a.repeatsOn(DayOfWeek.TUESDAY))
        assertEquals(127, Alarm.EVERY_DAY_MASK)
        assertFalse(Alarm().isRepeating)
    }
}
