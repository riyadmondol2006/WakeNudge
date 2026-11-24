package com.riyadm.wakenudge.domain.habits

import java.time.LocalDate

enum class HabitDayState { DONE, MISSED, NOT_DUE, FUTURE }

object HabitRules {
    fun canToggle(habit: Habit, date: LocalDate, today: LocalDate): Boolean {
        val epochDay = date.toEpochDay()
        return habit.isDueOn(date) &&
            epochDay >= today.minusDays((Habit.RECENT_EDIT_DAYS - 1).toLong()).toEpochDay() &&
            epochDay <= today.toEpochDay()
    }

    fun dayState(habit: Habit, date: LocalDate, today: LocalDate, done: Set<Long>): HabitDayState = when {
        date.isAfter(today) -> HabitDayState.FUTURE
        !habit.isDueOn(date) -> HabitDayState.NOT_DUE
        date.toEpochDay() in done -> HabitDayState.DONE
        else -> HabitDayState.MISSED
    }

    fun completionPercent(habit: Habit, weekStart: LocalDate, today: LocalDate, done: Set<Long>): Int {
        var due = 0
        var completed = 0
        repeat(7) { index ->
            val date = weekStart.plusDays(index.toLong())
            if (!date.isAfter(today) && habit.isDueOn(date)) {
                due++
                if (date.toEpochDay() in done) completed++
            }
        }
        return if (due == 0) 0 else (completed * 100f / due).toInt()
    }
}
