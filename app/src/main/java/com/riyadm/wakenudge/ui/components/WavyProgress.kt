package com.riyadm.wakenudge.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** True when the user turned system animations off (Developer options / accessibility "remove animations"). */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { isReduceMotion(context) }
}

fun isReduceMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
private fun rememberWavePhase(animate: Boolean): Float {
    if (!animate) return 0f
    val transition = rememberInfiniteTransition(label = "wave")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    ).value
}

/** Horizontal progress drawn as a travelling wave that settles into a straight track. */
@Composable
fun WavyLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier.fillMaxWidth().height(20.dp),
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    amplitude: Dp = 4.dp,
    wavelength: Dp = 30.dp,
    strokeWidth: Dp = 5.dp,
    animate: Boolean = !rememberReduceMotion(),
) {
    val phase = rememberWavePhase(animate)
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val amp = amplitude.toPx()
        val len = wavelength.toPx()
        val midY = size.height / 2
        val endX = (size.width - stroke) * progress.coerceIn(0f, 1f) + stroke / 2
        drawLine(trackColor, Offset(stroke / 2, midY), Offset(size.width - stroke / 2, midY), stroke, StrokeCap.Round)
        if (progress > 0f) {
            val path = Path()
            var x = stroke / 2
            path.moveTo(x, midY + amp * sin(phase + (x / len) * 2 * PI.toFloat()))
            while (x < endX) {
                x = (x + 3f).coerceAtMost(endX)
                path.lineTo(x, midY + amp * sin(phase + (x / len) * 2 * PI.toFloat()))
            }
            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** Circular progress with a wavy ring, used for countdowns and mission progress. */
@Composable
fun WavyCircularProgress(
    progress: Float,
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    amplitude: Dp = 3.dp,
    waves: Int = 14,
    strokeWidth: Dp = 6.dp,
    animate: Boolean = !rememberReduceMotion(),
) {
    val phase = rememberWavePhase(animate)
    Canvas(modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val amp = amplitude.toPx()
        val radius = this.size.minDimension / 2 - stroke / 2 - amp
        val center = Offset(this.size.width / 2, this.size.height / 2)
        drawCircle(trackColor, radius, center, style = Stroke(stroke))
        val sweep = 2 * PI.toFloat() * progress.coerceIn(0f, 1f)
        if (sweep > 0f) {
            val path = Path()
            val steps = (sweep / 0.02f).toInt().coerceAtLeast(2)
            for (i in 0..steps) {
                val t = sweep * i / steps
                val r = radius + amp * sin(waves * t + phase)
                val a = t - PI.toFloat() / 2
                val p = Offset(center.x + r * cos(a), center.y + r * sin(a))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}
