package com.riyadm.wakenudge.missions.photo

import com.riyadm.wakenudge.domain.model.Difficulty
import kotlin.math.sqrt

/**
 * Scores how alike two scenes are, 0 (nothing in common) to 1 (identical), entirely from their [PhotoSignature]s.
 *
 * `similarity = 0.7 x structure + 0.3 x colour`:
 *  - structure is the normalised cross-correlation of the grayscale grids, maximised over shifts of up to [MAX_SHIFT]
 *    cells (about 6% of the frame) so slightly different framing still matches, and unaffected by brightness or
 *    contrast changes because correlation subtracts the mean and divides by the spread;
 *  - colour is the histogram intersection, which cares about the palette and not at all about layout.
 * Structure dominates because palette alone cannot tell two rooms with the same paint apart.
 *
 * Measured on the synthetic scenes in `PhotoMatcherTest` (40 seeds each): the same view scores 0.97-1.00 when shifted
 * by up to 5 cells or zoomed 3%, 0.83-0.87 with +25 brightness and 0.75-0.83 with -40; a different scene scores
 * 0.03-0.39, the same pixels scrambled 0.30-0.40 and random noise 0.09-0.28. The thresholds (see [thresholdFor]) sit
 * in the wide gap between those groups. Real camera frames are noisier than the synthetic ones, so expect lower
 * same-place scores in practice; the margins were not verified on a device.
 */
object PhotoMatcher {
    const val MAX_SHIFT = 2
    private const val STRUCTURE_WEIGHT = 0.7f
    private const val COLOUR_WEIGHT = 0.3f

    fun similarity(reference: PhotoSignature, frame: PhotoSignature): Float =
        (STRUCTURE_WEIGHT * structure(reference.gray, frame.gray) + COLOUR_WEIGHT * colour(reference.histogram, frame.histogram)).coerceIn(0f, 1f)

    /** Best correlation (clamped to 0..1) over all shifts of [frame] relative to [reference]. */
    internal fun structure(reference: ByteArray, frame: ByteArray): Float {
        val g = PhotoSignature.GRID
        var best = 0f
        for (dy in -MAX_SHIFT..MAX_SHIFT) for (dx in -MAX_SHIFT..MAX_SHIFT) {
            val x0 = maxOf(0, -dx)
            val x1 = minOf(g, g - dx)
            val y0 = maxOf(0, -dy)
            val y1 = minOf(g, g - dy)
            val n = ((x1 - x0) * (y1 - y0)).toFloat()
            var sumA = 0f
            var sumB = 0f
            for (y in y0 until y1) for (x in x0 until x1) {
                sumA += reference[y * g + x].toInt() and 0xFF
                sumB += frame[(y + dy) * g + x + dx].toInt() and 0xFF
            }
            val meanA = sumA / n
            val meanB = sumB / n
            var cross = 0f
            var varA = 0f
            var varB = 0f
            for (y in y0 until y1) for (x in x0 until x1) {
                val a = (reference[y * g + x].toInt() and 0xFF) - meanA
                val b = (frame[(y + dy) * g + x + dx].toInt() and 0xFF) - meanB
                cross += a * b
                varA += a * a
                varB += b * b
            }
            if (varA < 1e-3f || varB < 1e-3f) continue
            val correlation = cross / sqrt(varA * varB)
            if (correlation > best) best = correlation
        }
        return best.coerceIn(0f, 1f)
    }

    internal fun colour(a: FloatArray, b: FloatArray): Float {
        var overlap = 0f
        for (i in a.indices) overlap += minOf(a[i], b[i])
        return overlap.coerceIn(0f, 1f)
    }

    /** Similarity a live frame must reach, and hold for [MatchHold.HOLD_NS], to pass. */
    fun thresholdFor(difficulty: Difficulty): Float = when (difficulty) {
        Difficulty.EASY -> 0.70f
        Difficulty.MEDIUM -> 0.78f
        Difficulty.HARD -> 0.85f
    }
}

/**
 * Requires the similarity to stay at or above [threshold] for [holdNs] without a dip, so passing the camera over the
 * right place for a split second is not enough. A gap in frames longer than [MAX_GAP_NS] (camera stalled or paused)
 * restarts the hold rather than counting the silence as a match.
 */
class MatchHold(private val threshold: Float, private val holdNs: Long = HOLD_NS) {
    private var sinceNs = -1L
    private var lastNs = -1L

    /** 0..1 progress towards the required hold, for the UI. */
    var progress: Float = 0f
        private set

    /** Feeds one score at time [nowNs]; returns true once the hold is satisfied. */
    fun update(nowNs: Long, score: Float): Boolean {
        val stalled = lastNs >= 0 && nowNs - lastNs > MAX_GAP_NS
        if (score < threshold) sinceNs = -1L else if (stalled || sinceNs < 0) sinceNs = nowNs
        lastNs = nowNs
        progress = if (sinceNs < 0) 0f else ((nowNs - sinceNs).toFloat() / holdNs).coerceIn(0f, 1f)
        return sinceNs >= 0 && nowNs - sinceNs >= holdNs
    }

    fun reset() {
        sinceNs = -1L
        lastNs = -1L
        progress = 0f
    }

    companion object {
        const val HOLD_NS = 1_000_000_000L
        const val MAX_GAP_NS = 600_000_000L
    }
}
