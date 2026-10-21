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
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import com.riyadm.wakenudge.engine.notify.Notifier
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

/**
 * The ring state machine against storage that fails: a full disk, an unreadable database, an OS that will not arm a
 * snooze. The rule under test: persistence never decides whether an alarm rings or stops.
 */
class RingControllerResilienceTest {
    private val clock = TestClock()
    private val alarms = MemoryAlarms()
    private val history = MemoryHistory()
    private val timers = MemoryTimers()
    private val settings = MemorySettings()
    private val store = RingSessionStore()
    private val runtime = realRuntime()
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
    }
    private val notifier = mockk<Notifier>(relaxed = true)
    private val log = SilentLog()

    private fun controller(surfaces: SurfaceRefresher = CountingSurfaces()) = RingController(
        store, alarms, history, timers, settings, RecordingScheduler(), mockk(relaxed = true), pending, runtime, clock, notifier, surfaces, log,
    )

    private val diskFull = SQLiteFullException("database or disk is full")

    private fun alarm(block: Alarm.() -> Alarm = { this }) = alarms.add(Alarm(label = "work", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK).block())

    // ---- firing ---------------------------------------------------------------------------------------------------

    @Test fun `a ring starts even if the history cannot be written, and the backup is still armed`() = runBlocking {
        val a = alarm()
        history.failure = diskFull
        val request = controller().onAlarmFired(a.id, clock.now)
        assertNotNull(request)
        assertEquals(-1L, request!!.eventId)
        verify { pending.setAlarmClock(TriggerKind.BACKUP, a.id, any(), 1, any(), any()) }
    }

    @Test fun `an alarm that cannot be read rings with safe defaults instead of being lost`() = runBlocking {
        val a = alarm()
        alarms.failReads = IOException("database unreadable")
        val c = controller()
        val request = c.onAlarmFired(a.id, clock.now)!!
        assertEquals(RingKind.PRIMARY, request.kind)
        val session = c.begin(request)!!
        assertEquals("Alarm", session.alarm.label)
        assertEquals(a.id, session.alarm.id)
        assertNotNull(store.current)
    }

    @Test fun `an alarm we armed that has vanished from the database rings, a really deleted one does not`() = runBlocking {
        val lost = alarm()
        val deleted = alarm()
        runtime.setRegisteredTriggers(mapOf(lost.id to clock.now)) // armed and never cancelled
        alarms.rows.remove(lost.id)
        alarms.rows.remove(deleted.id) // deleted properly: deletion also drops the bookkeeping, so it is not registered
        val c = controller()

        assertNotNull(c.onAlarmFired(lost.id, clock.now))
        assertFalse("forgotten once it has rung", lost.id in runtime.registeredTriggers())
        assertNull(c.onAlarmFired(deleted.id, clock.now))
    }

    @Test fun `a one-time alarm still rings when switching it off afterwards fails`() = runBlocking {
        val a = alarm { copy(repeatDaysMask = 0) }
        val flaky = object : com.riyadm.wakenudge.domain.repository.AlarmRepository by alarms {
            override suspend fun setEnabled(id: Long, enabled: Boolean) = throw diskFull
        }
        val c = RingController(store, flaky, history, timers, settings, RecordingScheduler(), mockk(relaxed = true), pending, runtime, clock, notifier, CountingSurfaces(), log)
        assertNotNull(c.onAlarmFired(a.id, clock.now))
    }

    // ---- starting the session --------------------------------------------------------------------------------------

    @Test fun `starting a session never throws and survives unreadable settings and alarms`() = runBlocking {
        val a = alarm()
        alarms.failReads = IOException("gone")
        settings.failure = IOException("gone")
        val session = controller().begin(RingRequest(a.id, -1, clock.now, RingKind.PRIMARY))
        assertNotNull(session)
        assertEquals(session, store.current)
    }

    @Test fun `a failure after the session was created keeps that session instead of replacing it`() = runBlocking {
        val a = alarm()
        val exploding = object : SurfaceRefresher {
            override fun refreshAll() = throw IllegalStateException("widgets exploded")
        }
        val c = controller(exploding)
        val session = c.begin(RingRequest(a.id, -1, clock.now, RingKind.PRIMARY))!!
        assertEquals(a.id, session.alarm.id)
        assertEquals(session, store.current)
    }

    @Test fun `a timer whose row cannot be read still rings as a plain timer`() = runBlocking {
        timers.failure = IOException("gone")
        val s = controller().begin(RingRequest(RingIds.TIMER_ALARM_BASE - 4, -1, clock.now, RingKind.TIMER, timerId = 4))
        assertNotNull(s)
        assertTrue(s!!.isTimer)
    }

    @Test fun `two recent crashes put the new session into safe mode and one does not`() = runBlocking {
        val a = alarm()
        runtime.recordRingCrash(clock.now - 1_000)
        assertFalse(controller().begin(RingRequest(a.id, -1, clock.now, RingKind.PRIMARY))!!.safeMode)
        store.set(null)
        runtime.recordRingCrash(clock.now - 500)
        assertTrue(controller().begin(RingRequest(a.id, -1, clock.now, RingKind.PRIMARY))!!.safeMode)
    }

    // ---- ending the ring -------------------------------------------------------------------------------------------

    @Test fun `dismissing ends the ring and cancels the backups even if the disk fills up meanwhile`() = runBlocking {
        val a = alarm()
        val c = controller()
        c.begin(c.onAlarmFired(a.id, clock.now)!!)
        history.failure = diskFull
        c.dismiss()
        assertNull(store.current)
        verify { pending.cancel(TriggerKind.BACKUP, a.id, 1) }
    }

    @Test fun `the ring timeout ends the ring and warns about the missed alarm even if the history cannot be written`() = runBlocking {
        val a = alarm { copy(antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 1, backupEnabled = false)) }
        val c = controller()
        c.begin(c.onAlarmFired(a.id, clock.now)!!)
        history.failure = diskFull
        clock.now += 2 * 60_000
        c.tick()
        assertNull("the ring ended by itself", store.current)
        verify { notifier.showMissed(any(), any()) }
    }

    @Test fun `a snooze that cannot be recorded is still armed and comes back`() = runBlocking {
        val a = alarm { copy(snooze = SnoozeSettings(minutes = 5, maxCount = 3)) }
        history.failure = diskFull
        val c = controller()
        c.begin(c.onAlarmFired(a.id, clock.now)!!)
        assertTrue(c.snooze() is SnoozeOutcome.Snoozed)
        assertNull(store.current)
        val back = c.onSnoozeExpired(a.id, -1)
        assertNotNull(back)
        assertEquals(RingKind.SNOOZE_RETURN, back!!.kind)
    }

    @Test fun `a snooze the OS will not arm leaves the alarm ringing`() = runBlocking {
        val a = alarm()
        val c = controller()
        c.begin(c.onAlarmFired(a.id, clock.now)!!)
        every { pending.setAlarmClock(TriggerKind.SNOOZE, any(), any(), any(), any(), any()) } returns false
        assertTrue(c.snooze() is SnoozeOutcome.NotAllowed)
        assertNotNull("a snooze that never returns would be the worst outcome", store.current)
    }

    @Test fun `stale wake-now and snooze expiries for handled alarms stay quiet`() = runBlocking {
        val a = alarm()
        val c = controller()
        assertNull("no open event, no wake-now", c.wakeNow(a.id))
        val id = history.start(AlarmEvent(alarmId = a.id, label = "x", scheduledAt = 1, firedAt = 1, outcome = AlarmOutcome.DISMISSED))
        assertNull(c.onSnoozeExpired(a.id, id))
    }

    // ---- backups and retries ---------------------------------------------------------------------------------------

    @Test fun `a backup rings when its event is unresolved or unknown to a failed history, not when it was handled`() = runBlocking {
        val a = alarm()
        val c = controller()
        val open = history.start(AlarmEvent(alarmId = a.id, label = "x", scheduledAt = 1, firedAt = 1))
        val done = history.start(AlarmEvent(alarmId = a.id, label = "x", scheduledAt = 2, firedAt = 2, outcome = AlarmOutcome.DISMISSED))
        assertNotNull(c.onBackupFired(a.id, open, 1))
        assertNull(c.onBackupFired(a.id, done, 1))
        assertNull("a positive id that points nowhere is a stale trigger", c.onBackupFired(a.id, 999, 1))
        assertNotNull("no event id at all: the history was down when the ring started", c.onBackupFired(a.id, -1, 1))
    }

    @Test fun `a retry rings only an alarm that is still unresolved and not already ringing`() = runBlocking {
        val a = alarm()
        val c = controller()
        val open = history.start(AlarmEvent(alarmId = a.id, label = "x", scheduledAt = 5, firedAt = 5))
        val request = c.onRetry(a.id, open, 5, RingKind.BACKUP, attempt = 0)!!
        assertEquals(RingKind.BACKUP, request.kind)
        assertEquals(1, request.attempt)

        history.update(history.get(open)!!.copy(snoozeUntil = clock.now + 60_000))
        assertNull("snoozed meanwhile", c.onRetry(a.id, open, 5, RingKind.PRIMARY, 0))
        history.update(history.get(open)!!.copy(snoozeUntil = 0, outcome = AlarmOutcome.DISMISSED))
        assertNull("dismissed meanwhile", c.onRetry(a.id, open, 5, RingKind.PRIMARY, 0))

        history.update(history.get(open)!!.copy(outcome = AlarmOutcome.RINGING))
        c.begin(request)
        assertNull("something is ringing already", c.onRetry(a.id, open, 5, RingKind.PRIMARY, 0))
    }

    @Test fun `a retry rings even when the history cannot be read`() = runBlocking {
        val a = alarm()
        history.failure = diskFull
        assertNotNull(controller().onRetry(a.id, 3, 5, RingKind.PRIMARY, 0))
    }

    // ---- housekeeping ----------------------------------------------------------------------------------------------

    @Test fun `housekeeping after a crash cannot throw even when the database is down`() = runBlocking {
        history.failure = diskFull
        timers.failure = diskFull
        timers.rows[1] = CountdownTimer(id = 1, durationMs = 1000, state = TimerState.RINGING)
        controller().recoverStaleState()
        assertTrue(true)
    }
}
