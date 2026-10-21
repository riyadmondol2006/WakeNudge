package com.riyadm.wakenudge.ui.timer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.ui.components.AnimatedClockText
import com.riyadm.wakenudge.ui.components.WavyCircularProgress
import com.riyadm.wakenudge.ui.components.topLevelInsets
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.ClockType
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.PolygonShape
import kotlinx.coroutines.delay

private val Presets = listOf(1, 3, 5, 10, 15, 30, 45, 60)

@Composable
fun TimerScreen(modifier: Modifier = Modifier, viewModel: TimerViewModel = hiltViewModel()) {
    val timers by viewModel.timers.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var creating by remember { mutableStateOf(false) }
    val list = timers

    // Tick only while something is counting down; the screen is otherwise static.
    val anyRunning = list?.any { it.state == TimerState.RUNNING } == true
    val now by produceState(System.currentTimeMillis(), anyRunning) {
        while (anyRunning) {
            value = System.currentTimeMillis()
            delay(100)
        }
        value = System.currentTimeMillis()
    }

    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = topLevelInsets(),
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.timer_title)) }, scrollBehavior = scroll) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { creating = true },
                shape = PolygonShape(Blobs.Cookie),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(84.dp),
            ) { Icon(Icons.Filled.Add, stringResource(R.string.timer_new), Modifier.size(32.dp)) }
        },
    ) { padding ->
        if (list == null) {
            Box(Modifier.fillMaxSize())
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (list.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.timer_quick_start), style = MaterialTheme.typography.titleMedium)
                    }
                }
                item {
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        Presets.forEach { minutes ->
                            AssistChip(
                                onClick = { viewModel.create(minutes * 60_000L, "", true) },
                                label = { Text(stringResource(R.string.unit_minutes, minutes)) },
                            )
                        }
                    }
                }
            }
            items(list, key = { it.id }) { timer ->
                TimerCard(
                    timer = timer, now = now,
                    onStart = { viewModel.start(timer.id) },
                    onPause = { viewModel.pause(timer.id) },
                    onReset = { viewModel.reset(timer.id) },
                    onAddMinute = { viewModel.addMinute(timer.id) },
                    onDelete = { viewModel.delete(timer.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
    if (creating) {
        NewTimerSheet(
            onCreate = { ms, label, start -> viewModel.create(ms, label, start); creating = false },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun TimerCard(
    timer: CountdownTimer,
    now: Long,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onAddMinute: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val remaining = timer.remainingAt(now)
    val running = timer.state == TimerState.RUNNING
    val progress = if (timer.durationMs > 0) remaining.toFloat() / timer.durationMs else 0f
    // Ceil to whole seconds so the display reads 00:01 until the very moment the timer is done.
    val shown = ((remaining + 999) / 1000) * 1000
    val spoken = TimeText.duration(shown)

    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(36.dp), color = if (running) scheme.primaryContainer else scheme.surfaceContainerHigh) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                WavyCircularProgress(progress = progress, size = 240.dp, color = if (running) scheme.primary else scheme.outline, trackColor = scheme.surface.copy(alpha = 0.5f), animate = running)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedClockText(TimeText.clockDuration(shown), ClockType.Readout.copy(fontSize = androidx.compose.ui.unit.TextUnit(52f, androidx.compose.ui.unit.TextUnitType.Sp)), spokenText = spoken)
                    if (timer.label.isNotBlank()) Text(timer.label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onReset, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.Replay, stringResource(R.string.action_reset)) }
                FilledIconButton(onClick = if (running) onPause else onStart, modifier = Modifier.size(80.dp), shape = PolygonShape(Blobs.Cookie)) {
                    Icon(if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (running) R.string.action_pause else R.string.action_resume), Modifier.size(36.dp))
                }
                FilledTonalIconButton(onClick = onAddMinute, modifier = Modifier.size(56.dp)) { Text(stringResource(R.string.action_add_minute), style = MaterialTheme.typography.labelLarge) }
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.action_delete))
            }
        }
    }
}

/** A calculator-style duration entry: digits shift in from the right, like the system clock apps. */
@Composable
private fun NewTimerSheet(onCreate: (Long, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalWakeHaptics.current
    var digits by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val padded = digits.padStart(6, '0')
    val h = padded.substring(0, 2).toInt()
    val m = padded.substring(2, 4).toInt()
    val s = padded.substring(4, 6).toInt()
    val totalMs = ((h * 60L + m) * 60 + s) * 1000
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.semantics { contentDescription = TimeText.duration(totalMs) }) {
                Unit3(h, "h", digits.length >= 5); Unit3(m, "m", digits.length >= 3); Unit3(s, "s", digits.isNotEmpty())
            }
            OutlinedTextField(
                value = label, onValueChange = { if (it.length <= 40) label = it }, singleLine = true,
                label = { Text(stringResource(R.string.timer_label)) }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", "⌫")
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    row.forEach { key ->
                        Surface(
                            onClick = {
                                haptics.tick()
                                digits = when (key) {
                                    "⌫" -> digits.dropLast(1)
                                    else -> (digits + key).trimStart('0').takeLast(6)
                                }
                            },
                            shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.weight(1f).height(60.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (key == "⌫") Icon(Icons.AutoMirrored.Filled.Backspace, stringResource(R.string.cd_backspace)) else Text(key, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onCreate(totalMs, label, false) }, enabled = totalMs > 0, modifier = Modifier.weight(1f).height(56.dp)) { Text(stringResource(R.string.timer_add)) }
                Button(onClick = { onCreate(totalMs, label, true) }, enabled = totalMs > 0, modifier = Modifier.weight(1f).height(56.dp)) { Text(stringResource(R.string.timer_start)) }
            }
        }
    }
}

@Composable
private fun Unit3(value: Int, unit: String, active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = 6.dp)) {
        Text("%02d".format(value), style = ClockType.Readout.copy(fontSize = androidx.compose.ui.unit.TextUnit(56f, androidx.compose.ui.unit.TextUnitType.Sp)), color = color)
        Text(unit, style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.padding(start = 2.dp, bottom = 10.dp))
    }
}
