package com.riyadm.wakenudge.ui.clock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.WorldClockCity
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.repository.WorldClockRepository
import com.riyadm.wakenudge.ui.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class WorldClockViewModel @Inject constructor(
    private val repo: WorldClockRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val cities: StateFlow<List<WorldClockCity>?> = repo.observeCities().map<List<WorldClockCity>, List<WorldClockCity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Every zone the device knows, in "Region/City" form, as pickable candidates (offline, from the JDK tz database). */
    val allZones: List<String> by lazy {
        ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") && !it.startsWith("US/") }.sorted()
    }

    fun add(zoneId: String) = viewModelScope.launch { repo.add(WorldClockCity(zoneId, Formatters.cityName(zoneId))) }
    fun remove(zoneId: String) = viewModelScope.launch { repo.remove(zoneId) }

    companion object {
        val Popular = listOf(
            "Europe/London", "America/New_York", "America/Los_Angeles", "Asia/Tokyo", "Asia/Dubai",
            "Asia/Dhaka", "Asia/Kolkata", "Asia/Singapore", "Australia/Sydney", "Europe/Paris",
        )
    }
}
