package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.data.backup.RecordingScheduler
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.engine.policy.RingProof
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ways a ring used to be ended or quieted behind its back: what comes from outside the ring screen proves nothing. */
class RingCommandsTest {
    private val clock = TestClock()
    private val alarms = MemoryAlarms()
    private val history = MemoryHistory()
    private val store = RingSessionStore()
    private val runtime = realRuntime()
    private val notifier = mockk<Notifier>(relaxed = true)
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
    }
    private val controller = RingController(
        store, alarms, history, MemoryTimers(), MemorySettings(), RecordingScheduler(), mockk(relaxed = true), pending, runtime, clock, notifier,
        CountingSurfaces(), SilentLog(),
    )
    private val twoMissions = listOf(MissionConfig(MissionType.MATH), MissionConfig(MissionType.TYPING))

    private fun alarm(block: Alarm.() -> Alarm = { this }) =
        alarms.add(Alarm(label = "work", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK).block())

    private suspend fun ring(a: Alarm, at: Long = clock.now): RingSession = controller.begin(controller.onAlarmFired(a.id, at)!!)!!

    // ---- notification actions and keys --------------------------------------------------------------------------

    @Test fun `a notification action or a key cannot dismiss an alarm with a mission`() = runBlocking {
        val a = alarm { copy(missions = twoMissions) }
        val s = ring(a)
        clearMocks(pending, answers = false) // starting a ring clears out any old backups
        assertFalse(controller.dismiss(RingTarget(a.id, eventId = s.eventId)))
        assertFalse(controller.dismiss(RingTarget.of(s), RingProof.Gesture))
        assertFalse(controller.dismiss(RingTarget.of(s), RingProof.Hold))
        assertNotNull(store.current)
        assertEquals(AlarmOutcome.RINGING, history.get(s.eventId)!!.outcome)
        verify(exactly = 0) { pending.cancel(TriggerKind.BACKUP, a.id, any()) }
    }

    @Test fun `a notification action or a key cannot snooze an alarm with a mission, the ring screen can`() = runBlocking {
        val a = alarm { copy(missions = twoMissions) }
        val s = ring(a)
        assertEquals(SnoozeOutcome.NotAllowed, controller.snooze(RingTarget(a.id, eventId = s.eventId)))
        assertNotNull(store.current)
        verify(exactly = 0) { pending.setAlarmClock(TriggerKind.SNOOZE, any(), any(), any(), any(), any()) }

        assertTrue(controller.snooze(RingTarget.of(s), RingProof.Gesture) is SnoozeOutcome.Snoozed)
        assertNull(store.current)
    }

    @Test fun `a mission to snooze has to be done before a snooze counts`() = runBlocking {
        val a = alarm { copy(snoozeMissions = twoMissions) }
        val s = ring(a)
        assertEquals(SnoozeOutcome.NotAllowed, controller.snooze(RingTarget.of(s)))
        assertEquals(SnoozeOutcome.NotAllowed, controller.snooze(RingTarget.of(s), RingProof.Gesture))
        assertTrue(controller.snooze(RingTarget.of(s), RingProof.MissionsComplete) is SnoozeOutcome.Snoozed)
    }

    @Test fun `a hold-to-dismiss alarm needs its hold`() = runBlocking {
        val a = alarm { copy(antiOversleep = AntiOversleepSettings(holdToDismissSeconds = 5)) }
        val s = ring(a)
        assertFalse(controller.dismiss(RingTarget.of(s)))
        assertFalse(controller.dismiss(RingTarget.of(s), RingProof.Gesture))
        assertNotNull(store.current)
        assertTrue(controller.dismiss(RingTarget.of(s), RingProof.Hold))
    }

    @Test fun `an alarm with nothing to get around still ends from a notification or a key, and with a slide`() = runBlocking {
        val first = ring(alarm())
        assertTrue(controller.dismiss(RingTarget.of(first)))
        val second = ring(alarm())
        assertTrue(controller.dismiss(RingTarget.of(second), RingProof.Gesture))
        val third = ring(alarm())
        assertTrue(controller.snooze(RingTarget.of(third)) is SnoozeOutcome.Snoozed)
    }

    // ---- stale buttons ------------------------------------------------------------------------------------------

    @Test fun `a button left from an earlier ring of the same alarm cannot end this one`() = runBlocking {
        val a = alarm()
        val yesterday = ring(a, clock.now - 24 * 3_600_000L)
        assertTrue(controller.dismiss(RingTarget.of(yesterday)))
        val today = ring(a)
        assertTrue(today.eventId != yesterday.eventId)

        val stale = RingTarget.of(yesterday)
        assertFalse(controller.dismiss(stale))
        assertEquals(SnoozeOutcome.NotRinging, controller.snooze(stale))
        assertFalse(controller.emergencyStop(stale, RingProof.Hold))
        assertNotNull(store.current)
        assertEquals(AlarmOutcome.RINGING, history.get(today.eventId)!!.outcome)
    }

    @Test fun `a stale Dismiss falling through to the snoozed path leaves a live ring alone`() = runBlocking {
        val a = alarm()
        val s = ring(a)
        clearMocks(pending, answers = false) // starting a ring clears out any old backups
        controller.dismissSnoozed(a.id, s.eventId - 1)
        controller.dismissSnoozed(a.id)
        assertNotNull(store.current)
        assertEquals(AlarmOutcome.RINGING, history.get(s.eventId)!!.outcome)
        verify(exactly = 0) { pending.cancel(TriggerKind.BACKUP, a.id, any()) }
    }

    // ---- the Emergency stop -------------------------------------------------------------------------------------

    @Test fun `the Emergency stop on an alarm that has it off does nothing`() = runBlocking {
        val a = alarm { copy(missions = twoMissions, antiOversleep = AntiOversleepSettings(emergencyStopEnabled = false)) }
        val s = ring(a)
        assertFalse(controller.emergencyStop(RingTarget.of(s), RingProof.Hold))
        assertNotNull(store.current)
        assertEquals(AlarmOutcome.RINGING, history.get(s.eventId)!!.outcome)
    }

    @Test fun `the Emergency stop needs its hold and is recorded honestly`() = runBlocking {
        val a = alarm { copy(missions = twoMissions) }
        val s = ring(a)
        assertFalse(controller.emergencyStop(RingTarget.of(s)))
        assertNotNull(store.current)
        assertTrue(controller.emergencyStop(RingTarget.of(s), RingProof.Hold))
        assertNull(store.current)
        assertEquals(AlarmOutcome.EMERGENCY_STOPPED, history.get(s.eventId)!!.outcome)
    }

    @Test fun `the silent sunrise cannot be emergency stopped by a stray call`() = runBlocking {
        val a = alarm()
        controller.begin(RingRequest(a.id, -1, clock.now, RingKind.PRIMARY, phase = RingPhase.SUNRISE))
        assertFalse(controller.emergencyStop(proof = RingProof.Hold))
        assertNotNull(store.current)
    }

    // ---- counting missions --------------------------------------------------------------------------------------

    @Test fun `claiming the missions are complete is not enough, the count has to say so`() = runBlocking {
        val a = alarm { copy(missions = twoMissions) }
        val s = ring(a)
        assertFalse(controller.dismiss(RingTarget.of(s), RingProof.MissionsComplete))
        controller.noteMissionDone(1)
        assertFalse(controller.dismiss(RingTarget.of(s), RingProof.MissionsComplete))
        controller.noteMissionDone(2)
        assertTrue(controller.dismiss(RingTarget.of(s), RingProof.MissionsComplete))
        assertNull(store.current)
    }

    @Test fun `mission progress moves one step at a time and never back`() = runBlocking {
        val a = alarm { copy(missions = twoMissions) }
        ring(a)
        controller.noteMissionDone(2)
        assertEquals("a jump counts for nothing", 0, store.current!!.missionsDone)
        controller.noteMissionDone(1)
        controller.noteMissionDone(1)
        assertEquals("a repeat counts once", 1, store.current!!.missionsDone)
        controller.noteMissionDone(0)
        assertEquals("never back", 1, store.current!!.missionsDone)
        controller.noteMissionDone(2)
        controller.noteMissionDone(3)
        assertEquals("never past the end", 2, store.current!!.missionsDone)
    }

    @Test fun `a dismissal records the missions actually done`() = runBlocking {
        val a = alarm { copy(missions = twoMissions) }
        val s = ring(a)
        controller.noteMissionDone(1)
        controller.noteMissionDone(2)
        controller.dismiss(RingTarget.of(s), RingProof.MissionsComplete)
        val event = history.get(s.eventId)!!
        assertEquals(AlarmOutcome.DISMISSED, event.outcome)
        assertEquals(2, event.missionsDone)
        assertEquals(2, event.missionsTotal)
    }

    @Test fun `an Emergency stop part way records only what was done`() = runBlocking {
        val a = alarm { copy(missions = twoMissions) }
        val s = ring(a)
        controller.noteMissionDone(1)
        controller.emergencyStop(RingTarget.of(s), RingProof.Hold)
        assertEquals(1, history.get(s.eventId)!!.missionsDone)
    }

    @Test fun `in safe mode a mission alarm is not ended from its notification, only by the plain screen's sums`() = runBlocking {
        runtime.recordRingCrash(clock.now - 2_000)
        runtime.recordRingCrash(clock.now - 1_000)
        val a = alarm { copy(missions = twoMissions, antiOversleep = antiOversleep.copy(fallbackCount = 3)) }
        val s = ring(a)
        assertTrue(s.safeMode)
        assertFalse("no one-tap way out of a mission", controller.dismiss(RingTarget.of(s)))
        assertFalse("nor a claim the controller has not counted", controller.dismiss(RingTarget.of(s), RingProof.SafeSumsComplete))
        controller.noteSafeSum(1)
        controller.noteSafeSum(3)
        assertEquals("one at a time", 1, store.current!!.safeSumsDone)
        controller.noteSafeSum(2)
        assertFalse(controller.dismiss(RingTarget.of(s), RingProof.SafeSumsComplete))
        controller.noteSafeSum(3)
        assertTrue(controller.dismiss(RingTarget.of(s), RingProof.SafeSumsComplete))
        val event = history.get(s.eventId)!!
        assertEquals(AlarmOutcome.DISMISSED, event.outcome)
        assertEquals("no mission was done", 0, event.missionsDone)
    }

    @Test fun `in safe mode an alarm that ends with one tap still ends from its notification`() = runBlocking {
        runtime.recordRingCrash(clock.now - 2_000)
        runtime.recordRingCrash(clock.now - 1_000)
        val s = ring(alarm())
        assertTrue(s.safeMode)
        assertTrue(controller.dismiss(RingTarget.of(s)))
        assertEquals(AlarmOutcome.DISMISSED, history.get(s.eventId)!!.outcome)
    }

    // ---- the snoozed notification -------------------------------------------------------------------------------

    @Test fun `a snoozed alarm with a mission cannot be dismissed from a notification`() = runBlocking {
        val a = alarm { copy(missions = twoMissions) }
        val s = ring(a)
        controller.snooze(RingTarget.of(s), RingProof.Gesture)
        controller.dismissSnoozed(a.id, s.eventId)
        assertEquals("still waiting to ring again", AlarmOutcome.RINGING, history.get(s.eventId)!!.outcome)
        assertTrue(history.get(s.eventId)!!.snoozeUntil > clock.now)
        verify(exactly = 0) { pending.cancel(TriggerKind.SNOOZE, a.id, any()) }
    }

    @Test fun `a snoozed alarm that cannot be read keeps its snooze`() = runBlocking {
        val a = alarm()
        val s = ring(a)
        controller.snooze(RingTarget.of(s))
        alarms.failReads = IOException("database unreadable")
        controller.dismissSnoozed(a.id, s.eventId)
        verify(exactly = 0) { pending.cancel(TriggerKind.SNOOZE, a.id, any()) }
    }

    @Test fun `a snoozed alarm still dismisses when the history cannot be written`() = runBlocking {
        val a = alarm()
        val s = ring(a)
        controller.snooze(RingTarget.of(s))
        history.failure = IOException("database unreadable")
        controller.dismissSnoozed(a.id, s.eventId)
        verify { pending.cancel(TriggerKind.SNOOZE, a.id, 0) }
    }

    @Test fun `a Dismiss from an earlier snooze does not close a later one`() = runBlocking {
        val a = alarm()
        val s = ring(a)
        controller.snooze(RingTarget.of(s))
        controller.dismissSnoozed(a.id, s.eventId + 7)
        assertEquals(AlarmOutcome.RINGING, history.get(s.eventId)!!.outcome)
    }

    // ---- the ring timeout ---------------------------------------------------------------------------------------

    @Test fun `an alarm set to Never is not ended by the timeout path`() = runBlocking {
        val a = alarm { copy(antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 0, backupEnabled = false)) }
        ring(a)
        clock.now += 48 * 3_600_000L
        controller.tick()
        assertNotNull(store.current)
    }
}
