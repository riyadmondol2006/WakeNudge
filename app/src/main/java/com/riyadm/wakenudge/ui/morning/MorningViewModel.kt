package com.riyadm.wakenudge.ui.morning

import com.riyadm.wakenudge.domain.stats.HowItWent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.WakeCheck
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.repository.WakeCheckRepository
import com.riyadm.wakenudge.domain.stats.WakeStats
import com.riyadm.wakenudge.domain.stats.WakeStatsCalculator
import com.riyadm.wakenudge.engine.WakeCheckController
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DayBar(val date: LocalDate, val dismissMinutes: Float?, val snoozes: Int, val successful: Boolean, val hasData: Boolean)

/** [cancellable] is false for a check that needs proof: only its screen answers it. */
data class PendingCheck(val check: WakeCheck, val label: String, val cancellable: Boolean = true)

data class MorningUiState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val stats: WakeStats? = null,
    val today: List<AlarmEvent> = emptyList(),
    val recent: List<AlarmEvent> = emptyList(),
    val week: List<DayBar> = emptyList(),
    val pendingChecks: List<PendingCheck> = emptyList(),
    val now: ZonedDateTime = ZonedDateTime.now(),
    /** "How it went": this morning's rings and the last seven days', from the history on this phone. */
    val howToday: HowItWent = HowItWent(),
    val howWeek: HowItWent = HowItWent(),
)

@HiltViewModel
class MorningViewModel @Inject constructor(
    private val history: HistoryRepository,
    wakeChecks: WakeCheckRepository,
    alarms: AlarmRepository,
    settings: SettingsRepository,
    private val checkController: WakeCheckController,
    private val time: TimeSource,
) : ViewModel() {

    val state: StateFlow<MorningUiState> = combine(
        history.observeSince(time.nowMillis() - HISTORY_WINDOW_MS), wakeChecks.observeAll(), alarms.observeAlarms(), settings.settings,
    ) { events, checks, alarmList, s -> build(events, checks, alarmList, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MorningUiState())

    private fun build(events: List<AlarmEvent>, checks: List<WakeCheck>, alarms: List<Alarm>, s: AppSettings): MorningUiState {
        val now = time.now()
        val zone = now.zone
        val stats = WakeStatsCalculator.compute(events, zone)
        val today = now.toLocalDate()
        val byDay = stats.days.associateBy { it.date }
        val week = (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val day = byDay[d]
            DayBar(d, day?.dismissDurationMs?.let { it / 60_000f }, day?.snoozes ?: 0, day?.successful == true, day != null)
        }
        val labels = alarms.associate { it.id to it.label }
        val needsProof = alarms.filter { it.wakeCheck.requireProof }.map { it.id }.toSet()
        val todays = events.filter { java.time.Instant.ofEpochMilli(it.firedAt).atZone(zone).toLocalDate() == today }
        val weekStart = today.minusDays(6)
        val lastWeek = events.filter { !java.time.Instant.ofEpochMilli(it.firedAt).atZone(zone).toLocalDate().isBefore(weekStart) }
        return MorningUiState(
            loaded = true, settings = s, stats = stats,
            howToday = HowItWent.of(todays), howWeek = HowItWent.of(lastWeek),
            today = todays,
            recent = events.sortedByDescending { it.firedAt }.take(30),
            week = week,
            pendingChecks = checks.map { PendingCheck(it, labels[it.alarmId].orEmpty(), cancellable = it.alarmId !in needsProof) },
            now = now,
        )
    }

    fun cancelCheck(alarmId: Long) = viewModelScope.launch { checkController.cancelByUser(alarmId) }
    fun clearHistory() = viewModelScope.launch { history.clear() }

    private companion object {
        const val HISTORY_WINDOW_MS = 120L * 24 * 3_600_000
    }
}
