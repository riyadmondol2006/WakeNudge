package com.riyadm.wakenudge.engine

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
import com.riyadm.wakenudge.domain.repository.WakeCheckRepository
import com.riyadm.wakenudge.engine.notify.Notifier
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two things arriving in the same instant, held open at the storage read between them so the order that used to go
 * wrong is the order that runs: a dismissal against a backup's start, an answer against its deadline, a timer's
 * last-second extension against its expiry.
 */
class RaceAuditFixesTest {
    private val clock = TestClock()
    private val alarms = MemoryAlarms()
    private val history = MemoryHistory()
    private val timers = MemoryTimers()
    private val store = RingSessionStore()
    private val runtime = realRuntime()
    private val notifier = mockk<Notifier>(relaxed = true)
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setExact(any(), any(), any(), any(), any(), any()) } returns true
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
    }
    private val ring = RingController(
        store, alarms, history, timers, MemorySettings(), RecordingScheduler(), mockk(relaxed = true), pending, runtime, clock, notifier, CountingSurfaces(), SilentLog(),
    )

    private val checks = object : WakeCheckRepository {
        val rows = HashMap<Long, WakeCheck>()
        override suspend fun getAll(): List<WakeCheck> = rows.values.toList()
        override fun observeAll(): Flow<List<WakeCheck>> = flowOf(rows.values.toList())
        override suspend fun get(alarmId: Long): WakeCheck? = rows[alarmId]
        override suspend fun save(check: WakeCheck) { rows[check.alarmId] = check }
        override suspend fun delete(alarmId: Long) { rows.remove(alarmId) }
        override suspend fun clear() = rows.clear()
    }
    private val wakeChecks = WakeCheckController(
        checks, alarms, history, pending, notifier, clock, SilentLog(), runtime, StepTotal { null }, CoroutineScope(Dispatchers.Unconfined),
    )

    /** Holds the next read open: completes [reached] when it starts, and lets it finish when [release] completes. */
    private class Gate {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun hold() { reached.complete(Unit); release.await() }
    }

    // ---- a dismissal against a backup's start ----------------------------------------------------------------------

    @Test fun `a dismissal that lands while a backup's start is being checked is not undone by the ring starting again`() = runBlocking {
        val a = alarms.add(Alarm(label = "work", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK, antiOversleep = AntiOversleepSettings(backupEnabled = false)))
        val s = ring.begin(ring.onAlarmFired(a.id, clock.now)!!)!!
        val backup = RingRequest(a.id, s.eventId, s.scheduledAt, RingKind.BACKUP)
        val gate = Gate()
        history.beforeGet = { history.beforeGet = null; gate.hold() }

        val starting = async { ring.begin(backup, onlyIfUnhandled = true) }
        gate.reached.await() // the start is reading the history, which still says "ringing"
        val dismissing = async { ring.dismiss() }
        yield() // the dismissal now waits its turn
        gate.release.complete(Unit)
        starting.await()

        assertTrue("the dismissal went through", dismissing.await())
        assertNull("and nothing rings after it", store.current)
        assertEquals(AlarmOutcome.DISMISSED, history.rows.getValue(s.eventId).outcome)
    }

    @Test fun `a start that is checked first finds a ring dismissed in the meantime and stays quiet`() = runBlocking {
        val a = alarms.add(Alarm(label = "work", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK, antiOversleep = AntiOversleepSettings(backupEnabled = false)))
        val s = ring.begin(ring.onAlarmFired(a.id, clock.now)!!)!!
        assertTrue(ring.dismiss())
        assertNull(ring.begin(RingRequest(a.id, s.eventId, s.scheduledAt, RingKind.BACKUP), onlyIfUnhandled = true))
        assertNull(store.current)
    }

    // ---- an answer against its deadline --------------------------------------------------------------------------------

    private suspend fun promptedCheck(): Pair<Alarm, Long> {
        val a = alarms.add(Alarm(label = "a", wakeCheck = WakeCheckSettings(enabled = true, delayMinutes = 5, maxChecks = 1, maxReRings = 3)))
        val event = history.start(AlarmEvent(alarmId = a.id, label = "a", scheduledAt = clock.now, firedAt = clock.now, outcome = AlarmOutcome.DISMISSED))
        wakeChecks.startAfterDismissal(a, event, 0)
        wakeChecks.onPrompt(a.id)
        assertEquals(WakeCheck.Phase.PROMPTED, checks.rows.getValue(a.id).phase)
        return a to event
    }

    @Test fun `an answer given as the deadline fires either passes the check or loses to it, never both`() = runBlocking {
        val (a, event) = promptedCheck()
        val gate = Gate()
        alarms.beforeGetAlarm = { alarms.beforeGetAlarm = null; gate.hold() }

        val deadline = async { wakeChecks.onDeadline(a.id) }
        gate.reached.await() // the deadline has read the check and is loading the alarm
        val answer = async { wakeChecks.answer(a.id) }
        yield()
        gate.release.complete(Unit)
        val request = deadline.await()
        val answered = answer.await()

        assertTrue("exactly one of them wins (ring again: ${request != null}, answered: $answered)", (request != null) != answered)
        if (answered) {
            assertNull(checks.rows[a.id])
            assertEquals(WakeCheckStatus.PASSED, history.rows.getValue(event).wakeCheck)
            assertEquals(AlarmOutcome.DISMISSED, history.rows.getValue(event).outcome)
        } else {
            assertNotNull("the check is still there for the ring that follows", checks.rows[a.id])
            assertEquals(AlarmOutcome.RINGING, history.rows.getValue(event).outcome)
        }
    }

    @Test fun `a deadline delivered twice rings the alarm again only once`() = runBlocking {
        val (a, _) = promptedCheck()
        assertNotNull(wakeChecks.onDeadline(a.id))
        assertEquals(1, checks.rows.getValue(a.id).reRings)
        assertNull("the same deadline again changes nothing", wakeChecks.onDeadline(a.id))
        assertEquals(1, checks.rows.getValue(a.id).reRings)
    }

    @Test fun `an answer that comes after the deadline does not pass a check whose alarm is already ringing again`() = runBlocking {
        val (a, event) = promptedCheck()
        assertNotNull(wakeChecks.onDeadline(a.id))
        assertFalse(wakeChecks.answer(a.id))
        assertNotNull(checks.rows[a.id])
        assertEquals(AlarmOutcome.RINGING, history.rows.getValue(event).outcome)
    }

    // ---- a timer extended in its last second ---------------------------------------------------------------------------

    @Test fun `an expiry that arrives after the timer was given another minute does not ring it early`() = runBlocking {
        val endsAt = clock.now + 60_000 // "+1 min" was saved a moment before the old expiry was handled
        timers.rows[4] = CountdownTimer(id = 4, durationMs = 120_000, state = TimerState.RUNNING, endsAtMillis = endsAt, remainingMs = 60_000)
        assertNull(ring.onTimerFired(4))
        assertEquals("still running towards its new end", TimerState.RUNNING, timers.rows.getValue(4).state)

        clock.now = endsAt
        assertNotNull("and it rings when that comes", ring.onTimerFired(4))
        assertEquals(TimerState.RINGING, timers.rows.getValue(4).state)
    }

    @Test fun `a timer whose row cannot be read still rings`() = runBlocking {
        timers.failure = java.io.IOException("database unreadable")
        assertNotNull(ring.onTimerFired(4))
    }
}
