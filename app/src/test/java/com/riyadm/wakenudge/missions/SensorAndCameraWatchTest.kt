package com.riyadm.wakenudge.missions

import com.riyadm.wakenudge.missions.camera.DarkFrameWatch
import com.riyadm.wakenudge.missions.camera.DarkFrameWatch.Verdict
import com.riyadm.wakenudge.missions.sensors.SensorLiveness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the phone, not the person, is why a sensor or a camera mission cannot be done. */
class SensorAndCameraWatchTest {
    private fun ticks(liveness: SensorLiveness, fromMs: Long, toMs: Long, onTick: (Long) -> Unit = {}): Boolean {
        var silent = false
        var t = fromMs
        while (t <= toMs) {
            onTick(t)
            silent = liveness.tick(t)
            t += 1_000
        }
        return silent
    }

    @Test fun `a sensor that says nothing for 30 s is silent, and any reading starts the count again`() {
        val liveness = SensorLiveness()
        assertFalse(ticks(liveness, 0, 29_000))
        liveness.onReading()
        assertFalse(ticks(liveness, 30_000, 58_000))
        assertTrue(ticks(liveness, 59_000, 61_000))
    }

    @Test fun `a sensor that keeps reporting is never silent`() {
        val liveness = SensorLiveness()
        assertFalse(ticks(liveness, 0, 600_000) { liveness.onReading() })
    }

    @Test fun `lying still does not make a step sensor silent, walking without steps does`() {
        val liveness = SensorLiveness(gated = true)
        assertFalse("ten minutes still in bed", ticks(liveness, 0, 600_000))
        assertTrue("half a minute of moving with no step", ticks(liveness, 601_000, 640_000) { liveness.onMotion(it) })
    }

    @Test fun `a gap in ticks, the screen off, counts for little`() {
        val liveness = SensorLiveness()
        liveness.tick(0)
        assertFalse(liveness.tick(3_600_000))
    }

    @Test fun `a black camera tries the torch, then gives up after a minute`() {
        val watch = DarkFrameWatch()
        var t = 0L
        var torchAt = -1L
        var verdict = Verdict.FINE
        while (verdict != Verdict.DARK && t < 120_000) {
            verdict = watch.onFrame(t, 1.2, torchAvailable = true)
            if (verdict == Verdict.TRY_TORCH && torchAt < 0) torchAt = t
            t += 100
        }
        assertEquals(DarkFrameWatch.TORCH_AFTER_MS, torchAt)
        assertEquals(Verdict.DARK, verdict)
        assertEquals(DarkFrameWatch.GIVE_UP_MS + 100, t)
    }

    @Test fun `a dark room the torch lights up is not a dead camera`() {
        val watch = DarkFrameWatch()
        var t = 0L
        while (t < 20_000) {
            watch.onFrame(t, 2.0, torchAvailable = true)
            t += 100
        }
        // The torch is on now and the room shows.
        while (t < 200_000) {
            assertEquals(Verdict.FINE, watch.onFrame(t, 25.0, torchAvailable = true))
            t += 100
        }
    }

    @Test fun `without a torch it still gives up, and time without frames does not count`() {
        val watch = DarkFrameWatch()
        assertEquals(Verdict.FINE, watch.onFrame(0, 0.5, torchAvailable = false))
        assertEquals(Verdict.FINE, watch.onFrame(50_000, 0.5, torchAvailable = false))
        var t = 50_000L
        var verdict = Verdict.FINE
        while (t <= 111_000 && verdict != Verdict.DARK) {
            assertTrue(verdict != Verdict.TRY_TORCH)
            verdict = watch.onFrame(t, 0.5, torchAvailable = false)
            t += 100
        }
        assertEquals(Verdict.DARK, verdict)
        assertTrue("counted from the frame after the gap", t >= 110_000)
    }

    @Test fun `spread of luminance`() {
        assertEquals(0.0, DarkFrameWatch.stdDev(IntArray(100) { 7 }), 1e-9)
        assertEquals(50.0, DarkFrameWatch.stdDev(IntArray(100) { if (it % 2 == 0) 0 else 100 }), 1e-9)
        assertEquals(0.0, DarkFrameWatch.stdDev(IntArray(0)), 1e-9)
    }
}
