package com.riyadm.wakenudge.missions.sensors

import com.riyadm.wakenudge.domain.model.Difficulty
import kotlin.math.abs
import kotlin.math.sqrt

/** Standard gravity in m/s²; the accelerometer vector has this magnitude when the phone is at rest. */
internal const val STANDARD_GRAVITY = 9.80665f

/**
 * Counts deliberate shakes from raw accelerometer samples. A shake is a sample whose magnitude differs from resting
 * gravity by more than [thresholdMs2]; the [refractoryNs] gap after a counted shake swallows the rebound of the same
 * back-and-forth stroke (and a single hard knock counts once), so the count follows how many strokes the user made
 * rather than how many sensor samples crossed the line. Orientation does not matter because only the magnitude is used.
 */
class ShakeDetector(
    private val thresholdMs2: Float,
    private val refractoryNs: Long,
    private val cadence: CadenceGuard = CadenceGuard(CadenceGuard.SHAKE_MIN_VARIATION),
) {
    private var lastShakeNs = 0L
    private var hasShake = false

    /** The stroke the hand is in (the excess above the threshold), and where its peak was so far. */
    private var inStroke = false
    private var strokePeak = 0f
    private var strokePeakNs = 0L
    private var beforePeak = 0f
    private var afterPeak = 0f
    private var peakIsLast = false
    private var spacingNs = 0L
    private var lastExcess = 0f
    private var lastNs = -1L

    /** The strongest recent stroke, fading, so the rhythm is read from the main strokes and not from their rebounds. */
    private var strongest = 0f

    /**
     * Feeds one accelerometer sample; returns true when it completes a counted shake. A shake in a rhythm too regular for a
     * person (a phone on a shaker) is not counted.
     *
     * The rhythm is judged from when each main stroke peaks, counted or not: the moment a stroke crosses the threshold is
     * held back by the dead time after the last one, which would make a fast hand look as even as a machine, and the weak
     * rebound of a stroke on the way back would make a machine look uneven.
     */
    fun onSample(timestampNs: Long, x: Float, y: Float, z: Float): Boolean {
        val excess = abs(sqrt(x * x + y * y + z * z) - STANDARD_GRAVITY)
        val previous = lastExcess
        if (lastNs >= 0) spacingNs = timestampNs - lastNs
        lastExcess = excess
        lastNs = timestampNs
        if (peakIsLast) {
            afterPeak = excess
            peakIsLast = false
        }
        if (excess < thresholdMs2) {
            if (inStroke) endStroke()
            inStroke = false
            return false
        }
        if (!inStroke || excess > strokePeak) {
            strokePeak = excess
            strokePeakNs = timestampNs
            beforePeak = previous
            peakIsLast = true
        }
        inStroke = true
        if (hasShake && timestampNs - lastShakeNs < refractoryNs) return false
        hasShake = true
        lastShakeNs = timestampNs
        return !cadence.isMechanicalNow()
    }

    /**
     * Records a main stroke's peak, placed between samples by a parabola through the samples around it: at a fast shake a
     * stroke is only a few samples long, and the sample nearest the peak would add jitter no machine has.
     */
    private fun endStroke() {
        strongest = maxOf(strongest * FADE, strokePeak)
        if (strokePeak < MAIN_STROKE * strongest) return
        val curve = beforePeak - 2f * strokePeak + afterPeak
        val offset = if (curve < 0f) (0.5f * (beforePeak - afterPeak) / curve).coerceIn(-0.5f, 0.5f) else 0f
        cadence.record(strokePeakNs + offset.toDouble() * spacingNs)
    }

    companion object {
        private const val FADE = 0.9f
        private const val MAIN_STROKE = 0.6f

        fun forDifficulty(difficulty: Difficulty): ShakeDetector = when (difficulty) {
            Difficulty.EASY -> ShakeDetector(thresholdMs2 = 7f, refractoryNs = 250_000_000L)
            Difficulty.MEDIUM -> ShakeDetector(thresholdMs2 = 11f, refractoryNs = 300_000_000L)
            Difficulty.HARD -> ShakeDetector(thresholdMs2 = 15f, refractoryNs = 350_000_000L)
        }
    }
}
