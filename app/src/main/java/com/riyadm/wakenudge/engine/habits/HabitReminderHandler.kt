package com.riyadm.wakenudge.engine.habits

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.habits.HabitRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HabitReminderHandler @Inject constructor(
    private val repository: HabitRepository,
    private val scheduler: HabitReminderScheduler,
    private val notifier: HabitNotifier,
    private val time: TimeSource,
) {
    suspend fun remind(habitId: Long, epochDay: Long) {
        val habit = repository.habit(habitId)
        if (habit == null) {
            scheduler.cancel(habitId)
            return
        }
        val today = time.now().toLocalDate()
        val current = epochDay == today.toEpochDay() && habit.isDueOn(today) && habit.reminderMinutes != null
        val alreadyDone = current && repository.data().doneDates(habitId).contains(epochDay)
        if (current && !alreadyDone) notifier.show(habit, epochDay)
        scheduler.schedule(habit)
    }

    suspend fun done(habitId: Long, epochDay: Long) {
        val date = runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull()
        if (date != null) repository.setDone(habitId, date, true, time.now().toLocalDate())
        notifier.cancel(habitId)
    }
}
