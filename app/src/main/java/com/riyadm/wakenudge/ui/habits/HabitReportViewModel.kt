package com.riyadm.wakenudge.ui.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.habits.HabitData
import com.riyadm.wakenudge.domain.habits.HabitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HabitReportUiState(
    val data: HabitData,
    val today: LocalDate,
    val weekStart: LocalDate,
    val earlierWeeks: Int,
)

@HiltViewModel
class HabitReportViewModel @Inject constructor(
    private val repository: HabitRepository,
    private val time: TimeSource,
) : ViewModel() {
    private val earlierWeeks = MutableStateFlow(0)

    val state: StateFlow<HabitReportUiState?> = combine(repository.observe(), earlierWeeks) { data, earlier ->
        val today = time.now().toLocalDate()
        val thisWeek = today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        HabitReportUiState(data, today, thisWeek.minusWeeks(earlier.toLong()), earlier)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun earlier() { earlierWeeks.value++ }
    fun later() { earlierWeeks.value = (earlierWeeks.value - 1).coerceAtLeast(0) }

    fun toggle(id: Long, date: LocalDate) = viewModelScope.launch {
        val today = time.now().toLocalDate()
        val done = repository.data().doneDates(id).contains(date.toEpochDay())
        repository.setDone(id, date, !done, today)
    }
}
