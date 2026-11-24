package com.riyadm.wakenudge.domain.habits

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HabitStreakCalculatorTest {
    private val start = LocalDate.of(2026, 11, 2)
    private val habit = Habit(id = 1, name = "Study", createdEpochDay = start.toEpochDay())

    private fun done(vararg dates: LocalDate): Set<Long> = dates.mapTo(hashSetOf()) { it.toEpochDay() }

    @Test fun `consecutive due days make the current and best streaks`() {
        val result = HabitStreakCalculator.calculate(habit, done(start, start.plusDays(1), start.plusDays(2)), start.plusDays(2))
        assertEquals(HabitStreak(3, 3), result)
    }

    @Test fun `today not done does not break a streak until tomorrow`() {
        val checked = done(start, start.plusDays(1))
        assertEquals(HabitStreak(2, 2), HabitStreakCalculator.calculate(habit, checked, start.plusDays(2)))
        assertEquals(HabitStreak(0, 2), HabitStreakCalculator.calculate(habit, checked, start.plusDays(3)))
    }

    @Test fun `days not due are skipped rather than counted or treated as breaks`() {
        val weekdays = habit.copy(daysMask = Habit.maskOf(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)))
        val friday = start.plusDays(4)
        val monday = start.plusDays(7)
        val wednesday = start.plusDays(9)
        val result = HabitStreakCalculator.calculate(weekdays, done(start, start.plusDays(2), friday, monday), wednesday)
        assertEquals(HabitStreak(4, 4), result)
        assertEquals(DayOfWeek.WEDNESDAY, wednesday.dayOfWeek)
    }

    @Test fun `a habit created midweek has no history before creation`() {
        val created = habit.copy(createdEpochDay = start.plusDays(3).toEpochDay())
        val result = HabitStreakCalculator.calculate(created, done(start, start.plusDays(3), start.plusDays(4)), start.plusDays(4))
        assertEquals(HabitStreak(2, 2), result)
    }

    @Test fun `best streak survives a later missed due day`() {
        val checked = done(start, start.plusDays(1), start.plusDays(2), start.plusDays(5), start.plusDays(6))
        val result = HabitStreakCalculator.calculate(habit, checked, start.plusDays(6))
        assertEquals(HabitStreak(2, 3), result)
    }

    @Test fun `unticking a past day recalculates current and best streaks`() {
        val dates = listOf(start, start.plusDays(1), start.plusDays(2))
        val before = HabitStreakCalculator.calculate(habit, done(*dates.toTypedArray()), start.plusDays(2))
        val after = HabitStreakCalculator.calculate(habit, done(start, start.plusDays(2)), start.plusDays(2))
        assertEquals(HabitStreak(3, 3), before)
        assertEquals(HabitStreak(1, 1), after)
    }

    @Test fun `editing days applies the current schedule to the whole history`() {
        val tuesdayThursday = Habit.maskOf(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY))
        val edited = habit.copy(daysMask = tuesdayThursday)
        val checked = done(start, start.plusDays(1), start.plusDays(2), start.plusDays(3))
        val result = HabitStreakCalculator.calculate(edited, checked, start.plusDays(3))
        assertEquals(HabitStreak(2, 2), result)
    }

    @Test fun `dst change days remain separate local days`() {
        val zone = ZoneId.of("America/New_York")
        val beforeGap = Instant.parse("2026-03-08T06:30:00Z").atZone(zone).toLocalDate()
        val afterGap = Instant.parse("2026-03-09T04:30:00Z").atZone(zone).toLocalDate()
        val dstHabit = habit.copy(createdEpochDay = beforeGap.toEpochDay())
        val result = HabitStreakCalculator.calculate(dstHabit, done(beforeGap, afterGap), afterGap)
        assertEquals(LocalDate.of(2026, 3, 8), beforeGap)
        assertEquals(LocalDate.of(2026, 3, 9), afterGap)
        assertEquals(HabitStreak(2, 2), result)
    }

    @Test fun `a zone change uses the current local calendar date without duplicating checkins`() {
        val instant = Instant.parse("2026-11-16T18:30:00Z")
        val dhaka = instant.atZone(ZoneId.of("Asia/Dhaka")).toLocalDate()
        val losAngeles = instant.atZone(ZoneId.of("America/Los_Angeles")).toLocalDate()
        val zonedHabit = habit.copy(createdEpochDay = dhaka.minusDays(1).toEpochDay())
        assertEquals(LocalDate.of(2026, 11, 17), dhaka)
        assertEquals(LocalDate.of(2026, 11, 16), losAngeles)
        assertEquals(HabitStreak(2, 2), HabitStreakCalculator.calculate(zonedHabit, done(dhaka.minusDays(1), dhaka), dhaka))
        assertEquals(HabitStreak(1, 1), HabitStreakCalculator.calculate(zonedHabit, done(dhaka.minusDays(1), dhaka), losAngeles))
    }
}
