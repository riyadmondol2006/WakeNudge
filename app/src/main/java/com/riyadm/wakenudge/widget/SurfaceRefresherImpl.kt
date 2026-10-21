package com.riyadm.wakenudge.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.service.quicksettings.TileService
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.riyadm.wakenudge.di.AppScope
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import com.riyadm.wakenudge.tile.NextAlarmTileService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Redraws the home-screen widgets and asks the quick-settings tile to refresh. The engine calls this after nearly every
 * change, so calls are coalesced: the first one in a burst schedules a single refresh [DEBOUNCE_MS] later, which then
 * reads whatever the state is by that time.
 */
@Singleton
class SurfaceRefresherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope,
    settings: SettingsRepository,
    private val signal: RefreshSignal,
) : SurfaceRefresher {

    private val refresh = CoalescingTrigger(scope, DEBOUNCE_MS) { refreshNow() }

    init {
        // Settings that change how the surfaces look or what they claim, whichever screen changed them.
        scope.launch {
            settings.settings
                .map { Triple(it.timeFormat, it.dynamicColor, it.vacation) }
                .distinctUntilChanged()
                .drop(1)
                .collect { refreshAll() }
        }
    }

    override fun refreshAll() = refresh.trigger()

    private suspend fun refreshNow() {
        signal.fire()
        guarded("widgets") { updateWidgets() }
        guarded("tile") {
            TileService.requestListeningState(context, ComponentName(context, NextAlarmTileService::class.java))
        }
    }

    private suspend fun updateWidgets() {
        // Before the first unlock nothing can show widgets, and Glance keeps its bookkeeping in credential storage.
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return
        val manager = AppWidgetManager.getInstance(context)
        for (placement in PLACEMENTS) {
            val placed = manager.getAppWidgetIds(ComponentName(context, placement.receiver)).isNotEmpty()
            if (placed) guarded(placement.receiver.simpleName) { placement.widget().updateAll(context) }
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.w(TAG, "refreshing $what failed", t)
        }
    }

    private class Placement(val receiver: Class<out GlanceAppWidgetReceiver>, val widget: () -> GlanceAppWidget)

    private companion object {
        const val TAG = "WakeNudge"
        const val DEBOUNCE_MS = 300L

        val PLACEMENTS = listOf(
            Placement(NextAlarmWidgetReceiver::class.java) { NextAlarmWidget() },
            Placement(ClockWidgetReceiver::class.java) { ClockWidget() },
            Placement(AlarmListWidgetReceiver::class.java) { AlarmListWidget() },
        )
    }
}

/**
 * Runs [action] once, [delayMs] after the first [trigger] of a burst; triggers arriving in between are absorbed
 * because the action reads whatever state exists by the time it runs. A trigger during the action starts a new round,
 * so no change is ever left unreported.
 */
internal class CoalescingTrigger(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val action: suspend () -> Unit,
) {
    private val scheduled = AtomicBoolean(false)

    fun trigger() {
        if (!scheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                delay(delayMs)
            } finally {
                scheduled.set(false)
            }
            action()
        }
    }
}
