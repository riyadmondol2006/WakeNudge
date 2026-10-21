package com.riyadm.wakenudge.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riyadm.wakenudge.R

/** Fade-in lengths offered in the UI, in seconds. 0 means "instant". */
val FadeInSteps = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300, 420, 600)

@Composable
fun fadeLabel(seconds: Int): String = when {
    seconds <= 0 -> stringResource(R.string.fade_instant)
    seconds < 60 -> stringResource(R.string.unit_seconds, seconds)
    seconds % 60 == 0 -> stringResource(R.string.unit_minutes, seconds / 60)
    else -> stringResource(R.string.unit_min_sec, seconds / 60, seconds % 60)
}

@Composable
fun minutesLabel(minutes: Int): String = stringResource(R.string.unit_minutes, minutes)
