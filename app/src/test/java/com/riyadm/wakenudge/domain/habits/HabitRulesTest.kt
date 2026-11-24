package com.riyadm.wakenudge.domain.habits

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitRulesTest {
    private val monday = LocalDate.of(2026, 11, 16)
    private val habit = Habit(id = 1, name = "Read", createdEpochDay = monday.toEpochDay())

    @Test fun `only today and the previous six days can be changed`() {
        assertTrue(HabitRules.canToggle(habit, monday, monday.plusDays(6)))
        assertTrue(HabitRules.canToggle(habit, monday.plusDays(6), monday.plusDays(6)))
        assertFalse(HabitRules.canToggle(habit, monday.minusDays(1), monday.plusDays(6)))
        assertFalse(HabitRules.canToggle(habit, monday, monday.plusDays(7)))
        assertFalse(HabitRules.canToggle(habit, monday.plusDays(7), monday.plusDays(6)))
    }

    @Test fun `a day the habit is not due cannot be checked`() {
        val mondays = habit.copy(daysMask = Habit.maskOf(setOf(DayOfWeek.MONDAY)))
        assertFalse(HabitRules.canToggle(mondays, monday.plusDays(1), monday.plusDays(6)))
    }

    @Test fun `creation is the earliest editable day even inside the recent window`() {
        val created = monday.plusDays(4)
        val recent = habit.copy(createdEpochDay = created.toEpochDay())
        assertFalse(HabitRules.canToggle(recent, created.minusDays(1), monday.plusDays(6)))
        assertTrue(HabitRules.canToggle(recent, created, monday.plusDays(6)))
    }

    @Test fun `report days distinguish done missed not due and future`() {
        val weekdays = habit.copy(daysMask = Habit.maskOf(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)))
        val today = monday.plusDays(2)
        val done = setOf(monday.toEpochDay())
        assertEquals(HabitDayState.DONE, HabitRules.dayState(weekdays, monday, today, done))
        assertEquals(HabitDayState.NOT_DUE, HabitRules.dayState(weekdays, monday.plusDays(1), today, done))
        assertEquals(HabitDayState.OPEN, HabitRules.dayState(weekdays, today, today, done))
        assertEquals(HabitDayState.FUTURE, HabitRules.dayState(weekdays, monday.plusDays(4), today, done))
        assertEquals(HabitDayState.MISSED, HabitRules.dayState(weekdays, today, today.plusDays(1), done))
    }

    @Test fun `today is open until it is done, and only a past day can be missed`() {
        val today = monday.plusDays(1)
        assertEquals(HabitDayState.OPEN, HabitRules.dayState(habit, today, today, emptySet()))
        assertEquals(HabitDayState.DONE, HabitRules.dayState(habit, today, today, setOf(today.toEpochDay())))
        assertEquals(HabitDayState.MISSED, HabitRules.dayState(habit, monday, today, emptySet()))
    }

    @Test fun `days before creation are not due or missed`() {
        val created = habit.copy(createdEpochDay = monday.plusDays(3).toEpochDay())
        assertEquals(HabitDayState.NOT_DUE, HabitRules.dayState(created, monday, monday.plusDays(6), emptySet()))
    }
}
