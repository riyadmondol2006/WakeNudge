package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.data.backup.RecordingScheduler
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.SunriseSettings
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.domain.model.WakeCheckSettings
import com.riyadm.wakenudge.engine.notify.Notifier
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring state machine against the ways real mornings go wrong: a tap that lands just after another ring took over,
 * a volume key during the silent glow, the ring timeout and the backup falling due together, a timer restarted while
 * it rang, a crash that makes Android hand back rings already dealt with, and a delete-after-use alarm with a check.
 */
class RingAuditFixesTest {
    private val clock = TestClock()
    private val alarms = MemoryAlarms()
    private val history = MemoryHistory()
    private val timers = MemoryTimers()
    private val store = RingSessionStore()
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
    }
    private val controller = RingController(
        store, alarms, history, timers, MemorySettings(), RecordingScheduler(), mockk(relaxed = true), pending, realRuntime(), clock,
        mockk<Notifier>(relaxed = true), CountingSurfaces(), SilentLog(),
    )

    private fun alarm(block: Alarm.() -> Alarm = { this }) =
        alarms.add(Alarm(label = "work", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK).block())

    private suspend fun ring(a: Alarm): RingSession = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!

    // ---- a button acts on the ring it was shown for ------------------------------------------------------------

    @Test fun `a dismiss meant for another ring does nothing, the right one ends it`() = runBlocking {
        val gym = alarm()
        ring(gym)
        assertFalse(controller.dismiss(RingTarget(alarmId = 999)))
        assertNotNull("the ring on screen is untouched", store.current)
        assertEquals(SnoozeOutcome.NotRinging, controller.snooze(RingTarget(alarmId = 999)))
        assertTrue(controller.dismiss(RingTarget(gym.id)))
        assertNull(store.current)
    }

    @Test fun `a timer's Stop arriving after an alarm took over does not dismiss the alarm`() = runBlocking {
        val timer = CountdownTimer(id = 4, durationMs = 60_000, state = TimerState.RINGING)
        timers.save(timer)
        controller.begin(RingRequest(RingIds.TIMER_ALARM_BASE - 4, -1, clock.now, RingKind.TIMER, timerId = 4))!!
        val work = alarm()
        ring(work)
        assertEquals(work.id, store.current!!.alarm.id)

        // The Stop button of the timer's notification, tapped a moment too late.
        assertFalse(controller.dismiss(RingTarget(RingIds.TIMER_ALARM_BASE - 4, timerId = 4)))
        assertEquals("the alarm still rings, missions and all", work.id, store.current!!.alarm.id)
    }

    @Test fun `the silent sunrise glow before an alarm cannot be snoozed or dismissed by a stray key`() = runBlocking {
        val a = alarm { copy(sunrise = SunriseSettings(enabled = true, leadMinutes = 10)) }
        controller.begin(RingRequest(a.id, -1, clock.now + 600_000, RingKind.PRIMARY, phase = RingPhase.SUNRISE))!!
        assertEquals(SnoozeOutcome.NotRinging, controller.snooze())
        assertFalse(controller.dismiss())
        assertEquals(RingPhase.SUNRISE, store.current!!.phase)
        verifyNothingArmed()
    }

    private fun verifyNothingArmed() {
        io.mockk.verify(exactly = 0) { pending.setAlarmClock(TriggerKind.SNOOZE, any(), any(), any(), any(), any()) }
    }

    // ---- the ring timeout and the backup -----------------------------------------------------------------------

    @Test fun `a ring the backup has just taken over is not ended by the timeout that falls due with it`() = runBlocking {
        val a = alarm { copy(antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 10, backupDelayMinutes = 10)) }
        val first = ring(a)
        clock.now += 10 * 60_000L
        // The backup fires in the same second as the timeout and upgrades the live ring.
        val backup = controller.onBackupFired(a.id, first.eventId, 1)!!
        controller.begin(backup)
        controller.tick()
        val current = store.current
        assertNotNull("still ringing, now as the backup", current)
        assertEquals(RingKind.BACKUP, current!!.kind)
    }

    @Test fun `an ignored ring still times out when nothing took it over`() = runBlocking {
        val a = alarm { copy(antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 10, backupEnabled = false)) }
        val session = ring(a)
        clock.now += 10 * 60_000L
        controller.tick()
        assertNull(store.current)
        assertEquals(AlarmOutcome.MISSED, history.get(session.eventId)!!.outcome)
    }

    // ---- timers ------------------------------------------------------------------------------------------------

    @Test fun `a timer started again while it rang keeps counting down when the ring ends`() = runBlocking {
        timers.save(CountdownTimer(id = 7, durationMs = 300_000, state = TimerState.RINGING))
        controller.begin(RingRequest(RingIds.TIMER_ALARM_BASE - 7, -1, clock.now, RingKind.TIMER, timerId = 7))!!
        // Play on the Timers tab: running again, while the old ring is still on.
        timers.save(timers.getTimer(7)!!.copy(state = TimerState.RUNNING, endsAtMillis = clock.now + 300_000))
        assertTrue(controller.dismiss(RingTarget(RingIds.TIMER_ALARM_BASE - 7, timerId = 7)))
        assertEquals("not reset to idle under the running countdown", TimerState.RUNNING, timers.getTimer(7)!!.state)
    }

    @Test fun `a timer whose ring ends goes back to idle`() = runBlocking {
        timers.save(CountdownTimer(id = 8, durationMs = 300_000, state = TimerState.RINGING))
        controller.begin(RingRequest(RingIds.TIMER_ALARM_BASE - 8, -1, clock.now, RingKind.TIMER, timerId = 8))!!
        controller.dismiss()
        assertEquals(TimerState.IDLE, timers.getTimer(8)!!.state)
    }

    // ---- rings that Android hands back after a crash ---------------------------------------------------------------

    @Test fun `a redelivered ring rings only if it is still unhandled`() = runBlocking {
        val a = alarm()
        fun event(outcome: AlarmOutcome, snoozeUntil: Long = 0) = runBlocking {
            history.start(AlarmEvent(alarmId = a.id, label = "", scheduledAt = clock.now, firedAt = clock.now, outcome = outcome, snoozeUntil = snoozeUntil))
        }
        fun request(eventId: Long) = RingRequest(a.id, eventId, clock.now, RingKind.PRIMARY)

        assertFalse("dismissed before the crash", controller.stillUnhandled(request(event(AlarmOutcome.DISMISSED))))
        assertFalse("snoozed before the crash", controller.stillUnhandled(request(event(AlarmOutcome.RINGING, snoozeUntil = clock.now + 600_000))))
        assertTrue("ringing when the process died", controller.stillUnhandled(request(event(AlarmOutcome.RINGING))))
        assertTrue("no history to ask: ring rather than miss", controller.stillUnhandled(request(-1)))

        fun timer(state: TimerState): Boolean = runBlocking {
            timers.save(CountdownTimer(id = 9, durationMs = 60_000, state = state))
            controller.stillUnhandled(RingRequest(RingIds.TIMER_ALARM_BASE - 9, -1, clock.now, RingKind.TIMER, timerId = 9))
        }
        assertTrue("still ringing", timer(TimerState.RINGING))
        // What start-up housekeeping leaves of a timer that rang when the process died: it must ring again.
        assertTrue(timer(TimerState.IDLE))
        assertFalse("started again by the user: its own trigger rings it", timer(TimerState.RUNNING))
        assertFalse(timer(TimerState.PAUSED))
    }

    // ---- delete after use --------------------------------------------------------------------------------------

    @Test fun `a delete-after-use alarm with a still-awake check is kept, switched off, until the check is over`() = runBlocking {
        val a = alarm { copy(repeatDaysMask = 0, deleteAfterUse = true, wakeCheck = WakeCheckSettings(enabled = true)) }
        ring(a)
        controller.dismiss()
        val kept = alarms.getAlarm(a.id)
        assertNotNull("the check needs it to ask about and to ring again", kept)
        assertFalse(kept!!.enabled)
    }

    @Test fun `a delete-after-use alarm without a check is deleted when dismissed`() = runBlocking {
        val a = alarm { copy(repeatDaysMask = 0, deleteAfterUse = true) }
        ring(a)
        controller.dismiss()
        assertNull(alarms.getAlarm(a.id))
    }
}
