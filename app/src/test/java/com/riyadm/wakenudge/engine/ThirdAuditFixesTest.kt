package com.riyadm.wakenudge.engine

import android.database.sqlite.SQLiteFullException
import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.data.backup.RecordingScheduler
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.domain.model.WakeCheck
import com.riyadm.wakenudge.domain.model.WakeCheckSettings
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.WakeCheckRepository
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.engine.notify.Notifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The third audit: each test is a concrete way an alarm went silent, rang out of nowhere or left the wrong data. */
class ThirdAuditFixesTest {
    private val clock = TestClock()
    private val alarms = MemoryAlarms()
    private val history = MemoryHistory()
    private val timers = MemoryTimers()
    private val store = RingSessionStore()
    private val runtime = realRuntime()
    private val notifier = mockk<Notifier>(relaxed = true)
    private val wakeChecks = mockk<WakeCheckController>(relaxed = true)
    private val scheduler = RecordingScheduler()
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
    }
    private val controller = RingController(
        store, alarms, history, timers, MemorySettings(), scheduler, wakeChecks, pending, runtime, clock, notifier, CountingSurfaces(), SilentLog(),
    )

    private fun alarm(block: Alarm.() -> Alarm = { this }) =
        alarms.add(Alarm(label = "work", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK).block())

    private suspend fun ring(a: Alarm): RingSession = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!

    // ---- a snooze across a restart --------------------------------------------------------------------------------

    /** A ring first fired [firedAgo] ago and snoozed until [snoozeIn] from now; Android has forgotten the trigger (restart). */
    private suspend fun snoozedEvent(a: Alarm, firedAgo: Long, snoozeIn: Long): Long = history.start(
        AlarmEvent(
            alarmId = a.id, label = "", scheduledAt = clock.now - firedAgo, firedAt = clock.now - firedAgo,
            snoozeCount = 3, snoozeUntil = clock.now + snoozeIn,
        ),
    )

    @Test fun `a snooze still rings when the phone restarts more than an hour after the alarm first rang`() = runBlocking {
        val a = alarm()
        // 07:00 rang, snoozed three times; restarted at 08:40 with the snooze due at 09:00.
        val id = snoozedEvent(a, firedAgo = 100 * MINUTE, snoozeIn = 20 * MINUTE)
        // Housekeeping at process start runs before the boot broadcast has re-armed anything.
        every { pending.isScheduled(any(), any(), any()) } returns false
        controller.recoverStaleState()
        assertEquals("the ring is still waiting for its snooze", AlarmOutcome.RINGING, history.get(id)!!.outcome)

        clock.now += 20 * MINUTE
        val request = controller.onSnoozeExpired(a.id, id)
        assertNotNull("the alarm must ring again", request)
        assertEquals(3, request!!.snoozeCount)
    }

    @Test fun `a snooze that came due while the phone was off still rings on start-up`() = runBlocking {
        val a = alarm()
        val id = snoozedEvent(a, firedAgo = 2 * HOUR, snoozeIn = -30 * MINUTE)
        every { pending.isScheduled(any(), any(), any()) } returns false
        controller.recoverStaleState()
        assertNotNull(controller.onSnoozeExpired(a.id, id))
    }

    @Test fun `a backup still pending keeps its event open across a restart`() = runBlocking {
        val a = alarm()
        val id = history.start(
            AlarmEvent(alarmId = a.id, label = "", scheduledAt = 1, firedAt = clock.now - 2 * HOUR, pendingBackups = listOf(clock.now + MINUTE)),
        )
        every { pending.isScheduled(any(), any(), any()) } returns false
        controller.recoverStaleState()
        assertEquals(AlarmOutcome.RINGING, history.get(id)!!.outcome)
    }

    @Test fun `a ring left open with nothing to come back is still closed as missed`() = runBlocking {
        val a = alarm()
        val abandoned = snoozedEvent(a, firedAgo = 5 * HOUR, snoozeIn = -2 * HOUR) // too late for the scheduler to restore
        val plain = history.start(AlarmEvent(alarmId = a.id, label = "", scheduledAt = 2, firedAt = clock.now - 2 * HOUR))
        every { pending.isScheduled(any(), any(), any()) } returns false
        controller.recoverStaleState()
        assertEquals(AlarmOutcome.MISSED, history.get(abandoned)!!.outcome)
        assertEquals(AlarmOutcome.MISSED, history.get(plain)!!.outcome)
    }

    // ---- a "still awake?" re-ring that was ignored ------------------------------------------------------------------

    private fun schedulerWith(checks: List<WakeCheck>): AlarmSchedulerImpl {
        val repo = mockk<WakeCheckRepository>(relaxed = true)
        coEvery { repo.getAll() } returns checks
        every { pending.setExact(any(), any(), any(), any(), any(), any()) } returns true
        every { pending.setInexact(any(), any(), any()) } returns true
        every { pending.fallbackCount } returns 0
        val settings = MemorySettings()
        return AlarmSchedulerImpl(
            alarms, timers, repo, history, settings, NextTriggerCalculator(),
            SchedulingContextProvider(
                settings,
                mockk<GroupRepository>().also { coEvery { it.getGroups() } returns emptyList() },
                mockk<ExceptionRepository>().also { coEvery { it.getDates() } returns emptyList() },
                mockk(relaxed = true), clock,
            ),
            pending, clock, runtime, CountingSurfaces(), SilentLog(), mockk(relaxed = true), store, notifier,
        )
    }

    @Test fun `a reschedule hours later does not ring an alarm whose check deadline has already fired`() = runBlocking {
        val a = alarm { copy(wakeCheck = WakeCheckSettings(enabled = true)) }
        // What onDeadline leaves behind when it rings the alarm again: still PROMPTED, with no deadline any more.
        val spent = WakeCheck(a.id, 5, WakeCheck.Phase.PROMPTED, promptAt = clock.now - HOUR, deadlineAt = 0, checksDone = 0, reRings = 1)
        schedulerWith(listOf(spent)).rescheduleAll("watchdog")
        verify(exactly = 0) { pending.setAlarmClock(TriggerKind.WAKE_CHECK_DEADLINE, a.id, any(), any(), any(), any()) }
    }

    @Test fun `a check still waiting for its answer is re-armed after a reboot`() = runBlocking {
        val a = alarm { copy(wakeCheck = WakeCheckSettings(enabled = true)) }
        val deadline = clock.now + MINUTE
        val waiting = WakeCheck(a.id, 5, WakeCheck.Phase.PROMPTED, promptAt = clock.now - MINUTE, deadlineAt = deadline, checksDone = 0, reRings = 0)
        schedulerWith(listOf(waiting)).rescheduleAll("boot")
        verify { pending.setAlarmClock(TriggerKind.WAKE_CHECK_DEADLINE, a.id, deadline, any(), any(), any()) }
    }

    @Test fun `an ignored re-ring that ends with no backup to come ends its check as failed`() = runBlocking {
        val a = alarm { copy(wakeCheck = WakeCheckSettings(enabled = true), antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 1, backupEnabled = false)) }
        val eventId = history.start(AlarmEvent(alarmId = a.id, label = "", scheduledAt = 1, firedAt = clock.now))
        coEvery { wakeChecks.reRingsFor(a.id, eventId) } returns 1
        controller.begin(RingRequest(a.id, eventId, 1, RingKind.WAKE_CHECK_RERING))
        clock.now += MINUTE
        controller.tick()
        assertNull(store.current)
        coVerify { wakeChecks.abandon(a.id, WakeCheckStatus.FAILED, any()) }
    }

    @Test fun `an ignored re-ring with a backup still to come keeps its check for that backup`() = runBlocking {
        val a = alarm { copy(wakeCheck = WakeCheckSettings(enabled = true), antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 1)) }
        val eventId = history.start(AlarmEvent(alarmId = a.id, label = "", scheduledAt = 1, firedAt = clock.now))
        coEvery { wakeChecks.reRingsFor(a.id, eventId) } returns 1
        every { pending.isScheduled(TriggerKind.BACKUP, a.id, 1) } returns true
        controller.begin(RingRequest(a.id, eventId, 1, RingKind.WAKE_CHECK_RERING))
        clock.now += MINUTE
        controller.tick()
        coVerify(exactly = 0) { wakeChecks.abandon(any(), any(), any()) }
    }

    @Test fun `an ordinary ignored alarm does not touch a check belonging to another ring`() = runBlocking {
        val a = alarm { copy(antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 1, backupEnabled = false)) }
        ring(a)
        clock.now += MINUTE
        controller.tick()
        coVerify(exactly = 0) { wakeChecks.abandon(any(), any(), any()) }
    }

    // ---- "Wake now" on the snoozed notification ---------------------------------------------------------------------

    @Test fun `Wake now on a snoozed test alarm rings it`() = runBlocking {
        every { pending.isScheduled(TriggerKind.SNOOZE, RingIds.TEST_ALARM_ID, 0) } returns true
        val request = controller.wakeNow(RingIds.TEST_ALARM_ID)
        assertNotNull("the test alarm has no history event, but it is snoozed", request)
        assertEquals(RingKind.TEST, request!!.kind)
        verify { notifier.cancelSnoozed(RingIds.TEST_ALARM_ID) }
    }

    @Test fun `Wake now rings even when the history cannot be read`() = runBlocking {
        val a = alarm()
        every { pending.isScheduled(TriggerKind.SNOOZE, a.id, 0) } returns true
        history.failure = IOException("database unreadable")
        val request = controller.wakeNow(a.id)
        assertNotNull("the snooze must not be cancelled with nothing ringing in its place", request)
        assertEquals(RingKind.SNOOZE_RETURN, request!!.kind)
    }

    @Test fun `Wake now on a stale notification rings nothing but does take the notification away`() = runBlocking {
        val a = alarm()
        assertNull(controller.wakeNow(a.id))
        verify { notifier.cancelSnoozed(a.id) }
    }

    // ---- Dismiss on the snoozed notification ----------------------------------------------------------------------

    @Test fun `dismissing a snoozed delete-after-use alarm deletes it`() = runBlocking {
        val a = alarm { copy(repeatDaysMask = 0, deleteAfterUse = true) }
        ring(a)
        controller.snooze()
        controller.dismissSnoozed(a.id)
        assertNull(alarms.getAlarm(a.id))
    }

    @Test fun `dismissing a snoozed alarm starts its still-awake check and closes the event`() = runBlocking {
        val a = alarm { copy(wakeCheck = WakeCheckSettings(enabled = true)) }
        val session = ring(a)
        controller.snooze()
        controller.dismissSnoozed(a.id)
        coVerify { wakeChecks.startAfterDismissal(any(), session.eventId, any()) }
        val event = history.get(session.eventId)!!
        assertEquals(AlarmOutcome.DISMISSED, event.outcome)
        assertEquals(0L, event.snoozeUntil)
    }

    @Test fun `a stale Dismiss on an alarm with no open ring changes nothing`() = runBlocking {
        val a = alarm { copy(repeatDaysMask = 0, deleteAfterUse = true, wakeCheck = WakeCheckSettings(enabled = true)) }
        ring(a)
        assertTrue(controller.dismiss()) // the check keeps the alarm for now, switched off
        val kept = alarms.getAlarm(a.id)
        assertNotNull(kept)
        coVerify(exactly = 1) { wakeChecks.startAfterDismissal(any(), any(), any()) }

        controller.dismissSnoozed(a.id) // the ring's own Dismiss button, tapped a moment too late
        assertEquals(kept, alarms.getAlarm(a.id))
        coVerify(exactly = 1) { wakeChecks.startAfterDismissal(any(), any(), any()) }
    }

    // ---- a timer and failing storage ------------------------------------------------------------------------------

    @Test fun `a finished timer rings even if marking it as ringing fails on a full disk`() = runBlocking {
        val timer = CountdownTimer(id = 3, durationMs = 60_000, state = TimerState.RUNNING, endsAtMillis = clock.now)
        timers.rows[3] = timer
        val full = mockk<com.riyadm.wakenudge.domain.repository.TimerRepository>(relaxed = true)
        coEvery { full.getTimer(3) } returns timer
        coEvery { full.save(any()) } throws SQLiteFullException("database or disk is full")
        val c = RingController(
            store, alarms, history, full, MemorySettings(), scheduler, wakeChecks, pending, runtime, clock, notifier, CountingSurfaces(), SilentLog(),
        )
        val request = c.onTimerFired(3)
        assertNotNull("a silent timer is the failure", request)
        assertEquals(RingKind.TIMER, request!!.kind)
        assertEquals(3L, request.timerId)
    }

    @Test fun `a finished timer rings even if its row cannot be read`() = runBlocking {
        timers.failure = IOException("database unreadable")
        val request = controller.onTimerFired(3)
        assertNotNull(request)
        assertNotNull("and the ring itself starts as a plain timer", controller.begin(request!!))
    }

    @Test fun `a timer known to be paused or gone still does not ring`() = runBlocking {
        timers.rows[4] = CountdownTimer(id = 4, durationMs = 60_000, state = TimerState.PAUSED)
        assertNull(controller.onTimerFired(4))
        assertNull(controller.onTimerFired(99))
        assertNull(store.current)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
