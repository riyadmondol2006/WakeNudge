package com.riyadm.wakenudge.domain.sleep

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipEnvelopeTest {
    @Test fun `levels are scaled against the loudest part`() {
        val pcm = ShortArray(64) { i -> if (i < 32) 1000 else 4000 }
        assertEquals(listOf(25, 100), ClipEnvelope.of(pcm, buckets = 2))
        assertEquals("25,100", ClipEnvelope.encode(ClipEnvelope.of(pcm, buckets = 2)))
    }

    @Test fun `silence and empty clips give flat lines`() {
        assertEquals(List(4) { 0 }, ClipEnvelope.of(ShortArray(100), buckets = 4))
        assertEquals(List(4) { 0 }, ClipEnvelope.of(ShortArray(0), buckets = 4))
    }

    @Test fun `the ring keeps only the last samples, oldest first`() {
        val ring = PcmRing(4)
        ring.write(shortArrayOf(1, 2, 3), 3)
        assertArrayEquals(shortArrayOf(1, 2, 3), ring.last(10))
        ring.write(shortArrayOf(4, 5, 6), 3)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), ring.last(10))
        assertArrayEquals(shortArrayOf(5, 6), ring.last(2))
    }
}
