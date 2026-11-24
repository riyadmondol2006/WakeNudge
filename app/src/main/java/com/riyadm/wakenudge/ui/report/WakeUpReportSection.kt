package com.riyadm.wakenudge.ui.report

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.riyadm.wakenudge.ui.morning.MorningScreen

/** Wake-up report: how mornings went. Owned by the wake-up report feature; for now the morning record as it was. */
@Composable
fun WakeUpReportSection(modifier: Modifier = Modifier) {
    MorningScreen(modifier)
}
