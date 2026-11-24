package com.riyadm.wakenudge.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R

/** Habit report: how habits went this week. Owned by the habits feature; a placeholder until then. */
@Composable
fun HabitReportSection(nav: ReportNav, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.report_habits_placeholder))
        FilledTonalButton(onClick = nav.openHabits) { Text(stringResource(R.string.habits_open)) }
    }
}
