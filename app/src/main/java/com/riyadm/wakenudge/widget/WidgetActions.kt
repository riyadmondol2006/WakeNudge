package com.riyadm.wakenudge.widget

import android.content.Context
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.ToggleableStateKey
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.usecase.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val AlarmIdKey = ActionParameters.Key<Long>("alarm_id")

/** The ring the widget showed when the button was drawn, so a double tap cannot skip the one after it. */
internal val OccurrenceKey = ActionParameters.Key<Long>("occurrence_at")

/** Last-minute edit protection said no: tell the user how long the lock lasts. */
private suspend fun Context.toastLocked(minutes: Int) = withContext(Dispatchers.Main) {
    val text = resources.getQuantityString(R.plurals.widget_locked_minutes, minutes, minutes)
    Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
}

/**
 * "Skip next" on the next-alarm widget: suppresses one occurrence (the widget then offers "Undo skip"), or switches a
 * one-time alarm off. Nothing can be undone from the widget once a one-time alarm is off, so that takes a second tap.
 */
class SkipNextCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val alarmId = parameters[AlarmIdKey] ?: return
        val entry = context.surfaceEntry()
        val alarm = entry.alarms().getAlarm(alarmId) ?: return
        if (!alarm.isRepeating && !TurnOffConfirmation.confirm(alarmId, entry.time().nowMillis())) {
            val time = SurfaceText(context, entry.settings().current().timeFormat).time(alarm.time)
            withContext(Dispatchers.Main) {
                Toast.makeText(context.applicationContext, context.getString(R.string.widget_confirm_turn_off, time), Toast.LENGTH_LONG).show()
            }
            return
        }
        val result = entry.alarmActions().skipNext(alarmId, occurrenceAt = parameters[OccurrenceKey])
        if (result is ActionResult.Locked) context.toastLocked(result.minutes)
        NextAlarmWidget().update(context, glanceId)
    }
}

/** "Undo skip" on the next-alarm widget: the skipped ring is back on. */
class UndoSkipCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val alarmId = parameters[AlarmIdKey] ?: return
        context.surfaceEntry().alarmActions().clearSkip(alarmId)
        NextAlarmWidget().update(context, glanceId)
    }
}

/** A first tap on "Turn off" arms it for a few seconds; only a second tap within that window goes through. */
internal object TurnOffConfirmation {
    const val WINDOW_MS = 8_000L
    private val armedAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    fun confirm(alarmId: Long, nowMs: Long): Boolean {
        val first = armedAt[alarmId]
        return if (first != null && nowMs - first in 0..WINDOW_MS) {
            armedAt.remove(alarmId)
            true
        } else {
            armedAt[alarmId] = nowMs
            false
        }
    }

    internal fun reset() = armedAt.clear()
}

/** The switch of one row of the alarm list widget. Always redraws so a refused change snaps back. */
class ToggleAlarmCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val alarmId = parameters[AlarmIdKey] ?: return
        val enabled = parameters[ToggleableStateKey] ?: return
        val result = context.surfaceEntry().alarmActions().setEnabled(alarmId, enabled)
        if (result is ActionResult.Locked) context.toastLocked(result.minutes)
        AlarmListWidget().update(context, glanceId)
    }
}
