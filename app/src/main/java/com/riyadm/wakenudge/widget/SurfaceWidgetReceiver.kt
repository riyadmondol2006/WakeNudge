package com.riyadm.wakenudge.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Base of the three widget receivers. A widget appearing or disappearing (including the system re-announcing them after
 * a reboot or an update) is the one moment the app learns whether any widget exists, so it is also when the midnight
 * rollover alarm of [SurfaceRollover] is armed or dropped, without waiting for the next alarm change.
 */
abstract class SurfaceWidgetReceiver : GlanceAppWidgetReceiver() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        syncRollover(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        syncRollover(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        syncRollover(context)
    }

    private fun syncRollover(context: Context) {
        context.surfaceEntry().rollover().sync(SurfaceRollover.anyWidgetPlaced(context))
    }
}
