package com.riyadm.wakenudge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmDefaults
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.ExceptionDate
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.engine.CalendarHolidayProvider
import com.riyadm.wakenudge.engine.DeviceCalendar
import com.riyadm.wakenudge.engine.audio.AlarmVibrator
import com.riyadm.wakenudge.engine.audio.SoundPreviewer
import com.riyadm.wakenudge.ui.util.persist
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
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
    private val scheduler: AlarmScheduler,
    val soundPreviewer: SoundPreviewer,
    val vibrator: AlarmVibrator,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** The "new alarm defaults" shown through the same editor sections as a real alarm. */
    val defaultsAlarm: StateFlow<Alarm> = repo.settings.map { s ->
        val d = s.defaults
        Alarm(sound = d.sound, volume = d.volume, vibration = d.vibration, snooze = d.snooze, antiOversleep = d.antiOversleep)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Alarm())

    fun update(block: (AppSettings) -> AppSettings) = viewModelScope.launch { repo.update(block) }

    fun updateDefaults(transform: (Alarm) -> Alarm) = viewModelScope.launch {
        repo.update { s ->
            val d = s.defaults
            val a = transform(Alarm(sound = d.sound, volume = d.volume, vibration = d.vibration, snooze = d.snooze, antiOversleep = d.antiOversleep))
            s.copy(defaults = AlarmDefaults(a.sound, a.volume, a.vibration, a.snooze, a.antiOversleep))
        }
    }

    fun setVacation(enabled: Boolean, resumeEpochDay: Long?) = persist {
        repo.update { it.copy(vacation = it.vacation.copy(enabled = enabled, resumeEpochDay = resumeEpochDay)) }
        scheduler.rescheduleAll("vacation")
    }

    override fun onCleared() {
        soundPreviewer.stop()
        vibrator.stop()
    }
}

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groups: GroupRepository,
    private val scheduler: AlarmScheduler,
) : ViewModel() {
    val state: StateFlow<List<AlarmGroup>> = groups.observeGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) groups.save(AlarmGroup(name = name.trim().take(30), sortOrder = state.value.size))
    }

    fun rename(group: AlarmGroup, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) groups.save(group.copy(name = name.trim().take(30)))
    }

    fun setEnabled(group: AlarmGroup, enabled: Boolean) = persist {
        groups.setEnabled(group.id, enabled)
        scheduler.rescheduleAll("group-toggle")
    }

    fun delete(group: AlarmGroup) = persist {
        groups.delete(group.id)
        scheduler.rescheduleAll("group-delete")
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
    private val scheduler: AlarmScheduler,
) : ViewModel() {
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
        settings.update { it.copy(vacation = it.vacation.copy(enabled = enabled, resumeEpochDay = resumeEpochDay)) }
        scheduler.rescheduleAll("vacation")
    }

    fun addDate(epochDay: Long, label: String) = persist {
        exceptions.add(ExceptionDate(epochDay, label.trim().take(40)))
        scheduler.rescheduleAll("exceptions")
    }

    fun removeDate(epochDay: Long) = persist {
        exceptions.remove(epochDay)
        scheduler.rescheduleAll("exceptions")
    }

    fun setCalendarEnabled(enabled: Boolean) = persist {
        settings.update { it.copy(calendarExceptions = it.calendarExceptions.copy(enabled = enabled)) }
        scheduler.rescheduleAll("calendar-exceptions")
    }

    fun toggleCalendar(id: Long, selected: Boolean) = persist {
        settings.update {
            val ids = it.calendarExceptions.calendarIds
            it.copy(calendarExceptions = it.calendarExceptions.copy(calendarIds = if (selected) ids + id else ids - id))
        }
        scheduler.rescheduleAll("calendar-exceptions")
    }
}
