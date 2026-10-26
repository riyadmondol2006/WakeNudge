package com.riyadm.wakenudge.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.domain.usecase.ActionResult
import com.riyadm.wakenudge.domain.usecase.AlarmActions
import com.riyadm.wakenudge.engine.SchedulingContextProvider
import com.riyadm.wakenudge.engine.audio.AlarmVibrator
import com.riyadm.wakenudge.engine.audio.SoundPreviewer
import com.riyadm.wakenudge.ui.nav.AlarmEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EditUiState(
    val loaded: Boolean = false,
    val draft: Alarm = Alarm(),
    val isNew: Boolean = true,
    /** Minutes of last-minute protection left on the stored alarm, or null when editable. */
    val lockedMinutes: Int? = null,
    val lockOverridden: Boolean = false,
    val dirty: Boolean = false,
    /** The next few rings for the draft, shown under the time picker. */
    val upcoming: List<ZonedDateTime> = emptyList(),
) {
    val readOnly: Boolean get() = lockedMinutes != null && !lockOverridden
}

sealed interface SaveOutcome {
    data object Saved : SaveOutcome
    data class Locked(val minutes: Int) : SaveOutcome

    /** The alarm was deleted while the editor was open: it rang and deleted itself after use, or was removed elsewhere. */
    data object Gone : SaveOutcome

    /** A save already ran or is running (a double tap): nothing more to do, and above all nothing to save twice. */
    data object Busy : SaveOutcome
}

@HiltViewModel
class AlarmEditViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val alarms: AlarmRepository,
    private val actions: AlarmActions,
    private val settingsRepo: SettingsRepository,
    private val calculator: NextTriggerCalculator,
    private val contextProvider: SchedulingContextProvider,
    private val time: TimeSource,
    val soundPreviewer: SoundPreviewer,
    val vibrator: AlarmVibrator,
    groupRepo: GroupRepository,
) : ViewModel() {

    private val route = handle.toRoute<AlarmEditRoute>()
    private val _state = MutableStateFlow(EditUiState())
    val state: StateFlow<EditUiState> = _state.asStateFlow()
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val groups: StateFlow<List<AlarmGroup>> = groupRepo.observeGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var stored: Alarm? = null

    /** Set while a save or delete runs, and kept once one succeeded, so a second tap can never act again. */
    private var busy = false
    private var finished = false

    init {
        viewModelScope.launch {
            val restored = handle.get<String>(KEY_DRAFT)?.let {
                runCatching { WakeJson.instance.decodeFromString(Alarm.serializer(), it) }.getOrNull()
            }
            val existing = if (route.alarmId != 0L) alarms.getAlarm(route.alarmId) else null
            stored = existing
            val base = restored ?: existing ?: newAlarm(settingsRepo.current())
            val locked = existing?.let { actions.lockedMinutes(it) }
            // A new alarm that arrives filled in (from the sleep planner, an assistant or another app) is the user's
            // request already: leaving without saving should ask, like any other unsaved change.
            val prefilled = existing == null && route.hour in 0..23
            _state.value = EditUiState(
                loaded = true, draft = base, isNew = existing == null, lockedMinutes = locked,
                dirty = restored != null || prefilled,
            )
            refreshUpcoming(base)
        }
    }

    private fun newAlarm(settings: AppSettings): Alarm {
        val d = settings.defaults
        return Alarm(
            hour = route.hour.takeIf { it in 0..23 } ?: 7,
            minute = route.minute.takeIf { it in 0..59 } ?: 0,
            label = route.label,
            repeatDaysMask = route.daysMask.takeIf { it >= 0 } ?: 0,
            sound = d.sound, volume = d.volume, vibration = d.vibration, snooze = d.snooze, antiOversleep = d.antiOversleep,
            upcomingNoticeMinutes = 0,
        )
    }

    fun update(transform: (Alarm) -> Alarm) {
        val s = _state.value
        if (s.readOnly) return
        val next = transform(s.draft)
        handle[KEY_DRAFT] = WakeJson.instance.encodeToString(Alarm.serializer(), next)
        _state.value = s.copy(draft = next, dirty = true)
        refreshUpcoming(next)
    }

    private var upcomingJob: Job? = null

    private fun refreshUpcoming(alarm: Alarm) {
        // Each wheel step asks again; an older answer (a slow calendar lookup) must not land after a newer one.
        upcomingJob?.cancel()
        upcomingJob = viewModelScope.launch {
            val ctx = contextProvider.load().forAlarm(alarm.copy(enabled = true))
            val list = calculator.upcoming(alarm.copy(enabled = true), time.now(), ctx, count = 3)
            _state.value = _state.value.copy(upcoming = list)
        }
    }

    fun overrideLock() {
        _state.value = _state.value.copy(lockOverridden = true)
    }

    /**
     * Saves the draft. [asNew] saves an alarm that was deleted meanwhile (see [SaveOutcome.Gone]) as a new one.
     *
     * The draft is compared with the alarm as it is stored now, not as it was when the editor opened: while the editor
     * was open the alarm may have rung and switched itself off, been switched off or skipped from a widget or a
     * notification, or deleted itself after use. Saving a label fix must not undo any of that.
     */
    suspend fun save(asNew: Boolean = false): SaveOutcome {
        if (busy || finished) return SaveOutcome.Busy
        busy = true
        try {
            val s = _state.value
            val d = s.draft
            val current = stored?.let { alarms.getAlarm(it.id) }
            if (stored != null && current == null && !asNew) return SaveOutcome.Gone
            // Setting a time or schedule means "ring for me" (enable) and replaces a pending "skip next"; fixing a typo
            // in the label of an alarm the user switched off must not quietly turn it back on.
            val scheduleChanged = current == null || current.hour != d.hour || current.minute != d.minute ||
                current.repeatDaysMask != d.repeatDaysMask || current.oneTimeEpochDay != d.oneTimeEpochDay
            val toSave = if (current == null) {
                d.copy(id = 0, enabled = true, skipEpochDay = null)
            } else {
                d.copy(
                    enabled = if (scheduleChanged) true else current.enabled,
                    skipEpochDay = if (scheduleChanged) null else current.skipEpochDay,
                )
            }
            // The edit lock is checked against the alarm now: an editor opened at 06:25 is still locked at 06:50.
            return when (val result = actions.save(toSave, overrideLock = s.lockOverridden)) {
                is ActionResult.Locked -> {
                    _state.value = _state.value.copy(lockedMinutes = result.minutes, lockOverridden = false)
                    SaveOutcome.Locked(result.minutes)
                }
                is ActionResult.Done -> {
                    finished = true
                    handle.remove<String>(KEY_DRAFT)
                    SaveOutcome.Saved
                }
                ActionResult.NotFound -> SaveOutcome.Gone
            }
        } finally {
            busy = false
        }
    }

    suspend fun delete(): SaveOutcome {
        if (busy || finished) return SaveOutcome.Busy
        busy = true
        try {
            val s = _state.value
            return when (val r = actions.delete(s.draft.id, overrideLock = s.lockOverridden)) {
                is ActionResult.Locked -> {
                    _state.value = _state.value.copy(lockedMinutes = r.minutes, lockOverridden = false)
                    SaveOutcome.Locked(r.minutes)
                }
                else -> {
                    finished = true
                    handle.remove<String>(KEY_DRAFT)
                    SaveOutcome.Saved
                }
            }
        } finally {
            busy = false
        }
    }

    override fun onCleared() {
        soundPreviewer.stop()
        vibrator.stop()
    }

    private companion object {
        const val KEY_DRAFT = "draft"
    }
}
