package com.riyadm.wakenudge.domain.habits

import java.time.LocalDate

data class HabitStreak(val current: Int, val best: Int)

object HabitStreakCalculator {
    /**
     * The current schedule is applied to all dates. Non-due dates are skipped, and an unfinished today leaves the
     * previous streak intact; from tomorrow that missed due date starts the count again.
     */
    fun calculate(habit: Habit, done: Set<Long>, today: LocalDate): HabitStreak {
        val created = LocalDate.ofEpochDay(habit.createdEpochDay)
        if (created.isAfter(today)) return HabitStreak(0, 0)
        var current = 0
        var best = 0
        var date = created
        while (!date.isAfter(today)) {
            if (habit.isDueOn(date)) {
                if (date.toEpochDay() in done) {
                    current++
                    if (current > best) best = current
                } else if (date.isBefore(today)) {
                    current = 0
                }
            }
            date = date.plusDays(1)
        }
        return HabitStreak(current, best)
    }
}
