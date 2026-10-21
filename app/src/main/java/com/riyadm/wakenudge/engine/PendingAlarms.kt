package com.riyadm.wakenudge.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import com.riyadm.wakenudge.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the engine can ask AlarmManager to wake it for. */
enum class TriggerKind {
    /** The alarm itself. */
    ALARM,
    /** A snooze period ending. */
    SNOOZE,
    /** A backup alarm for an ignored ring. */
    BACKUP,
    /** The screen-glow lead-in before the alarm. */
    SUNRISE,
    /** "Alarm in 30 minutes" heads-up. */
    UPCOMING,
    /** Time to ask "Still awake?". */
    WAKE_CHECK_PROMPT,
    /** The "Still awake?" prompt went unanswered: ring again. */
    WAKE_CHECK_DEADLINE,
    TIMER,
    BEDTIME,
    /** Periodic self-healing re-registration. */
    WATCHDOG,
}

object Triggers {
    const val ACTION_PREFIX = "com.riyadm.wakenudge.action.TRIGGER_"
    const val EXTRA_ID = "id"
    const val EXTRA_INDEX = "index"
    const val EXTRA_AT = "at"
    const val EXTRA_EVENT_ID = "event_id"

    fun action(kind: TriggerKind) = ACTION_PREFIX + kind.name
    fun kindOf(action: String?): TriggerKind? =
        if (action != null && action.startsWith(ACTION_PREFIX)) TriggerKind.entries.firstOrNull { it.name == action.removePrefix(ACTION_PREFIX) } else null
}

/**
 * Thin, stateless wrapper around [AlarmManager]. Each trigger is identified by (kind, id, index) via the intent's
 * data URI, so registering again replaces the old one and cancelling is exact — no request-code collisions.
 */
@Singleton
class PendingAlarms @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** A user-visible alarm: shows in the system's "next alarm" UI and is exempt from Doze and exact-alarm limits. */
    fun setAlarmClock(kind: TriggerKind, id: Long, atMillis: Long, index: Int = 0, eventId: Long = -1, payloadAt: Long = atMillis): Boolean = guarded {
        val show = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_ALARMS).putExtra(Triggers.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), operation(kind, id, index, payloadAt, eventId))
    }

    /** An internal trigger (follow-ups, reminders): exact when permitted, otherwise the closest Doze-safe fallback. */
    fun setExact(kind: TriggerKind, id: Long, atMillis: Long, index: Int = 0, eventId: Long = -1, payloadAt: Long = atMillis): Boolean = guarded {
        val op = operation(kind, id, index, payloadAt, eventId)
        if (canScheduleExact()) {
            AlarmManagerCompat.setExactAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, atMillis, op)
        } else {
            AlarmManagerCompat.setAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, atMillis, op)
        }
    }

    /** Inexact, battery-friendly trigger used by the watchdog. */
    fun setInexact(kind: TriggerKind, id: Long, atMillis: Long): Boolean = guarded {
        AlarmManagerCompat.setAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, atMillis, operation(kind, id, 0, atMillis, -1))
    }

    /**
     * The OS allows 500 concurrent alarms per app and throws beyond that (and a revoked permission throws too). A
     * registration failure must never take the rest of the schedule down with it, so it is reported, not thrown.
     */
    private inline fun guarded(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (t: RuntimeException) {
        lastFailure = "${t.javaClass.simpleName}: ${t.message}"
        false
    }

    /** The most recent registration failure, for Alarm Health. */
    @Volatile var lastFailure: String? = null
        private set

    fun cancel(kind: TriggerKind, id: Long, index: Int = 0) {
        val op = existing(kind, id, index) ?: return
        alarmManager.cancel(op)
        op.cancel()
    }

    fun isScheduled(kind: TriggerKind, id: Long, index: Int = 0): Boolean = existing(kind, id, index) != null

    /** The system's idea of the next alarm clock (from any app), for diagnostics. */
    fun systemNextAlarmClock(): AlarmManager.AlarmClockInfo? = alarmManager.nextAlarmClock

    private fun intent(kind: TriggerKind, id: Long, index: Int): Intent =
        Intent(context, AlarmReceiver::class.java)
            .setAction(Triggers.action(kind))
            .setData(Uri.parse("wakenudge://trigger/${kind.name}/$id/$index"))
            .putExtra(Triggers.EXTRA_ID, id)
            .putExtra(Triggers.EXTRA_INDEX, index)

    private fun operation(kind: TriggerKind, id: Long, index: Int, atMillis: Long, eventId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            intent(kind, id, index).putExtra(Triggers.EXTRA_AT, atMillis).putExtra(Triggers.EXTRA_EVENT_ID, eventId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun existing(kind: TriggerKind, id: Long, index: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, intent(kind, id, index), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
}
