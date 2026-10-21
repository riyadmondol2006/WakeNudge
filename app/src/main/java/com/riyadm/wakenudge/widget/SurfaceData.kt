package com.riyadm.wakenudge.widget

import android.content.Context
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import com.riyadm.wakenudge.domain.usecase.AlarmActions
import com.riyadm.wakenudge.engine.SchedulingContextProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Fired whenever the engine asks the surfaces to redraw. A Glance session that is still alive does not re-run
 * `provideGlance` on `update`, so live sessions listen to this to reload their data.
 */
@Singleton
class RefreshSignal @Inject constructor() {
    private val mutableTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val ticks: SharedFlow<Unit> = mutableTicks

    fun fire() {
        mutableTicks.tryEmit(Unit)
    }
}

/** Answers "what will ring next, and is anything being skipped?" for the widgets and the quick-settings tile. */
@Singleton
class AlarmOutlookProvider @Inject constructor(
    private val alarms: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val calculator: NextTriggerCalculator,
    private val contexts: SchedulingContextProvider,
    private val time: TimeSource,
) {
    suspend fun load(): AlarmOutlook {
        val all = alarms.getAlarms()
        val next = scheduler.nextAlarm()
        val context = contexts.load()
        val now = time.now()
        val suppressed = all.mapNotNull { alarm ->
            calculator.suppressionOfNaturalNext(alarm, now, context.forAlarm(alarm))
                ?.let { SuppressedOccurrence(alarm.time, it) }
        }
        return AlarmOutlook.decide(next, suppressed, totalAlarms = all.size, enabledAlarms = all.count { it.enabled })
    }
}

/**
 * Widgets, their action callbacks and the tile run outside any Hilt-managed component, so they reach the object
 * graph the same way the engine's receivers do (and, like them, only touch device-protected storage).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SurfaceEntryPoint {
    fun alarms(): AlarmRepository
    fun groups(): GroupRepository
    fun exceptions(): ExceptionRepository
    fun settings(): SettingsRepository
    fun outlook(): AlarmOutlookProvider
    fun alarmActions(): AlarmActions
    fun scheduler(): AlarmScheduler
    fun surfaces(): SurfaceRefresher
    fun time(): TimeSource
    fun refreshSignal(): RefreshSignal
    fun refresher(): SurfaceRefresherImpl
    fun rollover(): SurfaceRollover
}

fun Context.surfaceEntry(): SurfaceEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, SurfaceEntryPoint::class.java)

/** Everything a widget needs to draw itself; equal models never trigger a redraw. */
internal data class WidgetModel(
    val settings: AppSettings,
    val today: LocalDate,
    val firstDay: DayOfWeek,
    val outlook: AlarmOutlook?,
    val alarms: List<Alarm>,
    val groupsOff: Set<Long>,
)

internal class WidgetModelSource(private val entry: SurfaceEntryPoint, private val needsOutlook: Boolean, private val needsAlarms: Boolean) {

    suspend fun load(): WidgetModel {
        val settings = entry.settings().current()
        val systemFirstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        return WidgetModel(
            settings = settings,
            today = entry.time().now().toLocalDate(),
            firstDay = settings.firstDayOfWeek(systemFirstDay),
            outlook = if (needsOutlook) entry.outlook().load() else null,
            alarms = if (needsAlarms) entry.alarms().getAlarms() else emptyList(),
            groupsOff = if (needsAlarms) entry.groups().getGroups().disabledIds() else emptySet(),
        )
    }

    /** Reloads whenever stored data changes or the engine asks for a refresh. */
    fun updates(): Flow<WidgetModel> {
        val stored = combine(
            entry.alarms().observeAlarms(),
            entry.groups().observeGroups(),
            entry.settings().settings,
            entry.exceptions().observeDates(),
        ) { _, _, _, _ -> }.drop(1)
        return merge(stored, entry.refreshSignal().ticks).map { load() }.distinctUntilChanged()
    }
}
