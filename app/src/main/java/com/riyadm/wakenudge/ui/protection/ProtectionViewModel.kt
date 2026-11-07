package com.riyadm.wakenudge.ui.protection

import android.content.Intent
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.engine.RingSessionStore
import com.riyadm.wakenudge.engine.protection.DeviceAdmin
import com.riyadm.wakenudge.engine.protection.Rehearsals
import com.riyadm.wakenudge.health.HealthChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One alarm with a mission that the rehearsal step can ring. */
data class RehearsalChoice(val alarm: Alarm, val rehearsed: Boolean)

data class ProtectionUiState(
    val inputs: ProtectionInputs? = null,
    val rehearsals: List<RehearsalChoice> = emptyList(),
    /** A one-off line to say (a rehearsal armed, admin switched off); a string resource. */
    val notice: Int? = null,
)

/**
 * The Protection setup. Every value is read from Android again on [refresh], which the screen calls each time it comes
 * back to the front, because every step happens in a Settings screen the app cannot watch. How far the overlay steps
 * got is kept in [SavedStateHandle], so leaving for Settings and coming back, even after the process was killed, carries on.
 */
@HiltViewModel
class ProtectionViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val health: HealthChecker,
    private val admin: DeviceAdmin,
    private val rehearsals: Rehearsals,
    private val alarms: AlarmRepository,
    private val history: HistoryRepository,
    private val sessions: RingSessionStore,
    private val time: TimeSource,
) : ViewModel() {
    private val _state = MutableStateFlow(ProtectionUiState())
    val state: StateFlow<ProtectionUiState> = _state.asStateFlow()

    private var stage: OverlayStage
        get() = handle.get<String>(KEY_STAGE)?.let { runCatching { OverlayStage.valueOf(it) }.getOrNull() } ?: OverlayStage.NOT_TRIED
        set(value) { handle[KEY_STAGE] = value.name }

    fun refresh() = viewModelScope.launch {
        val snapshot = health.snapshot()
        val all = guarded(emptyList()) { alarms.getAlarms() }
        val inputs = ProtectionInputs(
            sdkInt = Build.VERSION.SDK_INT,
            overlayGranted = snapshot.canDrawOverlays,
            overlayStage = stage,
            notificationsOk = snapshot.postNotificationsGranted && snapshot.notificationsEnabled,
            fullScreenOk = snapshot.fullScreenIntentAllowed,
            batteryExempt = snapshot.ignoringBatteryOptimizations,
            anyRehearsalFinished = rehearsals.anyFinished,
            adminActive = admin.isActive(),
            ringingOrSnoozed = ringingOrSnoozed(),
        )
        if (inputs.overlayGranted && stage != OverlayStage.NOT_TRIED) stage = OverlayStage.NOT_TRIED
        _state.value = _state.value.copy(
            inputs = inputs,
            rehearsals = all.filter { it.missions.isNotEmpty() }.map { RehearsalChoice(it, rehearsals.isRehearsed(it)) },
        )
    }

    /**
     * Only what is known for sure counts: a ring on now, or a snooze still to come. Storage that cannot be read counts as
     * "no", so a failed read never keeps device admin switched on.
     */
    private suspend fun ringingOrSnoozed(): Boolean {
        if (sessions.current != null) return true
        val now = time.nowMillis()
        return guarded(false) { history.withFollowUps().any { it.snoozeUntil > now } }
    }

    fun overlayOpened() {
        if (stage == OverlayStage.NOT_TRIED) stage = OverlayStage.OPENED
        refresh()
    }

    fun appInfoOpened() {
        stage = OverlayStage.APP_INFO_OPENED
        refresh()
    }

    fun rehearse(alarm: Alarm) {
        val at = guarded(null) { rehearsals.start(alarm) }
        _state.value = _state.value.copy(notice = if (at != null) com.riyadm.wakenudge.R.string.protection_rehearsal_armed else com.riyadm.wakenudge.R.string.protection_rehearsal_failed)
    }

    fun adminIntent(explanation: String): Intent = admin.activationIntent(explanation)

    /** Refused while an alarm rings or is snoozed; the screen then names the system route, which always works. */
    fun turnOffAdmin() = viewModelScope.launch {
        val inputs = _state.value.inputs
        if (inputs != null && !ProtectionPolicy.admin(inputs.copy(ringingOrSnoozed = ringingOrSnoozed())).canTurnOff) {
            _state.value = _state.value.copy(notice = com.riyadm.wakenudge.R.string.protection_admin_wait)
            refresh()
            return@launch
        }
        val done = admin.deactivate()
        _state.value = _state.value.copy(
            notice = if (done) com.riyadm.wakenudge.R.string.protection_admin_turned_off else com.riyadm.wakenudge.R.string.protection_admin_refused,
        )
        // Android takes a moment to let go of an admin.
        refresh()
        delay(ADMIN_SETTLE_MS)
        refresh()
    }

    fun noticeShown() {
        _state.value = _state.value.copy(notice = null)
    }

    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        fallback
    }

    private companion object {
        const val KEY_STAGE = "overlay_stage"
        const val ADMIN_SETTLE_MS = 600L
    }
}
