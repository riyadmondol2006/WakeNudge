package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.engine.audio.Torch
import com.riyadm.wakenudge.engine.audio.TorchFlashPolicy
import com.riyadm.wakenudge.engine.audio.TorchFlasher
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The torch for an ignored alarm: only when set and ignored, never faster than once a second, off when it should be. */
@OptIn(ExperimentalCoroutinesApi::class)
class TorchFlasherTest {
    private class RecordingTorch(private val now: () -> Long) : Torch {
        val calls = mutableListOf<Pair<Long, Boolean>>()
        var lit = false
        var works = true
        override fun set(on: Boolean): Boolean {
            if (!works) return false
            calls += now() to on
            lit = on
            return true
        }
    }

    private fun session(torch: Boolean = true, intensified: Boolean = true, kind: RingKind = RingKind.PRIMARY, phase: RingPhase = RingPhase.RINGING) = RingSession(
        alarm = Alarm(id = 1, antiOversleep = AntiOversleepSettings(torchWhenIgnored = torch)),
        kind = kind, phase = phase, eventId = 1, scheduledAt = 0, startedAt = 0, intensified = intensified,
    )

    private class Rig(scope: TestScope) {
        val store = RingSessionStore()
        val camera = MutableStateFlow(0)
        val time = object : TimeSource {
            override fun nowMillis() = scope.testScheduler.currentTime
            override fun zone(): ZoneId = ZoneId.of("UTC")
        }
        val torch = RecordingTorch { scope.testScheduler.currentTime }
        val flasher = TorchFlasher(store, time, torch, SilentLog(), camera)
    }

    @Test fun `an ignored alarm set to flash flashes once a second, never faster`() = runTest {
        val rig = Rig(this)
        rig.flasher.start(backgroundScope)
        rig.store.set(session())
        advanceTimeBy(20_000)
        val ons = rig.torch.calls.filter { it.second }.map { it.first }
        assertTrue("it flashes: ${ons.size}", ons.size in 18..21)
        ons.zipWithNext { a, b -> assertTrue("on again after ${b - a} ms", b - a >= 1_000) }
        val all = rig.torch.calls.map { it.first }
        all.zipWithNext { a, b -> assertTrue("a change every ${b - a} ms", b - a >= TorchFlashPolicy.HALF_PERIOD_MS) }
        rig.store.set(null)
        runCurrent()
        assertFalse("off when the ring ends", rig.torch.lit)
    }

    @Test fun `with the setting off, or before it is ignored, the torch is never touched`() = runTest {
        for (s in listOf(session(torch = false), session(intensified = false), session(kind = RingKind.TIMER), session(phase = RingPhase.SUNRISE))) {
            val rig = Rig(this)
            rig.flasher.start(backgroundScope)
            rig.store.set(s)
            advanceTimeBy(5_000)
            assertEquals("$s", emptyList<Pair<Long, Boolean>>(), rig.torch.calls)
        }
    }

    @Test fun `it stops while a camera mission is open, and during a pause or a mute`() = runTest {
        val rig = Rig(this)
        rig.flasher.start(backgroundScope)
        rig.store.set(session())
        advanceTimeBy(3_000)
        rig.camera.value = 1
        runCurrent()
        assertFalse("off at once for the camera", rig.torch.lit)
        val before = rig.torch.calls.size
        advanceTimeBy(5_000)
        assertEquals("nothing while the camera is open", before, rig.torch.calls.size)
        rig.camera.value = 0
        advanceTimeBy(3_000)
        assertTrue(rig.torch.calls.size > before)

        rig.store.update { it.copy(pausedUntil = currentTime + 10_000) }
        advanceTimeBy(1_000)
        val paused = rig.torch.calls.size
        advanceTimeBy(5_000)
        assertFalse(rig.torch.lit)
        assertEquals("nothing during an Emergency pause", paused, rig.torch.calls.size)

        rig.store.update { it.copy(pausedUntil = 0, muteStartedAt = currentTime) }
        advanceTimeBy(1_000)
        val muted = rig.torch.calls.size
        advanceTimeBy(5_000)
        assertEquals("nothing during a mute", muted, rig.torch.calls.size)
        assertFalse(rig.torch.lit)
    }

    @Test fun `a torch that will not switch on is tried again no faster, and a lit one is put out when it stops`() = runTest {
        val rig = Rig(this)
        rig.torch.works = false
        rig.flasher.start(backgroundScope)
        rig.store.set(session())
        advanceTimeBy(5_000)
        assertTrue(rig.torch.calls.isEmpty())
        rig.torch.works = true
        advanceTimeBy(5_000)
        assertTrue(rig.torch.calls.isNotEmpty())
        backgroundScope.cancel()
    }
}
