package com.riyadm.wakenudge.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Material 3 Expressive motion tokens (spatial springs may overshoot to feel alive; effects springs never do),
 * built from the published token values because the official MotionScheme API is not public in stable Material 3.
 */
object WakeMotion {
    fun <T> fastSpatial() = spring<T>(dampingRatio = 0.6f, stiffness = 800f)
    fun <T> defaultSpatial() = spring<T>(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> slowSpatial() = spring<T>(dampingRatio = 0.8f, stiffness = 200f)
    fun <T> fastEffects() = spring<T>(dampingRatio = 1f, stiffness = 3800f)
    fun <T> defaultEffects() = spring<T>(dampingRatio = 1f, stiffness = 1600f)
    fun <T> slowEffects() = spring<T>(dampingRatio = 1f, stiffness = 800f)

    /** Bouncy spring for shape morphs and big gestures. */
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
}

/** Presses squash the element slightly with a springy release — used on every tappable surface. */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.94f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, WakeMotion.fastSpatial(), label = "pressScale")
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun rememberInteractionSource(): MutableInteractionSource = remember { MutableInteractionSource() }
