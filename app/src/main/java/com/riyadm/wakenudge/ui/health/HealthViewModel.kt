package com.riyadm.wakenudge.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.domain.repository.DiagnosticRecord
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.health.DiagnosticsExporter
import com.riyadm.wakenudge.health.HealthChecker
import com.riyadm.wakenudge.health.HealthReport
import com.riyadm.wakenudge.health.Severity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HealthViewModel @Inject constructor(
    private val checker: HealthChecker,
    private val exporter: DiagnosticsExporter,
    private val scheduler: AlarmScheduler,
    private val log: DiagnosticsLog,
) : ViewModel() {
    private val _report = MutableStateFlow<HealthReport?>(null)
    val report: StateFlow<HealthReport?> = _report.asStateFlow()

    val events: StateFlow<List<DiagnosticRecord>> = log.observeRecent(60).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _testArmedAt = MutableStateFlow(0L)

    /** Epoch millis the last test alarm will ring, or 0. */
    val testArmedAt: StateFlow<Long> = _testArmedAt.asStateFlow()

    fun refresh() {
        viewModelScope.launch { _report.value = checker.check() }
    }

    fun armTestAlarm(seconds: Int) {
        viewModelScope.launch {
            _testArmedAt.value = scheduler.scheduleTestAlarm(seconds)
            refresh()
        }
    }

    suspend fun buildReport(): String = exporter.buildReport()

    fun clearLog() {
        viewModelScope.launch { log.clear() }
    }
}

/** Tiny view model behind the dot on the Alarms screen's health icon and the "fix this now" banner. */
@HiltViewModel
class HealthBadgeViewModel @Inject constructor(private val checker: HealthChecker) : ViewModel() {
    private val _worst = MutableStateFlow(Severity.OK)
    val worst: StateFlow<Severity> = _worst.asStateFlow()

    private val _urgent = MutableStateFlow<com.riyadm.wakenudge.health.HealthItem?>(null)

    /** The most serious problem that can stop an alarm from showing or sounding, if any. */
    val urgent: StateFlow<com.riyadm.wakenudge.health.HealthItem?> = _urgent.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val report = checker.check()
            _worst.value = report.worstSeverity
            _urgent.value = report.bySeverity.firstOrNull { it.severity >= Severity.WARNING && it.id in URGENT_IDS && it.fix != null }
        }
    }

    private companion object {
        /** Problems the user can fix in one tap and that directly affect whether the alarm is seen or heard. */
        val URGENT_IDS = setOf("notifications", "ring_channel", "full_screen", "dnd", "alarm_volume")
    }
}
