package com.riyadm.wakenudge.ui.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.habits.Habit
import com.riyadm.wakenudge.domain.habits.HabitData
import com.riyadm.wakenudge.domain.habits.HabitRepository
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.engine.habits.HabitNotifier
import com.riyadm.wakenudge.engine.habits.HabitReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HabitsViewModel @Inject constructor(
    private val repository: HabitRepository,
    private val scheduler: HabitReminderScheduler,
    private val notifier: HabitNotifier,
    private val time: TimeSource,
    settings: SettingsRepository,
) : ViewModel() {
    val data: StateFlow<HabitData?> = repository.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _today = MutableStateFlow(time.now().toLocalDate())
    val today: StateFlow<LocalDate> = _today.asStateFlow()
    private val _notifications = MutableStateFlow(true)
    val notifications: StateFlow<Boolean> = _notifications.asStateFlow()
    private val _limitMessage = MutableSharedFlow<Unit>()
    val limitMessage = _limitMessage.asSharedFlow()

    init {
        viewModelScope.launch { scheduler.rescheduleAll() }
    }

    fun refresh() {
        _today.value = time.now().toLocalDate()
        _notifications.value = notifier.canShow()
    }

    fun toggle(id: Long, date: LocalDate) = viewModelScope.launch {
        val current = _today.value
        val done = repository.data().doneDates(id).contains(date.toEpochDay())
        repository.setDone(id, date, !done, current)
    }

    fun save(habit: Habit, onSaved: () -> Unit) = viewModelScope.launch {
        val id = repository.save(habit)
        if (id == null) {
            _limitMessage.emit(Unit)
        } else {
            scheduler.schedule(habit.copy(id = id))
            onSaved()
        }
    }

    fun delete(id: Long) = viewModelScope.launch {
        repository.delete(id)
        scheduler.cancel(id)
    }

    fun move(id: Long, upwards: Boolean) = viewModelScope.launch { repository.move(id, upwards) }
}
