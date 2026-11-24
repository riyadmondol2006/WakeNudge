package com.riyadm.wakenudge.engine.habits

import com.riyadm.wakenudge.domain.habits.Habit
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HabitReminderPlannerTest {
    private val zone = ZoneId.of("UTC")
    private val monday = LocalDate.of(2026, 11, 16)
    private val habit = Habit(
        id = 1,
        name = "Read",
        createdEpochDay = monday.toEpochDay(),
        daysMask = Habit.EVERY_DAY_MASK,
        reminderMinutes = 7 * 60 + 30,
    )

    @Test fun `next reminder is today when its local time is still ahead`() {
        val now = ZonedDateTime.of(2026, 11, 16, 7, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 16, 7, 30, 0, 0, zone), HabitReminderPlanner.next(habit, now))
    }

    @Test fun `a passed reminder moves to the next due day without catching up`() {
        val now = ZonedDateTime.of(2026, 11, 16, 8, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 17, 7, 30, 0, 0, zone), HabitReminderPlanner.next(habit, now))
    }

    @Test fun `a habit without a reminder has no trigger`() {
        assertNull(HabitReminderPlanner.next(habit.copy(reminderMinutes = null), ZonedDateTime.now(zone)))
    }

    @Test fun `spring forward gap moves by the gap length`() {
        val newYork = ZoneId.of("America/New_York")
        val spring = habit.copy(createdEpochDay = LocalDate.of(2026, 3, 1).toEpochDay(), reminderMinutes = 2 * 60 + 30)
        val now = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, newYork)
        val next = HabitReminderPlanner.next(spring, now)
        assertEquals(ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, newYork), next)
        assertEquals("2026-03-08T07:30:00Z", next?.toInstant().toString())
    }

    @Test fun `fall back overlap chooses the first occurrence`() {
        val newYork = ZoneId.of("America/New_York")
        val autumn = habit.copy(createdEpochDay = LocalDate.of(2026, 10, 1).toEpochDay(), reminderMinutes = 60 + 30)
        val now = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, newYork)
        val next = HabitReminderPlanner.next(autumn, now)
        assertEquals("2026-11-01T05:30:00Z", next?.toInstant().toString())
    }

    @Test fun `reminder follows local wall time after a zone change`() {
        val now = ZonedDateTime.of(2026, 11, 16, 6, 0, 0, 0, ZoneId.of("Asia/Dhaka"))
        assertEquals("2026-11-16T07:30+06:00[Asia/Dhaka]", HabitReminderPlanner.next(habit, now).toString())
        assertEquals("2026-11-16T07:30-08:00[America/Los_Angeles]", HabitReminderPlanner.next(habit, now.withZoneSameInstant(ZoneId.of("America/Los_Angeles")).minusHours(13)).toString())
    }
}
