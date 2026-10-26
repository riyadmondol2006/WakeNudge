package com.riyadm.wakenudge.ui.morning

import androidx.compose.ui.platform.LocalResources
import com.riyadm.wakenudge.ui.util.spokenDuration
import androidx.compose.ui.graphics.drawscope.Stroke
import com.riyadm.wakenudge.ui.util.currentLocale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.ui.components.ConfirmDialog
import com.riyadm.wakenudge.ui.components.HeroCard
import com.riyadm.wakenudge.ui.components.topLevelInsets
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.ClockType
import com.riyadm.wakenudge.ui.theme.PolygonShape
import com.riyadm.wakenudge.ui.util.rememberIs24Hour
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun MorningScreen(modifier: Modifier = Modifier, viewModel: MorningViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirmClear by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = topLevelInsets(),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.morning_title)) },
                scrollBehavior = scroll,
                actions = {
                    if (state.recent.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.morning_clear)) }
                    }
                },
            )
        },
    ) { padding ->
        val stats = state.stats
        if (!state.loaded || stats == null) {
            Box(Modifier.fillMaxSize())
            return@Scaffold
        }
        val is24 = rememberIs24Hour(state.settings.timeFormat)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { GreetingCard(state, is24) }
            state.pendingChecks.forEach { pending ->
                item(key = "check-${pending.check.alarmId}") {
                    Surface(shape = RoundedCornerShape(28.dp), color = scheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.NotificationsActive, null, tint = scheme.onTertiaryContainer)
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                Text(stringResource(R.string.morning_check_pending), style = MaterialTheme.typography.titleMedium, color = scheme.onTertiaryContainer)
                                Text(
                                    if (pending.check.phase == com.riyadm.wakenudge.domain.model.WakeCheck.Phase.WAITING)
                                        stringResource(R.string.morning_check_waiting, TimeText.format(Instant.ofEpochMilli(pending.check.promptAt).atZone(state.now.zone).toLocalTime(), is24))
                                    else stringResource(R.string.morning_check_prompted),
                                    style = MaterialTheme.typography.bodyMedium, color = scheme.onTertiaryContainer,
                                )
                            }
                            TextButton(onClick = { viewModel.cancelCheck(pending.check.alarmId) }) { Text(stringResource(R.string.action_cancel_check)) }
                        }
                    }
                }
            }
            item { StreakCard(stats.currentStreak, stats.bestStreak) }
            item { WeekChart(state.week) }
            item { StatTiles(state) }
            if (state.recent.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.morning_empty), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                item { Text(stringResource(R.string.morning_history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) }
                items(state.recent, key = { it.id }) { event -> HistoryRow(event, is24, state.now.zone) }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.morning_clear_title),
            text = stringResource(R.string.morning_clear_body),
            confirmLabel = stringResource(R.string.morning_clear),
            onConfirm = viewModel::clearHistory, onDismiss = { confirmClear = false }, destructive = true,
        )
    }
}

@Composable
private fun GreetingCard(state: MorningUiState, is24: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val hour = state.now.hour
    val greeting = stringResource(when { hour < 5 -> R.string.morning_greeting_night; hour < 12 -> R.string.morning_greeting_morning; hour < 18 -> R.string.morning_greeting_afternoon; else -> R.string.morning_greeting_evening })
    val latest = state.today.filter { it.outcome != AlarmOutcome.RINGING }.maxByOrNull { it.firedAt }
    HeroCard(color = scheme.secondaryContainer) {
        Column {
            Text(greeting, style = MaterialTheme.typography.headlineLarge, color = scheme.onSecondaryContainer)
            Spacer(Modifier.height(8.dp))
            if (latest == null) {
                Text(stringResource(R.string.morning_nothing_today), style = MaterialTheme.typography.bodyLarge, color = scheme.onSecondaryContainer)
            } else {
                val up = latest.endedAt?.let { TimeText.format(Instant.ofEpochMilli(it).atZone(state.now.zone).toLocalTime(), is24) }
                Text(
                    when (latest.outcome) {
                        AlarmOutcome.DISMISSED -> stringResource(R.string.morning_summary_up, up ?: "", TimeText.duration(latest.dismissDurationMs ?: 0))
                        AlarmOutcome.EMERGENCY_STOPPED -> stringResource(R.string.morning_summary_emergency)
                        else -> stringResource(R.string.morning_summary_missed)
                    },
                    style = MaterialTheme.typography.titleMedium, color = scheme.onSecondaryContainer,
                )
                if (latest.snoozeCount > 0) {
                    Text(pluralStringResource(R.plurals.morning_snoozes, latest.snoozeCount, latest.snoozeCount), style = MaterialTheme.typography.bodyLarge, color = scheme.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
private fun StreakCard(current: Int, best: Int) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(36.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp).clip(PolygonShape(Blobs.Sunny)).background(if (current > 0) scheme.tertiaryContainer else scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.LocalFireDepartment, null, Modifier.size(44.dp), tint = if (current > 0) scheme.onTertiaryContainer else scheme.outline)
            }
            Column(Modifier.padding(start = 20.dp).semantics(mergeDescendants = true) {}) {
                Text("$current", style = ClockType.Card.copy(fontSize = androidx.compose.ui.unit.TextUnit(60f, androidx.compose.ui.unit.TextUnitType.Sp)))
                Text(pluralStringResource(R.plurals.morning_streak_days, current), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.morning_streak_best, best), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WeekChart(week: List<DayBar>) {
    val scheme = MaterialTheme.colorScheme
    val max = (week.mapNotNull { it.dismissMinutes }.maxOrNull() ?: 10f).coerceAtLeast(10f)
    val locale = currentLocale()
    val resources = LocalResources.current
    val noAlarm = stringResource(R.string.morning_chart_no_alarm)
    val notClean = stringResource(R.string.morning_chart_not_clean)
    // What the bars show, in words: the day, how long getting up took, and whether it was a clean wake-up.
    val summary = week.joinToString("; ") { d ->
        val value = d.dismissMinutes?.let { minutes ->
            spokenDuration(resources, (minutes * 60_000).toLong()) + if (d.successful) "" else ", $notClean"
        } ?: noAlarm
        resources.getString(R.string.morning_chart_day, d.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale), value)
    }
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.morning_week_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.morning_week_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            val primary = scheme.primary
            val error = scheme.error
            val onError = scheme.onError
            val track = scheme.surfaceContainerHighest
            Canvas(Modifier.fillMaxWidth().height(130.dp).padding(top = 12.dp).semantics { contentDescription = summary }) {
                val slot = size.width / week.size
                val barWidth = slot * 0.5f
                week.forEachIndexed { i, d ->
                    val x = slot * i + (slot - barWidth) / 2
                    drawRoundRect(track, Offset(x, 0f), Size(barWidth, size.height), CornerRadius(barWidth / 2))
                    if (d.dismissMinutes != null) {
                        val h = (size.height * (d.dismissMinutes / max)).coerceAtLeast(barWidth)
                        drawRoundRect(if (d.successful) primary else error, Offset(x, size.height - h), Size(barWidth, h), CornerRadius(barWidth / 2))
                        if (!d.successful) {
                            // A ring at the top marks a morning that was not clean, so colour is not the only sign.
                            drawCircle(
                                onError, radius = barWidth * 0.22f, center = Offset(x + barWidth / 2, size.height - h + barWidth / 2),
                                style = Stroke(width = barWidth * 0.12f),
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                week.forEach { d ->
                    Text(
                        d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, currentLocale()), style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTiles(state: MorningUiState) {
    val stats = state.stats ?: return
    val items = listOf(
        stringResource(R.string.morning_stat_success) to "${(stats.successRate * 100).toInt()}%",
        stringResource(R.string.morning_stat_avg_up) to (stats.averageDismissMs?.let { TimeText.duration(it) } ?: "–"),
        stringResource(R.string.morning_stat_snoozes) to "%.1f".format(stats.averageSnoozes),
        stringResource(R.string.morning_stat_checks) to "${stats.checksPassed} / ${stats.checksPassed + stats.checksFailed}",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(18.dp)) {
                            Text(value, style = MaterialTheme.typography.headlineMedium)
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(event: AlarmEvent, is24: Boolean, zone: java.time.ZoneId) {
    val scheme = MaterialTheme.colorScheme
    val fired = Instant.ofEpochMilli(event.firedAt).atZone(zone)
    val (icon: ImageVector, tint, outcome) = when {
        event.outcome == AlarmOutcome.DISMISSED && event.wakeCheck == WakeCheckStatus.FAILED ->
            Triple(Icons.Outlined.ErrorOutline, scheme.error, stringResource(R.string.outcome_check_failed))
        event.outcome == AlarmOutcome.DISMISSED -> Triple(Icons.Outlined.CheckCircle, scheme.primary, stringResource(R.string.outcome_dismissed))
        event.outcome == AlarmOutcome.EMERGENCY_STOPPED -> Triple(Icons.Outlined.PanTool, scheme.tertiary, stringResource(R.string.outcome_emergency))
        event.outcome == AlarmOutcome.MISSED -> Triple(Icons.Outlined.ErrorOutline, scheme.error, stringResource(R.string.outcome_missed))
        else -> Triple(Icons.Outlined.NotificationsActive, scheme.outline, stringResource(R.string.outcome_ringing))
    }
    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    event.label.ifBlank { TimeText.format(Instant.ofEpochMilli(event.scheduledAt).atZone(zone).toLocalTime(), is24) },
                    style = MaterialTheme.typography.titleSmall,
                )
                val details = buildList {
                    add(fired.dayOfWeek.getDisplayName(TextStyle.SHORT, currentLocale()) + " " + fired.dayOfMonth + " · " + TimeText.format(fired.toLocalTime(), is24))
                    event.dismissDurationMs?.let { if (event.outcome == AlarmOutcome.DISMISSED) add(TimeText.duration(it)) }
                    if (event.snoozeCount > 0) add(pluralStringResource(R.plurals.morning_snoozes, event.snoozeCount, event.snoozeCount))
                    if (event.backupFired) add(stringResource(R.string.badge_backup_fired))
                    if (event.wakeCheck == WakeCheckStatus.PASSED) add(stringResource(R.string.badge_check_passed))
                }
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Text(outcome, style = MaterialTheme.typography.labelMedium, color = tint)
        }
    }
}
