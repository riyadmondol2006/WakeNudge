package com.riyadm.wakenudge.missions.sensors

import kotlin.math.sqrt

/**
 * Tells a machine from a person by the rhythm alone. A phone strapped to a shaker, or a held-steady metronome motion,
 * repeats its interval almost exactly; a person never does. Over the last [window] events (9 intervals):
 * - the intervals' coefficient of variation (standard deviation over mean) below [minVariation] is a machine's rhythm;
 * - so is a pattern that repeats every 2 to [MAX_LAG] intervals to within half that, such as short, long, short, long,
 *   which the plain spread would call irregular.
 * Such an event does not count. A pause longer than [resetAfterNs] starts the window over.
 */
class CadenceGuard(
    private val minVariation: Double,
    private val window: Int = WINDOW,
    private val resetAfterNs: Long = 3_000_000_000L,
) {
    private val times = ArrayDeque<Double>()

    /** Records an event at [timeNs] (fractions of a nanosecond allowed); returns whether the rhythm so far is a machine's. */
    fun isMechanical(timeNs: Double): Boolean {
        record(timeNs)
        return isMechanicalNow()
    }

    /** The intervals' coefficient of variation over the window, or null before the window is full. */
    fun variationOrNull(): Double? = if (times.size < window) null else variation()

    private fun intervals() = times.zipWithNext { a, b -> b - a }

    private fun isMachine(): Boolean = variation() < minVariation || repetition() < minVariation / 2

    /** Records an event without judging it (see [ShakeDetector], which judges a stroke before its peak is known). */
    fun record(timeNs: Double) {
        times.lastOrNull()?.let { if (timeNs - it > resetAfterNs) times.clear() }
        times.addLast(timeNs)
        while (times.size > window) times.removeFirst()
    }

    /** Whether the events recorded so far are a machine's rhythm; false before [window] of them. */
    fun isMechanicalNow(): Boolean = times.size >= window && isMachine()

    private fun variation(): Double {
        val intervals = intervals()
        val mean = intervals.average()
        if (mean <= 0.0) return 0.0
        return sqrt(intervals.sumOf { (it - mean) * (it - mean) } / intervals.size) / mean
    }

    /** How far each interval is from the one 2 to [MAX_LAG] places later, relative to the mean; the closest of those. */
    private fun repetition(): Double {
        val intervals = intervals()
        val mean = intervals.average()
        if (mean <= 0.0) return 0.0
        return (2..MAX_LAG).minOf { lag ->
            val diffs = (0 until intervals.size - lag).map { intervals[it + lag] - intervals[it] }
            // For independent intervals the difference of two has sqrt(2) times their spread.
            sqrt(diffs.sumOf { it * it } / diffs.size) / (SQRT2 * mean)
        }
    }

    companion object {
        const val WINDOW = 10
        const val MAX_LAG = 5
        private val SQRT2 = sqrt(2.0)



        /** Shakes. A shaker repeats its stroke to within sampling jitter, well below this. */
        const val SHAKE_MIN_VARIATION = 0.08
    }
}
