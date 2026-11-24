package com.riyadm.wakenudge.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.WakeCheck
import com.riyadm.wakenudge.domain.stats.HowItWent
import com.riyadm.wakenudge.ui.components.ConfirmDialog
import com.riyadm.wakenudge.ui.report.wakeup.DailyReportSheet
import com.riyadm.wakenudge.ui.report.wakeup.WakeUpReportUiState
import com.riyadm.wakenudge.ui.report.wakeup.WakeUpReportViewModel
import com.riyadm.wakenudge.ui.report.wakeup.WakeWeekChart
import com.riyadm.wakenudge.ui.util.Formatters
import com.riyadm.wakenudge.ui.util.currentLocale
import com.riyadm.wakenudge.ui.util.rememberIs24Hour
import java.time.Instant

/** Wake-up report: the week at a glance, then the morning record (streaks, checks, how it went, clear). */
@Composable
fun WakeUpReportSection(modifier: Modifier = Modifier, viewModel: WakeUpReportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val is24 = rememberIs24Hour(state.settings.timeFormat)

    if (!state.loaded || state.week == null || state.stats == null) {
        Box(modifier.fillMaxSize())
        return
    }
    val week = state.week!!
    val locale = currentLocale()
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReportWeekPicker(
                        start = week.start,
                        weeksBack = (java.time.LocalDate.now().toEpochDay() - week.start.toEpochDay()).coerceAtLeast(0) / 7,
                        canGoForward = state.canGoNext,
                        onPrevious = viewModel::previousWeek,
                        onNext = viewModel::nextWeek,
                        detail = pluralStringResource(R.plurals.wakereport_mornings_count, week.wakeUpCount, week.wakeUpCount),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Headline(
                            label = stringResource(R.string.wakereport_avg_wake_time),
                            value = week.averageWakeUpTime?.let { TimeText.format(it, is24, locale) } ?: "–",
                            modifier = Modifier.weight(1f),
                        )
                        Headline(
                            label = stringResource(R.string.wakereport_avg_time_to_wake),
                            value = week.averageTimeToWakeMs?.let { TimeText.duration(it) } ?: "–",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (week.wakeUpCount == 0) {
                        Text(
                            stringResource(if (state.isThisWeek) R.string.wakereport_no_mornings else R.string.wakereport_no_mornings_week),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    WakeWeekChart(week = week, today = state.today, is24 = is24, onDayClick = viewModel::openDaily)
                    Button(
                        onClick = {
                            viewModel.openDaily(
                                when {
                                    state.isThisWeek -> state.today
                                    else -> week.days.lastOrNull { it.wakeUp != null }?.date ?: week.start
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.wakereport_view_daily)) }
                }
            }
        }
        state.pendingChecks.forEach { pending ->
            item(key = "check-${pending.check.alarmId}") {
                PendingCheckCard(state, pending, viewModel::cancelCheck)
            }
        }
        if (state.howWeek.rings > 0) {
            item { HowItWentCard(state.howToday, state.howWeek) }
        }
        item { StreakCard(state.stats!!.currentStreak, state.stats!!.bestStreak) }
        if (state.stats!!.totalWakeUps > 0) {
            item {
                TextButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Outlined.DeleteSweep, null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.wakereport_clear_history))
                }
            }
        }
    }
    state.dailyReport?.let { day ->
        DailyReportSheet(day = day, zone = state.zone, is24 = is24, onDismiss = viewModel::closeDaily)
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.morning_clear_title),
            text = stringResource(R.string.morning_clear_body),
            confirmLabel = stringResource(R.string.morning_clear),
            onConfirm = viewModel::clearHistory,
            onDismiss = { confirmClear = false },
            destructive = true,
        )
    }
}

@Composable
private fun Headline(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PendingCheckCard(state: WakeUpReportUiState, pending: com.riyadm.wakenudge.ui.report.wakeup.PendingCheck, onCancel: (Long) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val is24 = rememberIs24Hour(state.settings.timeFormat)
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.NotificationsActive, null, tint = scheme.onTertiaryContainer)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.morning_check_pending), style = MaterialTheme.typography.titleMedium, color = scheme.onTertiaryContainer)
                Text(
                    if (pending.check.phase == WakeCheck.Phase.WAITING) {
                        stringResource(
                            R.string.morning_check_waiting,
                            TimeText.format(Instant.ofEpochMilli(pending.check.promptAt).atZone(state.zone).toLocalTime(), is24),
                        )
                    } else {
                        stringResource(R.string.morning_check_prompted)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onTertiaryContainer,
                )
            }
            if (pending.cancellable) {
                TextButton(onClick = { onCancel(pending.check.alarmId) }) { Text(stringResource(R.string.action_cancel_check)) }
            }
        }
    }
}

@Composable
private fun HowItWentCard(today: HowItWent, week: HowItWent) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.morning_how_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (today.rings > 0) HowItWentLines(stringResource(R.string.morning_how_today), today)
            HowItWentLines(stringResource(R.string.morning_how_week), week)
            Text(stringResource(R.string.morning_how_local), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun HowItWentLines(title: String, how: HowItWent) {
    val scheme = MaterialTheme.colorScheme
    Text(
        title + " · " + pluralStringResource(R.plurals.morning_how_rings, how.rings, how.rings),
        style = MaterialTheme.typography.titleSmall,
        color = scheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
    val lines = buildList {
        if (how.backupMissions > 0) add(pluralStringResource(R.plurals.morning_how_backup_mission, how.backupMissions, how.backupMissions))
        if (how.wayOuts > 0) add(pluralStringResource(R.plurals.morning_how_way_out, how.wayOuts, how.wayOuts))
        if (how.missed > 0) add(pluralStringResource(R.plurals.morning_how_missed, how.missed, how.missed))
        if (how.pauses > 0) add(pluralStringResource(R.plurals.morning_how_pauses, how.pauses, how.pauses))
        if (how.resumes > 0) add(pluralStringResource(R.plurals.morning_how_resumes, how.resumes, how.resumes))
        if (how.pullBacks > 0) add(pluralStringResource(R.plurals.morning_how_pull_backs, how.pullBacks, how.pullBacks))
        if (how.mutes > 0) add(pluralStringResource(R.plurals.morning_how_mutes, how.mutes, how.mutes))
    }
    if (how.straightUp || lines.isEmpty()) {
        Text(stringResource(R.string.morning_how_clean), style = MaterialTheme.typography.bodyMedium)
    } else {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun StreakCard(current: Int, best: Int) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LocalFireDepartment, null, tint = if (current > 0) scheme.primary else scheme.outline)
            Spacer(Modifier.padding(horizontal = 6.dp))
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text("$current", style = MaterialTheme.typography.headlineLarge)
                Text(pluralStringResource(R.plurals.morning_streak_days, current, current), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.morning_streak_best, best), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}
