package com.riyadm.wakenudge.engine.sounds

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SleepDspTest {
    @Test fun continuousSeededBoundedAndLevelMatched() {
        for (kind in SleepSound.entries) {
            val a = SleepGenerator(kind, 17)
            val b = SleepGenerator(kind, 17)
            val full = FloatArray(24000 * 12).also(a::fill)
            val pieces = FloatArray(full.size)
            for (offset in pieces.indices step 240) {
                val chunk = FloatArray(240).also(b::fill)
                chunk.copyInto(pieces, offset)
            }
            assertArrayEquals(kind.name, full, pieces, 0f)
            assertTrue(full.all { it.isFinite() && it in -1f..1f })
            assertTrue(kind.name, full.zipWithNextCompat().all { abs(it) <= 0.181f })
            val rms = sqrt(full.drop(24000).sumOf { it.toDouble().pow(2) } / (full.size - 24000))
            assertTrue("$kind rms=$rms", rms in 0.06..0.22)
        }
    }
    private fun FloatArray.zipWithNextCompat() = (1 until size).map { this[it] - this[it-1] }
    @Test fun fadeEndsAtSilence() {
        assertEquals(1f, SleepEnvelope.gain(60000), 0f)
        assertEquals(0.5f, SleepEnvelope.gain(15000), 0f)
        assertEquals(0f, SleepEnvelope.gain(0), 0f)
        assertEquals(0f, SleepEnvelope.gain(-1), 0f)
    }
}
