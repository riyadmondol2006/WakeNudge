package com.riyadm.wakenudge.domain.sleep

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** What the snore detector looks at in one short frame of sound. */
data class FrameFeatures(
    /** Level in dBFS (0 is the loudest the microphone reports). */
    val db: Float,
    /** Zero crossings expressed as a frequency: a rough "how high is this sound". */
    val zeroCrossingHz: Float,
    /** Share of the energy below [FrameAnalyzer.LOW_CUTOFF_HZ]. Snoring is low and rumbling. */
    val lowRatio: Float,
)

/**
 * Measures 16-bit PCM frames. Keeps the low-pass filter's state between frames, so feed it frames in order.
 * Plain arithmetic, no model and no download.
 */
class FrameAnalyzer(private val sampleRate: Int) {
    companion object {
        const val LOW_CUTOFF_HZ = 800.0
        const val SILENCE_DB = -96f
    }

    private val alpha = (1 - exp(-2 * PI * LOW_CUTOFF_HZ / sampleRate)).toFloat()
    private var low = 0f
    private var previous = 0

    fun analyze(frame: ShortArray, length: Int = frame.size): FrameFeatures {
        if (length <= 0) return FrameFeatures(SILENCE_DB, 0f, 0f)
        var energy = 0.0
        var lowEnergy = 0.0
        var crossings = 0
        for (i in 0 until length) {
            val s = frame[i].toInt()
            val x = s / 32768f
            energy += x * x
            low += alpha * (x - low)
            lowEnergy += low * low
            if ((s >= 0) != (previous >= 0)) crossings++
            previous = s
        }
        val rms = sqrt(energy / length)
        val db = if (rms <= 0) SILENCE_DB else max(SILENCE_DB, (20 * log10(rms)).toFloat())
        val seconds = length.toFloat() / sampleRate
        return FrameFeatures(
            db = db,
            zeroCrossingHz = crossings / (2 * seconds),
            lowRatio = if (energy <= 0) 0f else (lowEnergy / energy).toFloat().coerceIn(0f, 1f),
        )
    }
}

/** A snore-like episode the detector is confident about. */
data class SnoreEvent(
    val startMs: Long,
    val endMs: Long,
    val peakDb: Float,
    /** How far the peak stood above the room's quiet level, in dB. What "loud" means for this room. */
    val aboveFloorDb: Float,
)

/**
 * A simple, documented on-device snore detector. No model, no download, nothing leaves the phone.
 *
 * - A frame (about 50 ms) is **snore-like** when it is at least [ABOVE_FLOOR_DB] louder than the room's quiet level and
 *   above [MIN_DB], low-pitched (zero crossings at most [MAX_ZERO_CROSSING_HZ]) and rumbling (at least [MIN_LOW_RATIO] of
 *   its energy below 800 Hz).
 * - An **episode** is a run of snore-like frames (gaps of up to [MAX_GAP_FRAMES] frames bridged) lasting
 *   [MIN_EPISODE_MS] to [MAX_EPISODE_MS]. Longer sounds (a fan, traffic, a long sentence) are not snores.
 * - Snoring follows breathing, so an episode only **counts** once it is the third in a row whose starts are
 *   [MIN_INTERVAL_MS] to [MAX_INTERVAL_MS] apart, with the two intervals within [MAX_INTERVAL_SPREAD] of each other.
 * - The quiet level follows the room: it drops quickly to a quieter frame and rises slowly ([FLOOR_RISE_DB] per frame).
 *
 * It will sometimes take a cough, a pet or a partner for a snore; that is why clips are kept for the user to listen to.
 */
class SnoreDetector(private val frameMs: Long) {
    companion object {
        const val ABOVE_FLOOR_DB = 8f
        const val MIN_DB = -65f
        const val MAX_ZERO_CROSSING_HZ = 900f
        const val MIN_LOW_RATIO = 0.5f
        const val MAX_GAP_FRAMES = 2
        const val MIN_EPISODE_MS = 300L
        const val MAX_EPISODE_MS = 3_500L
        const val MIN_INTERVAL_MS = 2_000L
        const val MAX_INTERVAL_MS = 12_000L
        const val MAX_INTERVAL_SPREAD = 0.4f
        const val FLOOR_RISE_DB = 0.01f
        const val FLOOR_FALL = 0.2f
        const val INITIAL_FLOOR_DB = -70f
    }

    var floorDb = INITIAL_FLOOR_DB
        private set

    private var episodeStart = -1L
    private var lastSnoreFrameEnd = -1L
    private var peak = Float.NEGATIVE_INFINITY
    private var gap = 0
    private val starts = ArrayDeque<Long>()

    fun isSnoreLike(f: FrameFeatures): Boolean =
        f.db - floorDb >= ABOVE_FLOOR_DB && f.db >= MIN_DB && f.zeroCrossingHz <= MAX_ZERO_CROSSING_HZ && f.lowRatio >= MIN_LOW_RATIO

    /** Feed one frame that started at [tMs]. Returns an event when this frame closed an episode that counts. */
    fun onFrame(tMs: Long, f: FrameFeatures): SnoreEvent? {
        val snoreLike = isSnoreLike(f)
        val floorBefore = floorDb
        floorDb = if (f.db < floorDb) floorDb + (f.db - floorDb) * FLOOR_FALL else floorDb + FLOOR_RISE_DB
        if (snoreLike) {
            if (episodeStart < 0) {
                episodeStart = tMs
                peak = Float.NEGATIVE_INFINITY
            }
            lastSnoreFrameEnd = tMs + frameMs
            peak = max(peak, f.db)
            gap = 0
            return null
        }
        if (episodeStart < 0) return null
        gap++
        if (gap <= MAX_GAP_FRAMES) return null
        val start = episodeStart
        val end = lastSnoreFrameEnd
        val episodePeak = peak
        episodeStart = -1
        gap = 0
        if (end - start !in MIN_EPISODE_MS..MAX_EPISODE_MS) return null
        starts.addLast(start)
        while (starts.size > 3) starts.removeFirst()
        if (starts.size < 3) return null
        val first = starts[1] - starts[0]
        val second = starts[2] - starts[1]
        val inRange = first in MIN_INTERVAL_MS..MAX_INTERVAL_MS && second in MIN_INTERVAL_MS..MAX_INTERVAL_MS
        val regular = abs(first - second) <= MAX_INTERVAL_SPREAD * max(first, second)
        if (!inRange || !regular) return null
        return SnoreEvent(start, end, episodePeak, episodePeak - minOf(floorBefore, floorDb))
    }
}
