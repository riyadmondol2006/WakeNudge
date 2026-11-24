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

/** Sleep report: how nights went. Owned by the sleep-tracking feature; a placeholder until then. */
@Composable
fun SleepReportSection(nav: ReportNav, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.report_sleep_placeholder))
        FilledTonalButton(onClick = nav.openSleepTracking) { Text(stringResource(R.string.sleep_track_open)) }
    }
}
