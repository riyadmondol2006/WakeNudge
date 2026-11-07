package com.riyadm.wakenudge.missions.camera

import kotlin.math.sqrt

/**
 * Notices a camera that opens and runs but shows nothing: frames with almost no variation for a minute, the torch tried
 * on the way. A covered or broken lens stays flat with the torch on; a dark room does not, because the torch lights it.
 * Only time with frames counts, so a screen that is off or behind another app adds nothing. Pure, so it is tested on
 * the JVM.
 */
class DarkFrameWatch(
    private val torchAfterMs: Long = TORCH_AFTER_MS,
    private val giveUpMs: Long = GIVE_UP_MS,
) {
    enum class Verdict { FINE, TRY_TORCH, DARK }

    private var flatSince = -1L
    private var lastFrameAt = -1L

    /** One analysed frame at [elapsedNow] whose luminance varies by [stdDev] (0..255). */
    fun onFrame(elapsedNow: Long, stdDev: Double, torchAvailable: Boolean): Verdict {
        // A long gap without frames (the screen was off) starts the count again rather than counting the gap.
        if (lastFrameAt >= 0 && elapsedNow - lastFrameAt > MAX_GAP_MS) flatSince = -1
        lastFrameAt = elapsedNow
        if (stdDev >= FLAT_STDDEV) {
            flatSince = -1
            return Verdict.FINE
        }
        if (flatSince < 0) flatSince = elapsedNow
        val flatFor = elapsedNow - flatSince
        return when {
            flatFor >= giveUpMs -> Verdict.DARK
            torchAvailable && flatFor >= torchAfterMs -> Verdict.TRY_TORCH
            else -> Verdict.FINE
        }
    }

    companion object {
        const val TORCH_AFTER_MS = 15_000L
        const val GIVE_UP_MS = 60_000L
        private const val MAX_GAP_MS = 3_000L

        /** Below this spread of luminance a frame shows nothing: sensor noise on a covered lens stays well under it. */
        const val FLAT_STDDEV = 4.0

        /** The standard deviation of luminance samples (0..255 each). */
        fun stdDev(samples: IntArray, count: Int = samples.size): Double {
            if (count <= 1) return 0.0
            var sum = 0.0
            var squares = 0.0
            for (i in 0 until count) {
                val v = samples[i].toDouble()
                sum += v
                squares += v * v
            }
            val mean = sum / count
            return sqrt((squares / count - mean * mean).coerceAtLeast(0.0))
        }
    }
}
