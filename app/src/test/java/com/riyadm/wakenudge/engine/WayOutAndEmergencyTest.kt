package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.data.backup.RecordingScheduler
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.engine.policy.RingPolicy
import com.riyadm.wakenudge.engine.policy.RingProof
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The way out (a hold, or a chain of tasks that opens late), Emergency pauses and the backup mission, against the real controller. */
class WayOutAndEmergencyTest {
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
    private val mission = listOf(MissionConfig(MissionType.QR_BARCODE))

    private fun alarm(ao: AntiOversleepSettings, missions: List<MissionConfig> = mission) = alarms.add(
        Alarm(label = "x", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK, missions = missions, snooze = SnoozeSettings(enabled = false), antiOversleep = ao),
    )

    private val hard = AntiOversleepSettings(
        emergencyStopEnabled = false, wayOutDelayMinutes = 3, wayOutCount = 10, fallbackCount = 5, ringTimeoutMinutes = 0,
        resumeWindowMinutes = 720, maxResumes = 5, backupEnabled = false,
    )

    private suspend fun ring(a: Alarm): RingSession = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!

    private suspend fun restart() {
        store.set(null)
        controller = newController()
        boots++
        clock.restart(1_000)
        controller.reviveDurableRing()
        controller.onSentinelFired()?.let { controller.begin(it) }
    }

    @Test fun `on Normal the way out is a 10 s hold, recorded as an emergency stop`() = runBlocking {
        val s = ring(alarm(AntiOversleepSettings(backupEnabled = false)))
        assertFalse("a hold proves nothing else", controller.emergencyStop(RingTarget.of(s), RingProof.Gesture))
        assertTrue(controller.emergencyStop(RingTarget.of(s), RingProof.Hold))
        assertEquals(AlarmOutcome.EMERGENCY_STOPPED, history.get(s.eventId)!!.outcome)
        assertEquals(10, RingPolicy.WAY_OUT_HOLD_SECONDS)
    }

    @Test fun `an alarm that ends with one tap has no hold to skip anything`() = runBlocking {
        val s = ring(alarm(AntiOversleepSettings(backupEnabled = false), missions = emptyList()))
        assertFalse(controller.emergencyStop(RingTarget.of(s), RingProof.Hold))
        assertNotNull(store.current)
    }

    @Test fun `on Hard the way out opens after 3 minutes and then takes 10 tasks, counted here one at a time`() = runBlocking {
        val s = ring(alarm(hard))
        assertFalse(controller.emergencyStop(RingTarget.of(s), RingProof.Hold))
        controller.noteWayOutStep(1)
        assertEquals("not before it opens", 0, store.current!!.wayOutProgress)
        clock.now += 3 * 60_000L - 1_000
        controller.noteWayOutStep(1)
        assertEquals(0, store.current!!.wayOutProgress)
        clock.now += 1_000
        controller.noteWayOutStep(1)
        controller.noteWayOutStep(5)
        assertEquals("one at a time", 1, store.current!!.wayOutProgress)
        for (n in 2..9) controller.noteWayOutStep(n)
        assertFalse("nine of ten is not the way out", controller.wayOut(RingTarget.of(s), RingProof.WayOutComplete))
        controller.noteWayOutStep(10)
        controller.noteWayOutStep(11)
        assertEquals(10, store.current!!.wayOutProgress)
        assertFalse("its proof is needed", controller.wayOut(RingTarget.of(s), RingProof.Gesture))
        assertTrue(controller.wayOut(RingTarget.of(s), RingProof.WayOutComplete))
        assertEquals(AlarmOutcome.EMERGENCY_STOPPED, history.get(s.eventId)!!.outcome)
    }

    @Test fun `way-out progress and the 3 minutes survive a restart`() = runBlocking {
        val s = ring(alarm(hard))
        clock.now += 4 * 60_000L
        for (n in 1..4) controller.noteWayOutStep(n)
        restart()
        val back = store.current!!
        assertEquals(1, back.resumes)
        assertEquals(4, back.wayOutProgress)
        controller.noteWayOutStep(5)
        assertEquals("open still: counted from when it first rang", 5, store.current!!.wayOutProgress)
        for (n in 6..10) controller.noteWayOutStep(n)
        assertTrue(controller.wayOut(RingTarget.of(store.current!!), RingProof.WayOutComplete))
        assertEquals(AlarmOutcome.EMERGENCY_STOPPED, history.get(s.eventId)!!.outcome)
    }

    @Test fun `with the way out removed there is neither a hold nor a chain`() = runBlocking {
        for (ao in listOf(AntiOversleepSettings(wayOutRemoved = true, backupEnabled = false), hard.copy(wayOutRemoved = true))) {
            val s = ring(alarm(ao))
            clock.now += 60 * 60_000L
            assertFalse(controller.emergencyStop(RingTarget.of(s), RingProof.Hold))
            for (n in 1..30) controller.noteWayOutStep(n)
            assertEquals(0, store.current!!.wayOutProgress)
            assertFalse(controller.wayOut(RingTarget.of(s), RingProof.WayOutComplete))
            // Emergency still works, and the mission (or its backup) still ends it.
            assertTrue(controller.pauseForEmergency(RingTarget.of(s), RingProof.Gesture))
            controller.noteFallbackUsed()
            controller.noteMissionDone(1)
            assertTrue(controller.dismiss(RingTarget.of(s), RingProof.MissionsComplete))
            assertTrue(history.get(s.eventId)!!.fallbackUsed)
        }
    }

    @Test fun `the first two Emergency pauses silence the alarm, later ones only stop the pull-back`() = runBlocking {
        val s = ring(alarm(hard))
        assertFalse("a pause needs the button", controller.pauseForEmergency(RingTarget.of(s), RingProof.None))
        for (n in 1..4) {
            assertTrue(controller.pauseForEmergency(RingTarget.of(s), RingProof.Gesture))
            val now = store.current!!
            assertEquals(n, now.pauses)
            assertTrue(now.pausedAt(clock.elapsedMillis()))
            assertEquals("pause $n", n <= 2, now.pauseSilences)
            assertEquals("counted outside the process too", n, durable.ring()!!.pauses)
            clock.now += RingPolicy.EMERGENCY_PAUSE_MS
            controller.tick()
            assertFalse("over after 3 minutes", store.current!!.pausedAt(clock.elapsedMillis()))
            assertFalse(store.current!!.pauseSilences)
        }
        assertNotNull("a pause never ends the ring", store.current)
        controller.noteMissionDone(1)
        assertTrue(controller.dismiss(RingTarget.of(s), RingProof.MissionsComplete))
        assertEquals(4, history.get(s.eventId)!!.pauses)
    }

    @Test fun `an ignored ring with a timeout counts from the end of a pause, not from before the call`() = runBlocking {
        val s = ring(alarm(AntiOversleepSettings(ringTimeoutMinutes = 5, backupEnabled = false)))
        clock.now += 4 * 60_000L
        controller.pauseForEmergency(RingTarget.of(s), RingProof.Gesture)
        clock.now += RingPolicy.EMERGENCY_PAUSE_MS
        controller.tick()
        clock.now += 4 * 60_000L
        controller.tick()
        assertNotNull(store.current)
    }

    @Test fun `a backup mission that stood in is recorded, and survives a restart`() = runBlocking {
        val s = ring(alarm(hard))
        controller.noteFallbackUsed()
        assertTrue(durable.ring()!!.fallback)
        restart()
        assertTrue(store.current!!.fallback)
        controller.noteMissionDone(1)
        assertTrue(controller.dismiss(RingTarget.of(store.current!!), RingProof.MissionsComplete))
        assertTrue(history.get(s.eventId)!!.fallbackUsed)
    }
}
