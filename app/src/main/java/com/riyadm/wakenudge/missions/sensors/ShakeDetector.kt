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
) {
    private var lastShakeNs = 0L
    private var hasShake = false

    /** Feeds one accelerometer sample; returns true when it completes a counted shake. */
    fun onSample(timestampNs: Long, x: Float, y: Float, z: Float): Boolean {
        val excess = abs(sqrt(x * x + y * y + z * z) - STANDARD_GRAVITY)
        if (excess < thresholdMs2) return false
        if (hasShake && timestampNs - lastShakeNs < refractoryNs) return false
        hasShake = true
        lastShakeNs = timestampNs
        return true
    }

    companion object {
        fun forDifficulty(difficulty: Difficulty): ShakeDetector = when (difficulty) {
            Difficulty.EASY -> ShakeDetector(thresholdMs2 = 7f, refractoryNs = 250_000_000L)
            Difficulty.MEDIUM -> ShakeDetector(thresholdMs2 = 11f, refractoryNs = 300_000_000L)
            Difficulty.HARD -> ShakeDetector(thresholdMs2 = 15f, refractoryNs = 350_000_000L)
        }
    }
}
