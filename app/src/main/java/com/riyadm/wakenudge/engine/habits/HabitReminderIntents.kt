package com.riyadm.wakenudge.engine.habits

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.riyadm.wakenudge.ui.habits.HabitNotificationActivity

object HabitReminderIds {
    const val NOTIFICATION_BASE = 140_000
    const val REMINDER_REQUEST_BASE = 700_000
    const val DONE_REQUEST_BASE = 710_000
    const val OPEN_REQUEST_BASE = 720_000

    fun notification(habitId: Long) = NOTIFICATION_BASE + (habitId and 0xFFFF).toInt()
    private fun request(base: Int, habitId: Long) = base + (habitId and 0xFFF).toInt()

    fun reminderRequest(habitId: Long) = request(REMINDER_REQUEST_BASE, habitId)
    fun doneRequest(habitId: Long) = request(DONE_REQUEST_BASE, habitId)
    fun openRequest(habitId: Long) = request(OPEN_REQUEST_BASE, habitId)
}

object HabitReminderIntents {
    const val ACTION_REMIND = "com.riyadm.wakenudge.action.HABIT_REMIND"
    const val ACTION_DONE = "com.riyadm.wakenudge.action.HABIT_DONE"
    const val EXTRA_HABIT_ID = "habit_id"
    const val EXTRA_EPOCH_DAY = "epoch_day"

    fun reminder(context: Context, habitId: Long, epochDay: Long, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        broadcast(context, ACTION_REMIND, HabitReminderIds.reminderRequest(habitId), habitId, epochDay, flags)

    fun done(context: Context, habitId: Long, epochDay: Long): PendingIntent =
        broadcast(context, ACTION_DONE, HabitReminderIds.doneRequest(habitId), habitId, epochDay, PendingIntent.FLAG_UPDATE_CURRENT)!!

    fun open(context: Context, habitId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        HabitReminderIds.openRequest(habitId),
        Intent(context, HabitNotificationActivity::class.java)
            .setData(Uri.parse("wakenudge://habits/$habitId")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(context: Context, action: String, request: Int, habitId: Long, epochDay: Long, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, HabitReminderReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("wakenudge://habit-reminder/$habitId"))
                .putExtra(EXTRA_HABIT_ID, habitId)
                .putExtra(EXTRA_EPOCH_DAY, epochDay),
            PendingIntent.FLAG_IMMUTABLE or flags,
        )
}
