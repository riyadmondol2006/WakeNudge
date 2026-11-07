package com.riyadm.wakenudge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.ExceptionDate
import com.riyadm.wakenudge.domain.model.Hardness
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.EditLock
import com.riyadm.wakenudge.domain.usecase.ActionResult
import com.riyadm.wakenudge.domain.usecase.GlobalChanges
import com.riyadm.wakenudge.engine.CalendarHolidayProvider
import com.riyadm.wakenudge.engine.DeviceCalendar
import com.riyadm.wakenudge.engine.audio.AlarmVibrator
import com.riyadm.wakenudge.engine.audio.SoundPreviewer
import com.riyadm.wakenudge.ui.util.persist
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val global: GlobalChanges,
    val soundPreviewer: SoundPreviewer,
    val vibrator: AlarmVibrator,
) : ViewModel() {
    private val locks = Channel<EditLock>(Channel.BUFFERED)

    /** Changes refused by edit protection, to be said on screen. */
    val lockNotices: Flow<EditLock> = locks.receiveAsFlow()

    /**
     * Null until the stored settings are read. The pages wait for them instead of showing the defaults for a moment,
     * where a quick tap would act on a switch that is about to flip.
     */
    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The "new alarm defaults" as the alarm they would make, so the shared sections can show their sound and vibration. */
    val defaultsAlarm: StateFlow<Alarm?> = repo.settings.map { s -> s.defaults.newAlarm() }
        .stateIn<Alarm?>(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How hard new alarms start. */
    val defaultLevel: StateFlow<Hardness?> = repo.settings.map<AppSettings, Hardness?> { it.defaults.level }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(block: (AppSettings) -> AppSettings) = viewModelScope.launch { repo.update(block) }

    /** Only the sound and the vibration are taken from [transform]: the level decides the rest. */
    fun updateDefaults(transform: (Alarm) -> Alarm) = viewModelScope.launch {
        repo.update { s ->
            val a = transform(s.defaults.newAlarm())
            s.copy(defaults = s.defaults.copy(sound = a.sound, vibration = a.vibration))
        }
    }

    fun setDefaultLevel(level: Hardness) = viewModelScope.launch {
        if (level != Hardness.CUSTOM) repo.update { it.copy(defaults = it.defaults.copy(level = level)) }
    }

    fun setVacation(enabled: Boolean, resumeEpochDay: Long?) = persist {
        (global.setVacation(enabled, resumeEpochDay) as? ActionResult.Locked)?.let { locks.send(it.lock) }
    }

    override fun onCleared() {
        soundPreviewer.stop()
        vibrator.stop()
    }
}

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groups: GroupRepository,
    private val global: GlobalChanges,
) : ViewModel() {
    private val locks = Channel<EditLock>(Channel.BUFFERED)
    val lockNotices: Flow<EditLock> = locks.receiveAsFlow()

    val state: StateFlow<List<AlarmGroup>> = groups.observeGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) groups.save(AlarmGroup(name = name.trim().take(30), sortOrder = state.value.size))
    }

    fun rename(group: AlarmGroup, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) groups.save(group.copy(name = name.trim().take(30)))
    }

    fun setEnabled(group: AlarmGroup, enabled: Boolean) = persist {
        (global.setGroupEnabled(group.id, enabled) as? ActionResult.Locked)?.let { locks.send(it.lock) }
    }

    fun delete(group: AlarmGroup) = persist {
        (global.deleteGroup(group.id) as? ActionResult.Locked)?.let { locks.send(it.lock) }
    }
}

data class ExceptionsUiState(
    val dates: List<ExceptionDate> = emptyList(),
    val calendarEnabled: Boolean = false,
    val hasCalendarPermission: Boolean = false,
    val calendars: List<DeviceCalendar> = emptyList(),
    val selectedCalendarIds: Set<Long> = emptySet(),
    /** How many days in the next year are skipped because of selected calendars. */
    val calendarHolidayDays: Int = 0,
    val vacation: VacationMode = VacationMode(),
    /** False until the stored settings were read: before that the switches would show defaults, not the user's state. */
    val loaded: Boolean = false,
)

@HiltViewModel
class ExceptionsViewModel @Inject constructor(
    private val exceptions: ExceptionRepository,
    private val settings: SettingsRepository,
    private val calendar: CalendarHolidayProvider,
    private val global: GlobalChanges,
) : ViewModel() {
    private val locks = Channel<EditLock>(Channel.BUFFERED)
    val lockNotices: Flow<EditLock> = locks.receiveAsFlow()

    private val _calendarState = MutableStateFlow(ExceptionsUiState())
    val state: StateFlow<ExceptionsUiState> = _calendarState.asStateFlow()

    init {
        viewModelScope.launch { exceptions.observeDates().collect { d -> _calendarState.value = _calendarState.value.copy(dates = d) } }
        viewModelScope.launch {
            settings.settings.collect { s ->
                // The stored state first, at once: the calendar lookup below can take a while, and a tap on a switch that
                // still showed the default ("vacation off") would have wiped the real resume date.
                _calendarState.value = _calendarState.value.copy(vacation = s.vacation, calendarEnabled = s.calendarExceptions.enabled, loaded = true)
                refreshCalendar(s)
            }
        }
    }

    fun onPermissionResult() = viewModelScope.launch { refreshCalendar(settings.current()) }

    private suspend fun refreshCalendar(s: AppSettings) {
        val has = calendar.hasPermission()
        val (cals, days) = withContext(Dispatchers.IO) {
            val list = calendar.availableCalendars()
            list to calendar.holidayDays(s.calendarExceptions.calendarIds, LocalDate.now(), 366).size
        }
        _calendarState.value = _calendarState.value.copy(
            calendarEnabled = s.calendarExceptions.enabled, hasCalendarPermission = has, calendars = cals,
            selectedCalendarIds = s.calendarExceptions.calendarIds, calendarHolidayDays = days, vacation = s.vacation,
        )
    }

    fun setVacation(enabled: Boolean, resumeEpochDay: Long?) = persist {
        (global.setVacation(enabled, resumeEpochDay) as? ActionResult.Locked)?.let { locks.send(it.lock) }
    }

    fun addDate(epochDay: Long, label: String) = persist {
        (global.addExceptionDate(epochDay, label) as? ActionResult.Locked)?.let { locks.send(it.lock) }
    }

    fun removeDate(epochDay: Long) = persist { global.removeExceptionDate(epochDay) }

    fun setCalendarEnabled(enabled: Boolean) = persist {
        (global.setCalendarExceptions(enabled) as? ActionResult.Locked)?.let { locks.send(it.lock) }
    }

    fun toggleCalendar(id: Long, selected: Boolean) = persist {
        (global.selectCalendar(id, selected) as? ActionResult.Locked)?.let { locks.send(it.lock) }
    }
}
