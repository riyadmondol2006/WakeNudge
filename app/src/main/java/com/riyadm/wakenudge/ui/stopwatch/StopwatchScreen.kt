package com.riyadm.wakenudge.ui.stopwatch

import androidx.compose.ui.platform.LocalResources
import com.riyadm.wakenudge.ui.util.spokenDuration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.StopwatchState
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.ui.components.topLevelInsets
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.ClockType
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.PolygonShape
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The stopwatch lives in settings storage, so it keeps counting across process death and screen changes. */
@HiltViewModel
class StopwatchViewModel @Inject constructor(
    private val settings: SettingsRepository,
    val time: TimeSource,
) : ViewModel() {
    val state: StateFlow<StopwatchState?> = settings.settings.map<_, StopwatchState?> { it.stopwatch }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun edit(block: (StopwatchState) -> StopwatchState) = viewModelScope.launch {
        settings.update { it.copy(stopwatch = block(it.stopwatch)) }
    }

    fun startOrPause() = edit { s ->
        val now = time.nowMillis()
        if (s.running) s.copy(running = false, accumulatedMs = s.elapsedAt(now)) else s.copy(running = true, startedAtMillis = now)
    }

    fun lap() = edit { s -> if (s.running) s.copy(laps = s.laps + s.elapsedAt(time.nowMillis())) else s }

    fun reset() = edit { StopwatchState() }
}

@Composable
fun StopwatchScreen(modifier: Modifier = Modifier, viewModel: StopwatchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state ?: StopwatchState()
    val haptics = LocalWakeHaptics.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scheme = MaterialTheme.colorScheme

    val elapsed by produceState(s.elapsedAt(System.currentTimeMillis()), s) {
        while (s.running) {
            value = s.elapsedAt(System.currentTimeMillis())
            kotlinx.coroutines.delay(33)
        }
        value = s.elapsedAt(System.currentTimeMillis())
    }

    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = topLevelInsets(),
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.stopwatch_title)) }, scrollBehavior = scroll) },
    ) { padding ->
        val resources = LocalResources.current
        val laps = s.laps
        val splits = laps.mapIndexed { i, t -> if (i == 0) t else t - laps[i - 1] }
        val best = splits.minOrNull()
        val worst = splits.maxOrNull()
        // One list for the readout, the buttons and the laps: in landscape it scrolls (collapsing the big title)
        // instead of cutting the buttons off below a readout that cannot move.
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "readout") {
                val text = TimeText.clockDuration(elapsed, withHundredths = true)
                // One line whatever the font size: past an hour ("1:00:00.00") the readout shrinks to fit the width.
                BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    val fitting = with(LocalDensity.current) { (maxWidth / (text.length * 0.62f)).toSp() }
                    Text(
                        text,
                        style = ClockType.Readout.copy(fontSize = minOf(68f, fitting.value).sp),
                        color = if (s.running) scheme.primary else scheme.onSurface,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.semantics { contentDescription = spokenDuration(resources, elapsed) },
                    )
                }
            }
            item(key = "buttons") {
                Row(Modifier.padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = { haptics.tick(); viewModel.reset() },
                        enabled = elapsed > 0 && !s.running, modifier = Modifier.size(64.dp),
                    ) { Icon(Icons.Outlined.Replay, stringResource(R.string.action_reset)) }
                    FilledIconButton(
                        onClick = { haptics.click(); viewModel.startOrPause() },
                        modifier = Modifier.size(96.dp), shape = PolygonShape(Blobs.Cookie),
                    ) {
                        Icon(if (s.running) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (s.running) R.string.action_pause else R.string.stopwatch_start), Modifier.size(42.dp))
                    }
                    FilledTonalIconButton(
                        onClick = { haptics.tick(); viewModel.lap() },
                        enabled = s.running, modifier = Modifier.size(64.dp),
                    ) { Icon(Icons.Outlined.Flag, stringResource(R.string.stopwatch_lap)) }
                }
            }
            itemsIndexed(splits.asReversed(), key = { reversedIndex, _ -> "lap-${splits.size - reversedIndex}" }) { reversedIndex, split ->
                val number = splits.size - reversedIndex
                val total = laps[number - 1]
                // Fastest and slowest are named, not only coloured.
                val mark = when {
                    splits.size > 1 && split == best -> stringResource(R.string.stopwatch_fastest)
                    splits.size > 1 && split == worst -> stringResource(R.string.stopwatch_slowest)
                    else -> null
                }
                Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.stopwatch_lap_number, number), style = MaterialTheme.typography.titleMedium)
                            if (mark != null) {
                                Text(mark, style = MaterialTheme.typography.labelMedium, color = if (split == best) scheme.primary else scheme.error)
                            }
                        }
                        val color = when {
                            splits.size > 1 && split == best -> scheme.primary
                            splits.size > 1 && split == worst -> scheme.error
                            else -> scheme.onSurface
                        }
                        Text(TimeText.clockDuration(split, true), style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.padding(end = 24.dp))
                        Text(TimeText.clockDuration(total, true), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
