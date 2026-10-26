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

/** "Skip next" on the next-alarm widget: suppresses one occurrence (or switches a one-time alarm off). */
class SkipNextCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val alarmId = parameters[AlarmIdKey] ?: return
        val result = context.surfaceEntry().alarmActions().skipNext(alarmId, occurrenceAt = parameters[OccurrenceKey])
        if (result is ActionResult.Locked) context.toastLocked(result.minutes)
        NextAlarmWidget().update(context, glanceId)
    }
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
