package com.riyadm.wakenudge.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Semantic haptics that honour the in-app switch, so every screen feels consistent and can be silenced in one place. */
class WakeHaptics(private val feedback: HapticFeedback, private val enabled: Boolean) {
    private fun perform(type: HapticFeedbackType) {
        if (enabled) feedback.performHapticFeedback(type)
    }

    /** A detent: wheel value changes, slider steps. */
    fun tick() = perform(HapticFeedbackType.SegmentTick)
    fun frequentTick() = perform(HapticFeedbackType.SegmentFrequentTick)
    fun toggle(on: Boolean) = perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    fun confirm() = perform(HapticFeedbackType.Confirm)
    fun reject() = perform(HapticFeedbackType.Reject)
    fun heavy() = perform(HapticFeedbackType.LongPress)
    fun threshold() = perform(HapticFeedbackType.GestureThresholdActivate)
    fun click() = perform(HapticFeedbackType.ContextClick)
}

val LocalWakeHaptics = staticCompositionLocalOf<WakeHaptics> { error("WakeNudgeTheme not installed") }

@Composable
internal fun rememberWakeHaptics(enabled: Boolean): WakeHaptics {
    val feedback = LocalHapticFeedback.current
    return androidx.compose.runtime.remember(feedback, enabled) { WakeHaptics(feedback, enabled) }
}
