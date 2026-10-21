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

    init {
        viewModelScope.launch {
            val restored = handle.get<String>(KEY_DRAFT)?.let {
                runCatching { WakeJson.instance.decodeFromString(Alarm.serializer(), it) }.getOrNull()
            }
            val existing = if (route.alarmId != 0L) alarms.getAlarm(route.alarmId) else null
            stored = existing
            val base = restored ?: existing ?: newAlarm(settingsRepo.current())
            val locked = existing?.let { actions.lockedMinutes(it) }
            _state.value = EditUiState(
                loaded = true, draft = base, isNew = existing == null, lockedMinutes = locked,
                dirty = restored != null,
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

    private fun refreshUpcoming(alarm: Alarm) {
        viewModelScope.launch {
            val ctx = contextProvider.load().forAlarm(alarm.copy(enabled = true))
            val list = calculator.upcoming(alarm.copy(enabled = true), time.now(), ctx, count = 3)
            _state.value = _state.value.copy(upcoming = list)
        }
    }

    fun overrideLock() {
        _state.value = _state.value.copy(lockOverridden = true)
    }

    suspend fun save(): SaveOutcome {
        val s = _state.value
        val original = stored
        val d = s.draft
        // Setting a time or schedule means "ring for me" (enable); fixing a typo in the label of an alarm the user
        // switched off must not quietly turn it back on.
        val scheduleChanged = original == null || original.hour != d.hour || original.minute != d.minute ||
            original.repeatDaysMask != d.repeatDaysMask || original.oneTimeEpochDay != d.oneTimeEpochDay
        val result = actions.save(d.copy(enabled = if (scheduleChanged) true else original!!.enabled), overrideLock = s.lockOverridden || s.lockedMinutes == null)
        return when (result) {
            is ActionResult.Locked -> SaveOutcome.Locked(result.minutes)
            else -> {
                handle.remove<String>(KEY_DRAFT)
                SaveOutcome.Saved
            }
        }
    }

    suspend fun delete(): SaveOutcome {
        val s = _state.value
        return when (val r = actions.delete(s.draft.id, overrideLock = s.lockOverridden || s.lockedMinutes == null)) {
            is ActionResult.Locked -> SaveOutcome.Locked(r.minutes)
            else -> SaveOutcome.Saved
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
