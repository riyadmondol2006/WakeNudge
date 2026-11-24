package com.riyadm.wakenudge.ui.report

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R

/** Which part of the report is shown. */
enum class ReportTab(val label: Int) { WAKE_UP(R.string.report_tab_wake), SLEEP(R.string.report_tab_sleep), HABITS(R.string.report_tab_habits) }

/** Where the report sections can send the user. */
data class ReportNav(
    val openSleepTracking: () -> Unit,
    val openSleepSounds: () -> Unit,
    val openHabits: () -> Unit,
)

/**
 * The Report tab: how mornings went, how nights went, and habits. Each section lives in its own file
 * ([WakeUpReportSection], [SleepReportSection], [HabitReportSection]) and draws its own content below the tabs.
 */
@Composable
fun ReportScreen(nav: ReportNav, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableStateOf(ReportTab.WAKE_UP) }
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            stringResource(R.string.report_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ReportTab.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = tab == t,
                    onClick = { tab = t },
                    shape = SegmentedButtonDefaults.itemShape(i, ReportTab.entries.size),
                ) { Text(stringResource(t.label), maxLines = 1) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ReportTab.WAKE_UP -> WakeUpReportSection()
                ReportTab.SLEEP -> SleepReportSection(nav)
                ReportTab.HABITS -> HabitReportSection(nav)
            }
        }
    }
}
