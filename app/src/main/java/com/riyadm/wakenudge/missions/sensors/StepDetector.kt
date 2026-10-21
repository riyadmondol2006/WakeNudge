package com.riyadm.wakenudge.missions.sensors

import kotlin.math.sqrt

/**
 * Accelerometer-only pedometer, used when the phone has neither a hardware step counter nor a step detector (or the
 * user declined the activity-recognition permission).
 *
 * The gravity-free magnitude is smoothed and each local maximum above an adaptive threshold is a step candidate.
 * Candidates must follow each other at a walking cadence; the first [WARMUP_STEPS] of a streak are credited together
 * once the streak is confirmed, so one-off jolts (picking the phone up, putting it down) never count as steps.
 */
class StepDetector {
    private var lastNs = -1L
    private var gravity = STANDARD_GRAVITY
    private var smooth = 0f
    private var prev = 0f
    private var prevNs = 0L
    private var rising = false
    private var peakAverage = 0f
    private var lastPeakNs = -1L
    private var streak = 0

    /** Feeds one accelerometer sample; returns how many steps it newly confirms (usually 0 or 1, 3 at the start of a walk). */
    fun onSample(timestampNs: Long, x: Float, y: Float, z: Float): Int {
        val magnitude = sqrt(x * x + y * y + z * z)
        if (lastNs < 0) {
            lastNs = timestampNs
            gravity = magnitude
            prevNs = timestampNs
            return 0
        }
        val dt = ((timestampNs - lastNs) / 1e9f).coerceIn(0.001f, 0.2f)
        lastNs = timestampNs
        gravity += (magnitude - gravity) * dt / (GRAVITY_TAU_S + dt)
        smooth += (magnitude - gravity - smooth) * dt / (SMOOTH_TAU_S + dt)

        var credited = 0
        // `prev` was a peak if the signal rose into it and is now falling away.
        if (rising && smooth < prev) credited = onPeak(prev, prevNs)
        rising = smooth > prev
        prev = smooth
        prevNs = timestampNs
        return credited
    }

    private fun onPeak(height: Float, timeNs: Long): Int {
        val threshold = maxOf(MIN_PEAK_MS2, ADAPTIVE_FRACTION * peakAverage)
        if (height < threshold) return 0
        val sincePeak = if (lastPeakNs < 0) Float.MAX_VALUE else (timeNs - lastPeakNs) / 1e9f
        if (sincePeak < MIN_INTERVAL_S) return 0
        lastPeakNs = timeNs
        peakAverage = if (peakAverage == 0f) height else peakAverage * 0.8f + height * 0.2f
        streak = if (sincePeak <= MAX_INTERVAL_S) streak + 1 else 1
        return when {
            streak == WARMUP_STEPS -> WARMUP_STEPS
            streak > WARMUP_STEPS -> 1
            else -> 0
        }
    }

    companion object {
        const val WARMUP_STEPS = 3
        private const val GRAVITY_TAU_S = 1.0f
        private const val SMOOTH_TAU_S = 0.05f
        private const val MIN_PEAK_MS2 = 1.0f
        private const val ADAPTIVE_FRACTION = 0.45f
        private const val MIN_INTERVAL_S = 0.25f
        private const val MAX_INTERVAL_S = 1.2f
    }
}
