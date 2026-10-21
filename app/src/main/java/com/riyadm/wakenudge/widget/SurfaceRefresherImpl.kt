package com.riyadm.wakenudge.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.service.quicksettings.TileService
import android.text.format.DateFormat
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.di.AppScope
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.TimeFormat
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import com.riyadm.wakenudge.tile.NextAlarmTileService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Redraws the home-screen widgets and asks the quick-settings tile to refresh. The engine calls this after nearly every
 * change, so calls are coalesced: the first one in a burst schedules a single refresh [DEBOUNCE_MS] later, which then
 * reads whatever the state is by that time.
 *
 * The widgets declare no periodic update (it would wake the device every 30 minutes per widget), so everything that can
 * change what they show has to arrive here: alarm and settings changes through the engine, the date rolling over and a
 * language change through [SurfaceEventsReceiver]. A widget is only redrawn when its picture really differs from the
 * one it was last given, which keeps the many redundant calls around a ring (fired, started, snoozed, dismissed) cheap.
 */
@Singleton
class SurfaceRefresherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope,
    settings: SettingsRepository,
    private val signal: RefreshSignal,
    private val rollover: SurfaceRollover,
    private val time: TimeSource,
) : SurfaceRefresher {

    private val refresh = CoalescingTrigger(scope, DEBOUNCE_MS) { refreshNow() }
    private val gate = Mutex()
    private val ledger = RenderLedger(RENDER_MAX_AGE_MS)

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

    /** Redraws now. [force] skips the "nothing changed" check, for causes the pictures cannot see (a new language). */
    internal suspend fun refreshNow(force: Boolean = false) = gate.withLock {
        signal.fire()
        guarded("widgets") { updateWidgets(force) }
        guarded("tile") {
            TileService.requestListeningState(context, ComponentName(context, NextAlarmTileService::class.java))
        }
    }

    private suspend fun updateWidgets(force: Boolean) {
        // Before the first unlock nothing can show widgets, and Glance keeps its bookkeeping in credential storage.
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return
        val manager = AppWidgetManager.getInstance(context)
        val ids = PLACEMENTS.associateWith { manager.getAppWidgetIds(ComponentName(context, it.receiver)).toSet() }
        val placed = PLACEMENTS.filter { ids.getValue(it).isNotEmpty() }
        // Whenever the surfaces are looked at, the midnight alarm is brought in line with whether any widget exists.
        rollover.sync(placed.isNotEmpty())
        for (gone in PLACEMENTS - placed.toSet()) ledger.forget(gone.receiver)
        if (placed.isEmpty()) return

        val model = WidgetModelSource(
            context.surfaceEntry(),
            needsOutlook = placed.any { it.drawsOutlook },
            needsAlarms = placed.any { it.drawsAlarms },
        ).load()
        val setting = RenderSetting(Locale.getDefault(), DateFormat.is24HourFormat(context), time.zone())
        val nowMs = time.nowMillis()
        for (placement in placed) {
            val picture = placement.pictureOf(model, setting, ids.getValue(placement))
            if (!force && ledger.isCurrent(placement.receiver, picture, nowMs)) continue
            guarded(placement.receiver.simpleName) {
                placement.widget().updateAll(context)
                ledger.record(placement.receiver, picture, nowMs)
            }
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

    private class Placement(
        val receiver: Class<out GlanceAppWidgetReceiver>,
        val widget: () -> GlanceAppWidget,
        /** What the widget draws: the next alarm and its note, and/or the list of alarms. */
        val drawsOutlook: Boolean,
        val drawsAlarms: Boolean,
    ) {
        fun pictureOf(model: WidgetModel, setting: RenderSetting, widgetIds: Set<Int>) = Picture(
            timeFormat = model.settings.timeFormat,
            dynamicColor = model.settings.dynamicColor,
            today = model.today,
            firstDay = model.firstDay,
            outlook = model.outlook.takeIf { drawsOutlook },
            alarms = if (drawsAlarms) model.alarms else emptyList(),
            groupsOff = if (drawsAlarms) model.groupsOff else emptySet(),
            setting = setting,
            widgetIds = widgetIds,
        )
    }

    private companion object {
        const val TAG = "WakeNudge"
        const val DEBOUNCE_MS = 300L

        /**
         * A picture is trusted for this long. The check exists to skip redundant redraws, not to be the last word: a
         * redraw that silently failed (Glance runs it later, in WorkManager) heals at the next change or after this.
         */
        const val RENDER_MAX_AGE_MS = 60 * 60_000L

        val PLACEMENTS = listOf(
            Placement(NextAlarmWidgetReceiver::class.java, { NextAlarmWidget() }, drawsOutlook = true, drawsAlarms = false),
            Placement(ClockWidgetReceiver::class.java, { ClockWidget() }, drawsOutlook = true, drawsAlarms = false),
            Placement(AlarmListWidgetReceiver::class.java, { AlarmListWidget() }, drawsOutlook = false, drawsAlarms = true),
        )
    }
}

/** What a widget is rendered from besides the stored data: the words, number formats and zone the surfaces use. */
internal data class RenderSetting(val locale: Locale, val is24Hour: Boolean, val zone: ZoneId)

/** Everything one widget draws; two equal pictures look identical, so the second need not be drawn. */
internal data class Picture(
    val timeFormat: TimeFormat,
    val dynamicColor: Boolean,
    val today: LocalDate,
    val firstDay: DayOfWeek,
    val outlook: AlarmOutlook?,
    val alarms: List<Alarm>,
    val groupsOff: Set<Long>,
    val setting: RenderSetting,
    val widgetIds: Set<Int>,
)

/** Remembers the picture each widget was last given, and when, so an unchanged one is not drawn again. */
internal class RenderLedger(private val maxAgeMs: Long) {
    private class Entry(val picture: Any, val atMs: Long)

    private val entries = HashMap<Any, Entry>()

    /** True when [picture] is what [key] was last given, no longer ago than the maximum age (and not from the future). */
    @Synchronized
    fun isCurrent(key: Any, picture: Any, nowMs: Long): Boolean {
        val last = entries[key] ?: return false
        return last.picture == picture && nowMs - last.atMs in 0..maxAgeMs
    }

    @Synchronized
    fun record(key: Any, picture: Any, nowMs: Long) {
        entries[key] = Entry(picture, nowMs)
    }

    @Synchronized
    fun forget(key: Any) {
        entries.remove(key)
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
