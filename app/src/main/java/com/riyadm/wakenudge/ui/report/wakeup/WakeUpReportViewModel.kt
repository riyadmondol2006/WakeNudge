package com.riyadm.wakenudge.ui.report.wakeup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.WakeCheck
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.repository.WakeCheckRepository
import com.riyadm.wakenudge.domain.stats.HowItWent
import com.riyadm.wakenudge.domain.stats.WakeDayReport
import com.riyadm.wakenudge.domain.stats.WakeReportCalculator
import com.riyadm.wakenudge.domain.stats.WakeStats
import com.riyadm.wakenudge.domain.stats.WakeStatsCalculator
import com.riyadm.wakenudge.domain.stats.WeekWakeReport
import com.riyadm.wakenudge.engine.WakeCheckController
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [cancellable] is false for a check that needs proof: only its screen answers it. */
data class PendingCheck(val check: WakeCheck, val label: String, val cancellable: Boolean = true)

data class WakeUpReportUiState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val thisWeekStart: LocalDate = LocalDate.now(),
    val week: WeekWakeReport? = null,
    val isThisWeek: Boolean = true,
    val canGoNext: Boolean = false,
    val stats: WakeStats? = null,
    val pendingChecks: List<PendingCheck> = emptyList(),
    val howToday: HowItWent = HowItWent(),
    val howWeek: HowItWent = HowItWent(),
    val dailyReport: WakeDayReport? = null,
    val selectedDay: LocalDate? = null,
)

@HiltViewModel
class WakeUpReportViewModel @Inject constructor(
    private val history: HistoryRepository,
    wakeChecks: WakeCheckRepository,
    alarms: AlarmRepository,
    settings: SettingsRepository,
    private val checkController: WakeCheckController,
    private val time: TimeSource,
) : ViewModel() {

    private val weekOffset = MutableStateFlow(0)
    private val selectedDay = MutableStateFlow<LocalDate?>(null)

    val state: StateFlow<WakeUpReportUiState> = combine(
        history.observeSince(time.nowMillis() - HISTORY_WINDOW_MS),
        wakeChecks.observeAll(),
        alarms.observeAlarms(),
        settings.settings,
        weekOffset,
        selectedDay,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        build(
            args[0] as List<AlarmEvent>,
            args[1] as List<WakeCheck>,
            args[2] as List<Alarm>,
            args[3] as AppSettings,
            args[4] as Int,
            args[5] as LocalDate?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WakeUpReportUiState())

    private fun build(
        events: List<AlarmEvent>,
        checks: List<WakeCheck>,
        alarmList: List<Alarm>,
        settings: AppSettings,
        offset: Int,
        selected: LocalDate?,
    ): WakeUpReportUiState {
        val now = time.now()
        val zone = now.zone
        val today = now.toLocalDate()
        val firstDay = settings.firstDayOfWeek(WeekFields.of(Locale.getDefault()).firstDayOfWeek)
        val thisWeekStart = WakeReportCalculator.weekStart(today, firstDay)
        val weekStart = thisWeekStart.plusWeeks(offset.toLong())
        // The zone in force now is the best record available for past rings; durations stay epoch-based.
        val week = WakeReportCalculator.weekReport(events, weekStart, zone)
        val stats = WakeStatsCalculator.compute(events, zone)
        val labels = alarmList.associate { it.id to it.label }
        val needsProof = alarmList.filter { it.wakeCheck.requireProof }.map { it.id }.toSet()
        val todays = events.filter { Instant.ofEpochMilli(it.firedAt).atZone(zone).toLocalDate() == today }
        val weekStartDate = today.minusDays(6)
        val lastWeek = events.filter { !Instant.ofEpochMilli(it.firedAt).atZone(zone).toLocalDate().isBefore(weekStartDate) }
        val daily = selected?.let { WakeReportCalculator.dayReport(events, it, zone) }
        return WakeUpReportUiState(
            loaded = true,
            settings = settings,
            zone = zone,
            today = today,
            thisWeekStart = thisWeekStart,
            week = week,
            isThisWeek = offset == 0,
            canGoNext = offset < 0,
            stats = stats,
            pendingChecks = checks.map { PendingCheck(it, labels[it.alarmId].orEmpty(), cancellable = it.alarmId !in needsProof) },
            howToday = HowItWent.of(todays),
            howWeek = HowItWent.of(lastWeek),
            dailyReport = daily,
            selectedDay = selected,
        )
    }

    fun previousWeek() {
        weekOffset.value -= 1
    }

    fun nextWeek() {
        if (weekOffset.value < 0) weekOffset.value += 1
    }

    fun openDaily(date: LocalDate) {
        selectedDay.value = date
    }

    fun closeDaily() {
        selectedDay.value = null
    }

    fun cancelCheck(alarmId: Long) = viewModelScope.launch { checkController.cancelByUser(alarmId) }
    fun clearHistory() = viewModelScope.launch { history.clear() }

    private companion object {
        const val HISTORY_WINDOW_MS = 120L * 24 * 3_600_000
    }
}
