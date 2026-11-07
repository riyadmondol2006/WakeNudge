package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.data.backup.RecordingScheduler
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.model.VolumeKeyAction
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.engine.policy.RingProof
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mute budget (stored with the ring, never silent after a restart) and calming only on mission progress. */
class MuteAndCalmTest {
    private val clock = TestClock()
    private val alarms = MemoryAlarms()
    private val history = MemoryHistory()
    private val store = RingSessionStore()
    private var boots = 1
    private val runtime = realRuntime().also { it.bootCountSource = { boots } }
    private val notifier = mockk<Notifier>(relaxed = true)
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
    }
    private val log = SilentLog()
    private val durable = DurableRingStore(runtime, log)
    private fun newController() = RingController(
        store, alarms, history, MemoryTimers(), MemorySettings(), RecordingScheduler(), mockk(relaxed = true), pending, runtime, clock, notifier, CountingSurfaces(), log,
    )
    private var controller = newController()
    private val qr = listOf(MissionConfig(MissionType.QR_BARCODE))

    private fun alarm(ao: AntiOversleepSettings, missions: List<MissionConfig> = qr) = alarms.add(
        Alarm(label = "x", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK, missions = missions, snooze = SnoozeSettings(enabled = false), antiOversleep = ao),
    )

    private val hard = AntiOversleepSettings(
        emergencyStopEnabled = false, wayOutDelayMinutes = 3, ringTimeoutMinutes = 0, resumeWindowMinutes = 720, maxResumes = 5,
        muteBudget = 2, muteSeconds = 20, calmOnlyOnProgress = true, volumeKeyAction = VolumeKeyAction.NOTHING, backupEnabled = false,
    )

    private suspend fun ring(a: Alarm) = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!
    private fun silenced() = store.current!!.silencedUntil > clock.elapsedMillis()

    private suspend fun seconds(n: Int, each: () -> Unit = {}) = repeat(n) {
        clock.now += 1_000
        each()
        controller.tick()
    }

    @Test fun `the budget is spent one mute at a time and then the sound stays on until the mission is done`() = runBlocking {
        val s = ring(alarm(hard))
        assertFalse("a mute needs the button", controller.muteForMission(RingTarget.of(s), RingProof.None))
        assertTrue(controller.muteForMission(RingTarget.of(s), RingProof.Gesture))
        assertTrue(silenced())
        assertFalse("not twice at once", controller.muteForMission(RingTarget.of(s), RingProof.Gesture))
        assertEquals(1, store.current!!.mutesUsed)
        seconds(21) { controller.noteProgress() }
        assertFalse(silenced())
        assertTrue(controller.muteForMission(RingTarget.of(s), RingProof.Gesture))
        seconds(21) { controller.noteProgress() }
        assertFalse("the budget is spent", controller.muteForMission(RingTarget.of(s), RingProof.Gesture))
        assertFalse(silenced())
        assertEquals(2, store.current!!.mutesUsed)
    }

    @Test fun `a mute ends early without mission progress, and touching the screen does not keep it going`() = runBlocking {
        val s = ring(alarm(hard.copy(muteSeconds = 60)))
        controller.muteForMission(RingTarget.of(s), RingProof.Gesture)
        seconds(19) { controller.noteInteraction() }
        assertTrue(silenced())
        seconds(1) { controller.noteInteraction() }
        assertFalse("20 s of touches are not progress", silenced())
    }

    @Test fun `with progress a mute lasts its full length, no more`() = runBlocking {
        val s = ring(alarm(hard.copy(muteSeconds = 45)))
        controller.muteForMission(RingTarget.of(s), RingProof.Gesture)
        seconds(44) { controller.noteProgress() }
        assertTrue(silenced())
        seconds(1) { controller.noteProgress() }
        assertFalse(silenced())
    }

    @Test fun `the mutes used are saved with the ring, and a restart does not bring a mute back`() = runBlocking {
        val s = ring(alarm(hard))
        controller.muteForMission(RingTarget.of(s), RingProof.Gesture)
        assertEquals(1, durable.ring()!!.mutesUsed)
        store.set(null)
        controller = newController()
        boots++
        clock.restart(1_000)
        controller.reviveDurableRing()
        controller.onSentinelFired()?.let { controller.begin(it) }
        val back = store.current!!
        assertEquals(1, back.mutesUsed)
        assertFalse("not silent after the restart", back.silencedUntil > clock.elapsedMillis())
        assertTrue(controller.muteForMission(RingTarget.of(back), RingProof.Gesture))
        assertFalse("and the budget was not reset", controller.run { seconds(21) { noteProgress() }; muteForMission(RingTarget.of(back), RingProof.Gesture) })
    }

    @Test fun `a volume key set to silence spends the same budget`() = runBlocking {
        val s = ring(alarm(hard.copy(volumeKeyAction = VolumeKeyAction.SILENCE_TEMPORARILY, muteBudget = 1)))
        controller.silenceTemporarily()
        assertTrue(silenced())
        seconds(21) { controller.noteProgress() }
        controller.silenceTemporarily()
        assertFalse("it was the only one", silenced())
        assertFalse(controller.muteForMission(RingTarget.of(s), RingProof.Gesture))
    }

    @Test fun `there is no mute without a mission`() = runBlocking {
        val s = ring(alarm(hard, missions = emptyList()))
        assertFalse(controller.muteForMission(RingTarget.of(s), RingProof.Gesture))
    }

    @Test fun `with calm only on progress a touch does not calm an escalated alarm, mission progress does`() = runBlocking {
        ring(alarm(hard))
        seconds(41)
        assertTrue(store.current!!.intensified)
        repeat(10) { controller.noteInteraction(); clock.now += 2_000 }
        assertTrue("touching is not being up", store.current!!.intensified)
        controller.noteProgress()
        assertFalse(store.current!!.intensified)
    }

    @Test fun `without it a touch still calms, as before`() = runBlocking {
        ring(alarm(hard.copy(calmOnlyOnProgress = false)))
        seconds(41)
        assertTrue(store.current!!.intensified)
        controller.noteInteraction()
        assertFalse(store.current!!.intensified)
    }
}
