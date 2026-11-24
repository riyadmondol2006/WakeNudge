package com.riyadm.wakenudge.engine.habits

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.content.edit
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.habits.Habit
import com.riyadm.wakenudge.domain.habits.HabitRepository
import com.riyadm.wakenudge.di.DeviceProtected
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HabitReminderScheduler @Inject constructor(
    @DeviceProtected private val context: Context,
    private val repository: HabitRepository,
    private val time: TimeSource,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val registered = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    suspend fun rescheduleAll() {
        val habits = repository.data().habits
        val previous = registered.getStringSet(KEY_REGISTERED, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        val current = habits.mapTo(hashSetOf()) { it.id }
        (previous - current).forEach(::cancel)
        habits.forEach(::schedule)
    }

    fun schedule(habit: Habit): Boolean {
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(time.nowMillis()), time.zone())
        val next = HabitReminderPlanner.next(habit, now)
        if (next == null) {
            cancel(habit.id)
            return false
        }
        val operation = HabitReminderIntents.reminder(context, habit.id, next.toLocalDate().toEpochDay()) ?: return false
        return try {
            alarms.setWindow(
                AlarmManager.RTC_WAKEUP,
                next.toInstant().toEpochMilli(),
                WINDOW_MILLIS,
                operation,
            )
            markRegistered(habit.id, true)
            true
        } catch (_: RuntimeException) {
            cancel(habit.id)
            false
        }
    }

    fun cancel(habitId: Long) {
        val existing = HabitReminderIntents.reminder(context, habitId, 0, PendingIntent.FLAG_NO_CREATE)
        if (existing != null) {
            runCatching { alarms.cancel(existing) }
            existing.cancel()
        }
        context.getSystemService(android.app.NotificationManager::class.java)
            .cancel(HabitReminderIds.notification(habitId))
        markRegistered(habitId, false)
    }

    private fun markRegistered(habitId: Long, present: Boolean) {
        val current = registered.getStringSet(KEY_REGISTERED, emptySet()).orEmpty().toMutableSet()
        if (present) current += habitId.toString() else current -= habitId.toString()
        registered.edit { putStringSet(KEY_REGISTERED, current) }
    }

    companion object {
        const val WINDOW_MILLIS = 10 * 60 * 1_000L
        private const val PREFERENCES = "habit_reminders"
        private const val KEY_REGISTERED = "registered"
    }
}
