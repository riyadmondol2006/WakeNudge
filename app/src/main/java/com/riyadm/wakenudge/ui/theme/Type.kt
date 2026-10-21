package com.riyadm.wakenudge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Tabular figures keep clock digits from jittering as they change. */
private const val TABULAR = "tnum"

/**
 * Expressive type scale: oversized display styles for clocks and headlines, heavier weights for emphasis. The
 * "Emphasized" variants mirror Material 3 Expressive's emphasized styles using only stable APIs.
 */
internal val WakeTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
        displayMedium = base.displayMedium.copy(fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Normal),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Text styles that are not part of Material's scale. */
object ClockType {
    /** The big clock on the ring screen and the picker preview. */
    val Hero = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 112.sp, lineHeight = 120.sp,
        letterSpacing = (-4).sp, fontFeatureSettings = TABULAR,
    )

    /** Alarm time in list cards. */
    val Card = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 56.sp, lineHeight = 60.sp,
        letterSpacing = (-1.5).sp, fontFeatureSettings = TABULAR,
    )

    val CardSuffix = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 24.sp)

    /** Timer and stopwatch readouts. */
    val Readout = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 72.sp, lineHeight = 80.sp,
        letterSpacing = (-2).sp, fontFeatureSettings = TABULAR,
    )

    val WheelItem = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 56.sp, lineHeight = 64.sp,
        fontFeatureSettings = TABULAR,
    )

    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
}
