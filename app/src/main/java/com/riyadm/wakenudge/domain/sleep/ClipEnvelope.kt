package com.riyadm.wakenudge.domain.sleep

import kotlin.math.sqrt

/** The little waveform drawn for a clip: [buckets] RMS levels scaled 0..100 against the loudest. */
object ClipEnvelope {
    fun of(pcm: ShortArray, buckets: Int = 32): List<Int> {
        if (pcm.isEmpty() || buckets <= 0) return List(buckets.coerceAtLeast(0)) { 0 }
        val levels = DoubleArray(buckets) { b ->
            val from = (pcm.size.toLong() * b / buckets).toInt()
            val to = (pcm.size.toLong() * (b + 1) / buckets).toInt().coerceAtLeast(from + 1).coerceAtMost(pcm.size)
            var sum = 0.0
            for (i in from until to) sum += pcm[i].toDouble() * pcm[i]
            sqrt(sum / (to - from).coerceAtLeast(1))
        }
        val max = levels.maxOrNull() ?: 0.0
        return levels.map { if (max <= 0) 0 else ((it / max) * 100).toInt().coerceIn(0, 100) }
    }

    fun encode(levels: List<Int>): String = levels.joinToString(",")
}

/** A fixed-size ring of the last few seconds of sound, from which a clip is cut. Not thread-safe; one reader thread owns it. */
class PcmRing(capacity: Int) {
    private val data = ShortArray(capacity.coerceAtLeast(1))
    private var write = 0
    private var filled = 0

    fun write(src: ShortArray, length: Int) {
        for (i in 0 until length) {
            data[write] = src[i]
            write = (write + 1) % data.size
        }
        filled = minOf(data.size, filled + length)
    }

    /** The last [count] samples (or as many as there are), oldest first. */
    fun last(count: Int): ShortArray {
        val n = count.coerceIn(0, filled)
        val out = ShortArray(n)
        var from = (write - n + data.size) % data.size
        for (i in 0 until n) {
            out[i] = data[from]
            from = (from + 1) % data.size
        }
        return out
    }
}
