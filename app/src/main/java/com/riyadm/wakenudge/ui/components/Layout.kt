package com.riyadm.wakenudge.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable

/**
 * Insets for screens shown inside the navigation-suite scaffold: the bar/rail already handles the bottom (or start)
 * edge, so these screens only need the status bar and display cutouts.
 */
@Composable
fun topLevelInsets(): WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
