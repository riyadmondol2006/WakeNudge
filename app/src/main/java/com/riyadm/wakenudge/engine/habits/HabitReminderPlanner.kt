package com.riyadm.wakenudge.engine.habits

import com.riyadm.wakenudge.domain.habits.Habit
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

object HabitReminderPlanner {
    fun next(habit: Habit, now: ZonedDateTime): ZonedDateTime? {
        val minutes = habit.reminderMinutes ?: return null
        val time = LocalTime.ofSecondOfDay((minutes.coerceIn(0, 1_439) * 60).toLong())
        val firstDate = maxOf(now.toLocalDate(), LocalDate.ofEpochDay(habit.createdEpochDay))
        repeat(8) { offset ->
            val date = firstDate.plusDays(offset.toLong())
            if (habit.isDueOn(date)) {
                val candidate = date.atTime(time).atZone(now.zone)
                if (candidate.toInstant().isAfter(now.toInstant())) return candidate
            }
        }
        return null
    }
}
