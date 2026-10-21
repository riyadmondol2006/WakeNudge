package com.riyadm.wakenudge.engine.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.riyadm.wakenudge.MainActivity
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.di.AppScope
import com.riyadm.wakenudge.domain.model.TimeFormat
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.SnoozePolicy
import com.riyadm.wakenudge.engine.ActionReceiver
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingSession
import com.riyadm.wakenudge.ui.ring.RingActivity
import com.riyadm.wakenudge.ui.ring.WakeCheckActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object Channels {
    const val RING = "ring_v1"
    const val UPCOMING = "upcoming_v1"
    const val SNOOZED = "snoozed_v1"
    const val MISSED = "missed_v1"
    const val WAKE_CHECK = "wake_check_v1"
    const val TIMER_RUNNING = "timer_running_v1"
    const val TIMER_DONE = "timer_done_v1"
    const val BEDTIME = "bedtime_v1"
}

object NotificationIds {
    const val RING = 1001
    const val BEDTIME = 1002
    fun snoozed(alarmId: Long) = 20_000 + (alarmId and 0xFFFF).toInt()
    fun upcoming(alarmId: Long) = 30_000 + (alarmId and 0xFFFF).toInt()
    fun wakeCheck(alarmId: Long) = 40_000 + (alarmId and 0xFFFF).toInt()
    fun missed(alarmId: Long) = 50_000 + (alarmId and 0xFFFF).toInt()
    fun timer(timerId: Long) = 60_000 + (timerId and 0xFFFF).toInt()
}

object Actions {
    private const val P = "com.riyadm.wakenudge.action."
    const val SNOOZE = P + "SNOOZE"
    const val DISMISS = P + "DISMISS"
    const val SNOOZE_WAKE_NOW = P + "SNOOZE_WAKE_NOW"
    const val SKIP_NEXT = P + "SKIP_NEXT"
    const val WAKE_CHECK_OK = P + "WAKE_CHECK_OK"
    const val WAKE_CHECK_CANCEL = P + "WAKE_CHECK_CANCEL"
    const val TIMER_PAUSE = P + "TIMER_PAUSE"
    const val TIMER_RESUME = P + "TIMER_RESUME"
    const val TIMER_ADD_MINUTE = P + "TIMER_ADD_MINUTE"
    const val TIMER_RESET = P + "TIMER_RESET"
    const val TIMER_STOP = P + "TIMER_STOP"
    const val EXTRA_ID = "id"
}

/** Builds and posts every notification the app shows. Channels are created lazily and idempotently. */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,
    @AppScope scope: CoroutineScope,
) {
    private val manager = NotificationManagerCompat.from(context)

    @Volatile
    private var timeFormat = TimeFormat.SYSTEM

    init {
        scope.launch { settings.settings.collect { timeFormat = it.timeFormat } }
    }

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        fun channel(id: String, nameRes: Int, descRes: Int, importance: Int, block: NotificationChannel.() -> Unit = {}) =
            NotificationChannel(id, context.getString(nameRes), importance).apply {
                description = context.getString(descRes)
                setShowBadge(false)
                block()
            }
        nm.createNotificationChannels(
            listOf(
                channel(Channels.RING, R.string.channel_ring, R.string.channel_ring_desc, NotificationManager.IMPORTANCE_HIGH) {
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                channel(Channels.UPCOMING, R.string.channel_upcoming, R.string.channel_upcoming_desc, NotificationManager.IMPORTANCE_LOW),
                channel(Channels.SNOOZED, R.string.channel_snoozed, R.string.channel_snoozed_desc, NotificationManager.IMPORTANCE_LOW),
                channel(Channels.MISSED, R.string.channel_missed, R.string.channel_missed_desc, NotificationManager.IMPORTANCE_HIGH),
                channel(Channels.WAKE_CHECK, R.string.channel_wake_check, R.string.channel_wake_check_desc, NotificationManager.IMPORTANCE_HIGH) {
                    setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, alarmAttrs)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                channel(Channels.TIMER_RUNNING, R.string.channel_timer_running, R.string.channel_timer_running_desc, NotificationManager.IMPORTANCE_LOW),
                channel(Channels.TIMER_DONE, R.string.channel_timer_done, R.string.channel_timer_done_desc, NotificationManager.IMPORTANCE_HIGH) {
                    setSound(null, null)
                    enableVibration(false)
                },
                channel(Channels.BEDTIME, R.string.channel_bedtime, R.string.channel_bedtime_desc, NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    // ---- ringing alarm -------------------------------------------------------------------------------------

    fun ring(session: RingSession, alertAgain: Boolean = false): Notification {
        val alarm = session.alarm
        val title = when {
            session.isTimer -> alarm.label.ifBlank { context.getString(R.string.timer_default_name) }
            else -> alarm.label.ifBlank { context.getString(R.string.notif_alarm_default_title) }
        }
        val is24 = is24Hour()
        val text = when {
            session.isTimer -> context.getString(R.string.notif_timer_done_text)
            session.phase == RingPhase.SUNRISE -> context.getString(R.string.notif_sunrise_text)
            else -> TimeText.format(alarm.hour, alarm.minute, is24)
        }
        val open = ringActivityIntent()
        val builder = NotificationCompat.Builder(context, Channels.RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!alertAgain)
            .setShowWhen(true)
            .setWhen(session.scheduledAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setContentIntent(open)
            .setFullScreenIntent(open, true)

        if (session.phase == RingPhase.RINGING) {
            if (session.isTimer) {
                builder.addAction(0, context.getString(R.string.action_add_minute), broadcast(Actions.TIMER_ADD_MINUTE, session.timerId))
                builder.addAction(0, context.getString(R.string.action_stop), broadcast(Actions.TIMER_STOP, session.timerId))
            } else {
                val canSnooze = SnoozePolicy.canSnooze(alarm.snooze, session.snoozeCount)
                if (canSnooze) {
                    val snoozeNeedsMission = alarm.snoozeMissions.isNotEmpty()
                    builder.addAction(
                        0,
                        context.getString(R.string.action_snooze),
                        if (snoozeNeedsMission) open else broadcast(Actions.SNOOZE, alarm.id),
                    )
                }
                val dismissDirect = alarm.missions.isEmpty() && alarm.antiOversleep.holdToDismissSeconds == 0
                builder.addAction(
                    0,
                    context.getString(if (dismissDirect) R.string.action_dismiss else R.string.action_solve_to_dismiss),
                    if (dismissDirect) broadcast(Actions.DISMISS, alarm.id) else open,
                )
            }
        }
        return builder.build()
    }

    fun postRing(session: RingSession, alertAgain: Boolean = false) {
        runCatching { manager.notify(NotificationIds.RING, ring(session, alertAgain)) }
    }

    /** Last-resort heads-up when the foreground service could not be started. */
    fun postRingFallback() {
        runCatching { manager.notify(NotificationIds.RING, ringPlaceholder()) }
    }

    /** Placeholder shown within the 5-second foreground-service deadline, before the session is loaded. */
    fun ringPlaceholder(): Notification = NotificationCompat.Builder(context, Channels.RING)
        .setSmallIcon(R.drawable.ic_stat_alarm)
        .setContentTitle(context.getString(R.string.notif_alarm_default_title))
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setOngoing(true)
        .setContentIntent(ringActivityIntent())
        .setFullScreenIntent(ringActivityIntent(), true)
        .build()

    fun cancelRing() = manager.cancel(NotificationIds.RING)

    // ---- snooze / missed / upcoming ------------------------------------------------------------------------

    fun showSnoozed(alarm: Alarm, untilMillis: Long) {
        val title = alarm.label.ifBlank { context.getString(R.string.notif_alarm_default_title) }
        val until = TimeText.format(Instant.ofEpochMilli(untilMillis).atZone(ZoneId.systemDefault()).toLocalTime(), is24Hour())
        val b = NotificationCompat.Builder(context, Channels.SNOOZED)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.notif_snoozed_title, title))
            .setContentText(context.getString(R.string.notif_snoozed_text, until))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setWhen(untilMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(mainIntent(alarm.id))
            .addAction(0, context.getString(R.string.action_wake_now), broadcast(Actions.SNOOZE_WAKE_NOW, alarm.id))
        if (alarm.missions.isEmpty()) {
            b.addAction(0, context.getString(R.string.action_dismiss), broadcast(Actions.DISMISS, alarm.id))
        }
        runCatching { manager.notify(NotificationIds.snoozed(alarm.id), b.build()) }
    }

    fun cancelSnoozed(alarmId: Long) = manager.cancel(NotificationIds.snoozed(alarmId))

    fun showMissed(alarm: Alarm, scheduledAt: Long) {
        val when_ = TimeText.format(Instant.ofEpochMilli(scheduledAt).atZone(ZoneId.systemDefault()).toLocalTime(), is24Hour())
        val b = NotificationCompat.Builder(context, Channels.MISSED)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.notif_missed_title))
            .setContentText(context.getString(R.string.notif_missed_text, alarm.label.ifBlank { when_ }, when_))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(mainIntent(alarm.id))
        runCatching { manager.notify(NotificationIds.missed(alarm.id), b.build()) }
    }

    fun showUpcoming(alarm: Alarm, atMillis: Long, canSkip: Boolean) {
        val whenText = TimeText.format(Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalTime(), is24Hour())
        val b = NotificationCompat.Builder(context, Channels.UPCOMING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.notif_upcoming_title, whenText))
            .setContentText(alarm.label.ifBlank { context.getString(R.string.notif_alarm_default_title) })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setWhen(atMillis)
            .setTimeoutAfter((atMillis - System.currentTimeMillis()).coerceAtLeast(1_000))
            .setContentIntent(mainIntent(alarm.id))
        if (canSkip) b.addAction(0, context.getString(R.string.action_skip_next), broadcast(Actions.SKIP_NEXT, alarm.id))
        runCatching { manager.notify(NotificationIds.upcoming(alarm.id), b.build()) }
    }

    fun cancelUpcoming(alarmId: Long) = manager.cancel(NotificationIds.upcoming(alarmId))

    // ---- "Still awake?" ------------------------------------------------------------------------------------

    fun showWakeCheck(alarm: Alarm, deadlineAt: Long, eventId: Long) {
        val full = PendingIntent.getActivity(
            context, 3,
            Intent(context, WakeCheckActivity::class.java)
                .putExtra(Actions.EXTRA_ID, alarm.id)
                .putExtra(WakeCheckActivity.EXTRA_DEADLINE, deadlineAt)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, Channels.WAKE_CHECK)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.notif_wake_check_title))
            .setContentText(context.getString(R.string.notif_wake_check_text, alarm.label.ifBlank { context.getString(R.string.notif_alarm_default_title) }))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setWhen(deadlineAt)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter((deadlineAt - System.currentTimeMillis()).coerceAtLeast(1_000))
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .addAction(0, context.getString(R.string.action_im_awake), broadcast(Actions.WAKE_CHECK_OK, alarm.id))
            .addAction(0, context.getString(R.string.action_cancel_check), broadcast(Actions.WAKE_CHECK_CANCEL, alarm.id))
        runCatching { manager.notify(NotificationIds.wakeCheck(alarm.id), b.build()) }
    }

    fun cancelWakeCheck(alarmId: Long) = manager.cancel(NotificationIds.wakeCheck(alarmId))

    // ---- timers --------------------------------------------------------------------------------------------

    fun showTimerRunning(timer: CountdownTimer) {
        val title = timer.label.ifBlank { context.getString(R.string.timer_default_name) }
        val b = NotificationCompat.Builder(context, Channels.TIMER_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainIntent(0, MainActivity.ACTION_SHOW_TIMERS))
        if (timer.state == TimerState.RUNNING) {
            b.setWhen(timer.endsAtMillis).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true)
            b.addAction(0, context.getString(R.string.action_pause), broadcast(Actions.TIMER_PAUSE, timer.id))
            b.addAction(0, context.getString(R.string.action_add_minute), broadcast(Actions.TIMER_ADD_MINUTE, timer.id))
        } else {
            b.setContentText(context.getString(R.string.notif_timer_paused, TimeText.clockDuration(timer.remainingMs)))
            b.setShowWhen(false)
            b.addAction(0, context.getString(R.string.action_resume), broadcast(Actions.TIMER_RESUME, timer.id))
        }
        b.addAction(0, context.getString(R.string.action_reset), broadcast(Actions.TIMER_RESET, timer.id))
        runCatching { manager.notify(NotificationIds.timer(timer.id), b.build()) }
    }

    fun cancelTimer(timerId: Long) = manager.cancel(NotificationIds.timer(timerId))

    // ---- bedtime -------------------------------------------------------------------------------------------

    fun showBedtime(bedtime: LocalTime, wake: LocalTime?) {
        val is24 = is24Hour()
        val b = NotificationCompat.Builder(context, Channels.BEDTIME)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.notif_bedtime_title))
            .setContentText(
                if (wake != null) context.getString(R.string.notif_bedtime_text_with_alarm, TimeText.format(bedtime, is24), TimeText.format(wake, is24))
                else context.getString(R.string.notif_bedtime_text, TimeText.format(bedtime, is24)),
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(mainIntent(0, MainActivity.ACTION_SHOW_SLEEP))
        runCatching { manager.notify(NotificationIds.BEDTIME, b.build()) }
    }

    // ---- plumbing ------------------------------------------------------------------------------------------

    private fun is24Hour(): Boolean = TimeText.is24Hour(context, timeFormat)

    private fun ringActivityIntent(): PendingIntent = PendingIntent.getActivity(
        context, 1,
        Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun mainIntent(alarmId: Long, action: String = MainActivity.ACTION_SHOW_ALARMS): PendingIntent = PendingIntent.getActivity(
        context, 2,
        Intent(context, MainActivity::class.java).setAction(action).putExtra(Actions.EXTRA_ID, alarmId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(action: String, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ActionReceiver::class.java).setAction(action)
            .setData(android.net.Uri.parse("wakenudge://action/$action/$id"))
            .putExtra(Actions.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
