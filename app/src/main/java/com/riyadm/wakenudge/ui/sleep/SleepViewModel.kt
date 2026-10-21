package com.riyadm.wakenudge.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.BedtimeSettings
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SleepViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val scheduler: AlarmScheduler,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** The wake time of the user's next alarm, offered as the planner's starting point. */
    private val _nextWake = MutableStateFlow<LocalTime?>(null)
    val nextWake: StateFlow<LocalTime?> = _nextWake.asStateFlow()

    init {
        viewModelScope.launch { _nextWake.value = scheduler.nextAlarm()?.at?.toLocalTime() }
    }

    fun updateBedtime(block: (BedtimeSettings) -> BedtimeSettings) = viewModelScope.launch {
        settingsRepo.update { it.copy(bedtime = block(it.bedtime)) }
        scheduler.refreshBedtime()
    }
}
