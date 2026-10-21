package com.riyadm.wakenudge.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.riyadm.wakenudge.core.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the widgets say about the next alarm only goes stale in one way that no app event announces: the date changes
 * ("Tomorrow" becomes "Today", a skipped day arrives, a vacation ends). The platform's answer, `updatePeriodMillis`,
 * wakes the device every 30 minutes for every placed widget, so the widgets declare no period and this arms ONE alarm
 * for the next local midnight instead. It is a non-waking alarm: if the device sleeps through midnight the refresh
 * happens the moment it wakes, which is also the first moment anybody could look at a widget.
 */
@Singleton
class SurfaceRollover @Inject constructor(
    @ApplicationContext private val context: Context,
    private val time: TimeSource,
) {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    /** Arms the midnight alarm while any widget is on a home screen and cancels it once none is. Idempotent. */
    fun sync(widgetsPlaced: Boolean) {
        try {
            if (widgetsPlaced) {
                alarmManager.set(AlarmManager.RTC, nextRollover(time.now()).toInstant().toEpochMilli(), operation(create = true)!!)
            } else {
                operation(create = false)?.let {
                    alarmManager.cancel(it)
                    it.cancel()
                }
            }
        } catch (e: RuntimeException) {
            // An inexact alarm failing only costs a stale day label until the next refresh; never worth a crash.
            Log.w(TAG, "arming the widget day rollover failed", e)
        }
    }

    private fun operation(create: Boolean): PendingIntent? = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, SurfaceEventsReceiver::class.java).setAction(SurfaceEventsReceiver.ACTION_ROLLOVER),
        PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE,
    )

    companion object {
        private const val TAG = "WakeNudge"

        /** A moment past midnight, so the refresh never reads "today" from a clock that is a hair early. */
        private const val SLACK_SECONDS = 2L

        /** The first instant of the day after [now] in its own zone (DST gaps at midnight resolve to the first valid time). */
        fun nextRollover(now: ZonedDateTime): ZonedDateTime =
            now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusSeconds(SLACK_SECONDS)

        /** True while at least one of WakeNudge's widgets is placed on a home screen. */
        fun anyWidgetPlaced(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return WIDGET_RECEIVERS.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
        }

        internal val WIDGET_RECEIVERS: List<Class<out BroadcastReceiver>> = listOf(
            NextAlarmWidgetReceiver::class.java,
            ClockWidgetReceiver::class.java,
            AlarmListWidgetReceiver::class.java,
        )
    }
}

/**
 * The few things that can make a widget wrong without any alarm changing: the date rolling over (see
 * [SurfaceRollover]) and the user switching language, which changes every word and date format the widgets drew.
 */
class SurfaceEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ROLLOVER && intent.action != Intent.ACTION_LOCALE_CHANGED) return
        val refresher = context.surfaceEntry().refresher()
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // A new language never alters the data, so that redraw is forced past the "nothing changed" check.
                val newLanguage = intent.action == Intent.ACTION_LOCALE_CHANGED
                withTimeoutOrNull(WORK_TIMEOUT_MS) { refresher.refreshNow(force = newLanguage) }
            } catch (t: Throwable) {
                Log.w("WakeNudge", "refreshing the widgets after ${intent.action} failed", t)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_ROLLOVER = "com.riyadm.wakenudge.action.WIDGET_DAY_ROLLOVER"
        private const val WORK_TIMEOUT_MS = 8_000L
    }
}
