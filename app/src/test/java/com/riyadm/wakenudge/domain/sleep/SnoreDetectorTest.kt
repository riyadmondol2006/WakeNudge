package com.riyadm.wakenudge.domain.sleep

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoreDetectorTest {
    private val rate = 16_000
    private val frameMs = 50L
    private val frameLen = (rate * frameMs / 1000).toInt()

    private fun sine(freq: Double, amplitude: Double, offset: Int = 0) =
        ShortArray(frameLen) { i -> (amplitude * 32767 * sin(2 * PI * freq * (i + offset) / rate)).toInt().toShort() }

    @Test fun `a low rumble is low pitched and mostly low energy, a whistle is not`() {
        val analyzer = FrameAnalyzer(rate)
        var low = analyzer.analyze(sine(150.0, 0.1))
        repeat(3) { low = analyzer.analyze(sine(150.0, 0.1, it * frameLen)) }
        assertEquals(150f, low.zeroCrossingHz, 25f)
        assertTrue("low ratio ${low.lowRatio}", low.lowRatio > 0.8f)
        assertEquals(-23f, low.db, 1f) // a 0.1 sine is -20 dB peak, -23 dB RMS

        val high = FrameAnalyzer(rate).analyze(sine(3_000.0, 0.1))
        assertTrue(high.zeroCrossingHz > 2_500f)
        assertTrue("low ratio ${high.lowRatio}", high.lowRatio < 0.2f)
    }

    @Test fun `silence is the floor of the scale`() {
        assertEquals(FrameAnalyzer.SILENCE_DB, FrameAnalyzer(rate).analyze(ShortArray(frameLen)).db, 0f)
    }

    private val quiet = FrameFeatures(-70f, 400f, 0.4f)
    private val snore = FrameFeatures(-40f, 200f, 0.9f)
    private val hiss = FrameFeatures(-40f, 3_000f, 0.1f)

    /** Runs a timeline of (frames of [snore] per episode) every [periodMs]; returns the events. */
    private fun run(detector: SnoreDetector, episodes: Int, episodeMs: Long, periodMs: Long, sound: FrameFeatures = snore, settleMs: Long = 10_000): List<SnoreEvent> {
        val events = mutableListOf<SnoreEvent>()
        var t = 0L
        while (t < settleMs) { detector.onFrame(t, quiet); t += frameMs }
        repeat(episodes) {
            val start = t
            while (t < start + episodeMs) { detector.onFrame(t, sound)?.let(events::add); t += frameMs }
            while (t < start + periodMs) { detector.onFrame(t, quiet)?.let(events::add); t += frameMs }
        }
        return events
    }

    @Test fun `regular snores count from the third one`() {
        val events = run(SnoreDetector(frameMs), episodes = 6, episodeMs = 1_000, periodMs = 4_000)
        assertEquals(4, events.size)
        events.forEach {
            assertEquals(-40f, it.peakDb, 0f)
            assertTrue("above floor ${it.aboveFloorDb}", it.aboveFloorDb > 25f)
            assertEquals(1_000L, it.endMs - it.startMs)
        }
    }

    @Test fun `breathing rhythm varies a little and still counts`() {
        val detector = SnoreDetector(frameMs)
        val events = mutableListOf<SnoreEvent>()
        var t = 0L
        while (t < 10_000) { detector.onFrame(t, quiet); t += frameMs }
        for (period in listOf(3_500L, 4_200L, 3_800L, 4_500L)) {
            val start = t
            while (t < start + 900) { detector.onFrame(t, snore)?.let(events::add); t += frameMs }
            while (t < start + period) { detector.onFrame(t, quiet)?.let(events::add); t += frameMs }
        }
        assertEquals(2, events.size)
    }

    @Test fun `a long continuous noise is not a snore`() {
        assertTrue(run(SnoreDetector(frameMs), episodes = 6, episodeMs = 5_000, periodMs = 9_000).isEmpty())
    }

    @Test fun `clicks too short to be snores do not count`() {
        assertTrue(run(SnoreDetector(frameMs), episodes = 8, episodeMs = 150, periodMs = 4_000).isEmpty())
    }

    @Test fun `high pitched sounds do not count`() {
        assertTrue(run(SnoreDetector(frameMs), episodes = 6, episodeMs = 1_000, periodMs = 4_000, sound = hiss).isEmpty())
    }

    @Test fun `irregular sounds such as talking do not count`() {
        val detector = SnoreDetector(frameMs)
        val events = mutableListOf<SnoreEvent>()
        var t = 0L
        while (t < 10_000) { detector.onFrame(t, quiet); t += frameMs }
        for (period in listOf(2_100L, 9_000L, 2_200L, 11_000L, 2_000L)) {
            val start = t
            while (t < start + 800) { detector.onFrame(t, snore)?.let(events::add); t += frameMs }
            while (t < start + period) { detector.onFrame(t, quiet)?.let(events::add); t += frameMs }
        }
        assertTrue(events.isEmpty())
    }

    @Test fun `a short dip inside a snore does not split it`() {
        val detector = SnoreDetector(frameMs)
        var t = 0L
        while (t < 10_000) { detector.onFrame(t, quiet); t += frameMs }
        detector.onFrame(t, snore); t += frameMs
        detector.onFrame(t, quiet); t += frameMs
        repeat(10) { detector.onFrame(t, snore); t += frameMs }
        var event: SnoreEvent? = null
        repeat(5) { event = event ?: detector.onFrame(t, quiet); t += frameMs }
        assertNull("one episode is not yet a pattern", event)
    }

    @Test fun `the quiet level follows the room`() {
        val detector = SnoreDetector(frameMs)
        repeat(200) { detector.onFrame(it * frameMs, FrameFeatures(-50f, 300f, 0.5f)) }
        assertTrue("rises slowly towards a louder room: ${detector.floorDb}", detector.floorDb > -70f && detector.floorDb < -60f)
        repeat(30) { detector.onFrame(it * frameMs, FrameFeatures(-80f, 300f, 0.5f)) }
        assertEquals(-80f, detector.floorDb, 1f)
        assertFalse(detector.isSnoreLike(FrameFeatures(-70f, 200f, 0.9f)))
    }
}
