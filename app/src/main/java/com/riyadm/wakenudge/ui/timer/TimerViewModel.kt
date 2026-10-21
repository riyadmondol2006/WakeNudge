package com.riyadm.wakenudge.ui.timer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.repository.TimerRepository
import com.riyadm.wakenudge.engine.TimerController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TimerViewModel @Inject constructor(
    repo: TimerRepository,
    settings: SettingsRepository,
    private val controller: TimerController,
) : ViewModel() {
    /** Null until the first read, so the screen can avoid flashing its empty state. */
    val timers: StateFlow<List<CountdownTimer>?> =
        repo.observeTimers().map<List<CountdownTimer>, List<CountdownTimer>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun create(durationMs: Long, label: String, start: Boolean) = viewModelScope.launch { controller.create(durationMs, label, start) }
    fun start(id: Long) = viewModelScope.launch { controller.start(id) }
    fun pause(id: Long) = viewModelScope.launch { controller.pause(id) }
    fun reset(id: Long) = viewModelScope.launch { controller.reset(id) }
    fun addMinute(id: Long) = viewModelScope.launch { controller.addMinute(id) }
    fun delete(id: Long) = viewModelScope.launch { controller.delete(id) }
}
