package com.riyadm.wakenudge.domain.sleep

import java.util.TreeMap
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Folds accelerometer readings and sound frames into 30-second [Epoch]s as they arrive, so raw samples are never kept.
 * Times are on the elapsed clock (`SystemClock.elapsedRealtime`, which sensor timestamps also use), counted from
 * [startElapsedMs], so changing the time of day cannot move a reading into the wrong epoch.
 *
 * Readings arrive in batches up to [SleepLimits.SENSOR_BATCH_US] late; [drain] therefore only hands over epochs that
 * ended at least [SETTLE_MS] ago, except at the end of the session. A reading for an epoch already handed over is
 * dropped. Thread-safe: the sensor, the microphone and the service's tick call it from different threads.
 */
class EpochAccumulator(private val startElapsedMs: Long) {
    companion object {
        /** A change of acceleration between two readings this large (m/s²) is a clear movement, not breathing or noise. */
        const val MOVE_THRESHOLD = 0.1f

        /** Readings further apart than this are not compared: the change across a gap says nothing. */
        const val GAP_MS = 1_500L

        /** How long after its end an epoch is handed over, leaving room for batched readings. */
        const val SETTLE_MS = 30_000L
    }

    private class Bucket {
        var samples = 0
        var deltas = 0
        var deltaSum = 0.0
        var deltaMax = 0f
        var moves = 0
        var soundFrames = 0
        var soundEnergy = 0.0
        var soundPeak = Float.NEGATIVE_INFINITY
        var snores = 0
    }

    private val buckets = TreeMap<Int, Bucket>()
    private var nextIndex = 0
    private var lastT = Long.MIN_VALUE
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f

    private fun indexOf(tMs: Long): Int? {
        if (tMs < startElapsedMs) return null
        val index = ((tMs - startElapsedMs) / SleepLimits.EPOCH_MS).toInt()
        return index.takeIf { it >= nextIndex }
    }

    @Synchronized
    fun addMotion(tMs: Long, x: Float, y: Float, z: Float) {
        val previous = lastT
        val px = lastX; val py = lastY; val pz = lastZ
        if (tMs > lastT) {
            lastT = tMs; lastX = x; lastY = y; lastZ = z
        }
        val bucket = indexOf(tMs)?.let { buckets.getOrPut(it) { Bucket() } } ?: return
        bucket.samples++
        if (previous == Long.MIN_VALUE || tMs <= previous || tMs - previous > GAP_MS) return
        val dx = x - px; val dy = y - py; val dz = z - pz
        val delta = sqrt(dx * dx + dy * dy + dz * dz)
        bucket.deltas++
        bucket.deltaSum += delta
        bucket.deltaMax = max(bucket.deltaMax, delta)
        if (delta >= MOVE_THRESHOLD) bucket.moves++
    }

    /** One short sound frame's level in dBFS. */
    @Synchronized
    fun addSound(tMs: Long, db: Float) {
        val bucket = indexOf(tMs)?.let { buckets.getOrPut(it) { Bucket() } } ?: return
        bucket.soundFrames++
        bucket.soundEnergy += 10.0.pow(db / 10.0)
        bucket.soundPeak = max(bucket.soundPeak, db)
    }

    @Synchronized
    fun addSnore(tMs: Long) {
        val bucket = indexOf(tMs)?.let { buckets.getOrPut(it) { Bucket() } } ?: return
        bucket.snores++
    }

    /**
     * Every epoch not yet handed over that ended [SETTLE_MS] before [nowElapsedMs], with empty ones for gaps. With
     * [final], also the epoch running at [nowElapsedMs], however short: the session ends there.
     */
    @Synchronized
    fun drain(nowElapsedMs: Long, final: Boolean = false): List<Epoch> {
        val elapsed = nowElapsedMs - startElapsedMs
        val until = if (final) {
            if (elapsed <= 0) 0 else ((elapsed - 1) / SleepLimits.EPOCH_MS).toInt() + 1
        } else {
            ((elapsed - SETTLE_MS).coerceAtLeast(0) / SleepLimits.EPOCH_MS).toInt()
        }
        val out = ArrayList<Epoch>()
        while (nextIndex < until) {
            val b = buckets.remove(nextIndex)
            out += if (b == null) Epoch(nextIndex, 0, 0f, 0f, 0) else Epoch(
                index = nextIndex,
                samples = b.samples,
                meanDelta = if (b.deltas == 0) 0f else (b.deltaSum / b.deltas).toFloat(),
                maxDelta = b.deltaMax,
                moveSamples = b.moves,
                soundDb = if (b.soundFrames == 0) null else (10 * log10(b.soundEnergy / b.soundFrames)).toFloat(),
                soundPeakDb = if (b.soundFrames == 0) null else b.soundPeak,
                snores = b.snores,
            )
            nextIndex++
        }
        // Anything older than what was handed over can no longer be stored.
        buckets.headMap(nextIndex).clear()
        return out
    }
}
