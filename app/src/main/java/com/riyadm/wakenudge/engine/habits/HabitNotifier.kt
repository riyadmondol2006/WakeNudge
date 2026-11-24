package com.riyadm.wakenudge.engine.habits

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.habits.Habit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HabitNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun canShow(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun show(habit: Habit, epochDay: Long) {
        ensureChannel()
        if (!canShow()) return
        val open = HabitReminderIntents.open(context, habit.id)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_habit)
            .setContentTitle(context.getString(R.string.habits_notification_title, habit.name))
            .setContentText(context.getString(R.string.habits_notification_body))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.habits_notification_done), HabitReminderIntents.done(context, habit.id, epochDay))
            .addAction(0, context.getString(R.string.habits_notification_open), open)
            .build()
        try {
            manager.notify(HabitReminderIds.notification(habit.id), notification)
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {
        }
    }

    fun cancel(habitId: Long) = manager.cancel(HabitReminderIds.notification(habitId))

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.habits_reminder_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.habits_reminder_channel_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "habit_reminders_v1"
    }
}
