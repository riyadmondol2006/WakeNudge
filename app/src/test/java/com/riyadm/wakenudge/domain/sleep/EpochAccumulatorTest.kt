package com.riyadm.wakenudge.domain.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpochAccumulatorTest {
    private val start = 1_000_000L
    private val epoch = SleepLimits.EPOCH_MS

    @Test fun `still readings give a small mean change and no movements`() {
        val acc = EpochAccumulator(start)
        for (i in 0 until 300) acc.addMotion(start + i * 100L, 0f, 0f, 9.81f + if (i % 2 == 0) 0.01f else -0.01f)
        val epochs = acc.drain(start + epoch + EpochAccumulator.SETTLE_MS)
        assertEquals(1, epochs.size)
        val e = epochs.single()
        assertEquals(300, e.samples)
        assertEquals(0.02f, e.meanDelta, 0.0001f)
        assertEquals(0, e.moveSamples)
        assertNull("no microphone, no sound", e.soundDb)
    }

    @Test fun `a turn over counts as movements`() {
        val acc = EpochAccumulator(start)
        acc.addMotion(start, 0f, 0f, 9.81f)
        acc.addMotion(start + 100, 0f, 3f, 9f)
        acc.addMotion(start + 200, 0f, 3.05f, 9f)
        val e = acc.drain(start + epoch, final = true).single()
        assertEquals(1, e.moveSamples)
        assertTrue(e.maxDelta > 3f)
    }

    @Test fun `epochs are handed over only once batched readings can have arrived`() {
        val acc = EpochAccumulator(start)
        acc.addMotion(start + 10, 0f, 0f, 9.8f)
        assertTrue(acc.drain(start + epoch + EpochAccumulator.SETTLE_MS - 1).isEmpty())
        // A batch arriving 20 s late still lands in its own epoch.
        acc.addMotion(start + 20_000, 0f, 0f, 9.8f)
        val e = acc.drain(start + epoch + EpochAccumulator.SETTLE_MS).single()
        assertEquals(2, e.samples)
    }

    @Test fun `a gap gives empty epochs and readings across it are not compared`() {
        val acc = EpochAccumulator(start)
        acc.addMotion(start, 0f, 0f, 9.8f)
        acc.addMotion(start + 3 * epoch, 5f, 0f, 9.8f) // after a long gap: no delta
        val epochs = acc.drain(start + 4 * epoch, final = true)
        assertEquals(listOf(0, 1, 2, 3), epochs.map { it.index })
        assertEquals(listOf(1, 0, 0, 1), epochs.map { it.samples })
        assertEquals(0, epochs[3].moveSamples)
    }

    @Test fun `late readings for an epoch already handed over are dropped, not misplaced`() {
        val acc = EpochAccumulator(start)
        acc.addMotion(start + 10, 0f, 0f, 9.8f)
        acc.drain(start + epoch + EpochAccumulator.SETTLE_MS)
        acc.addMotion(start + 20, 0f, 0f, 9.8f)
        val rest = acc.drain(start + 2 * epoch, final = true)
        assertEquals(listOf(1), rest.map { it.index })
        assertEquals(0, rest.single().samples)
    }

    @Test fun `the final drain includes the running partial epoch and nothing twice`() {
        val acc = EpochAccumulator(start)
        acc.addMotion(start + epoch + 5_000, 0f, 0f, 9.8f)
        val epochs = acc.drain(start + epoch + 6_000, final = true)
        assertEquals(listOf(0, 1), epochs.map { it.index })
        assertTrue(acc.drain(start + 10 * epoch, final = true).map { it.index }.none { it <= 1 })
    }

    @Test fun `sound is energy averaged and snores are counted per epoch`() {
        val acc = EpochAccumulator(start)
        acc.addSound(start + 100, -60f)
        acc.addSound(start + 200, -40f)
        acc.addSnore(start + 300)
        val e = acc.drain(start + epoch, final = true).single()
        // Energy average of -60 and -40 dB is -43 dB, not the arithmetic -50.
        assertEquals(-42.96f, e.soundDb!!, 0.05f)
        assertEquals(-40f, e.soundPeakDb!!, 0f)
        assertEquals(1, e.snores)
    }

    @Test fun `readings before the start are ignored`() {
        val acc = EpochAccumulator(start)
        acc.addMotion(start - 5, 0f, 0f, 9.8f)
        assertEquals(0, acc.drain(start + epoch, final = true).single().samples)
    }
}
