package com.riyadm.wakenudge.ui.report

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.habits.Habit
import com.riyadm.wakenudge.domain.habits.HabitRules
import com.riyadm.wakenudge.domain.habits.HabitStreakCalculator
import com.riyadm.wakenudge.ui.habits.HabitReportViewModel
import com.riyadm.wakenudge.ui.habits.HabitStreakBadge
import com.riyadm.wakenudge.ui.habits.HabitWeekRow
import com.riyadm.wakenudge.ui.habits.habitColour
import com.riyadm.wakenudge.ui.util.currentLocale
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

@Composable
fun HabitReportSection(nav: ReportNav, modifier: Modifier = Modifier, viewModel: HabitReportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = currentLocale()
    LaunchedEffect(locale) { viewModel.setSystemFirstDay(WeekFields.of(locale).firstDayOfWeek) }
    val current = state ?: return
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.habits_report_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        item {
            ReportWeekPicker(
                start = current.weekStart,
                weeksBack = current.earlierWeeks.toLong(),
                canGoForward = current.earlierWeeks > 0,
                onPrevious = viewModel::earlier,
                onNext = viewModel::later,
            )
        }
        if (current.data.habits.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.habits_report_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = nav.openHabits) {
                        Icon(Icons.Filled.Add, null)
                        Text(stringResource(R.string.habits_report_add), Modifier.padding(start = 8.dp))
                    }
                }
            }
        } else {
            items(current.data.habits, key = { it.id }) { habit ->
                HabitReportCard(
                    habit = habit,
                    weekStart = current.weekStart,
                    today = current.today,
                    done = current.data.doneDates(habit.id),
                    onToggle = { viewModel.toggle(habit.id, it) },
                )
            }
        }
    }
}

@Composable
private fun HabitReportCard(
    habit: Habit,
    weekStart: LocalDate,
    today: LocalDate,
    done: Set<Long>,
    onToggle: (LocalDate) -> Unit,
) {
    val streak = HabitStreakCalculator.calculate(habit, done, today)
    val colour = habitColour(habit.colorIndex)
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(colour.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text(habit.icon, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                }
                Text(habit.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                HabitStreakBadge(streak.current, colour)
            }
            Text(
                pluralStringResource(R.plurals.habits_best_streak, streak.best, streak.best),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            HabitWeekRow(habit, weekStart, today, done, onToggle, Modifier.padding(top = 12.dp))
            Text(
                stringResource(R.string.habits_report_completion, HabitRules.completionPercent(habit, weekStart, today, done)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
