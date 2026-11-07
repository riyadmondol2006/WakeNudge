package com.riyadm.wakenudge.engine

import android.content.Context
import android.os.PowerManager
import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.data.backup.RecordingScheduler
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmDefaults
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.engine.notify.Notifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Never" as the ring timeout (stored as 0): an ignored alarm keeps ringing until it is dealt with, whatever else
 * happens around it, while timers and the test alarm keep their own fixed timeouts.
 */
class RingUntilHandledTest {
    private val clock = TestClock()
    private val alarms = MemoryAlarms()
    private val history = MemoryHistory()
    private val timers = MemoryTimers()
    private val store = RingSessionStore()
    private val runtime = realRuntime()
    private val notifier = mockk<Notifier>(relaxed = true)
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
    }
    private val settings = MemorySettings(
        // The owner may make Never the default for new alarms: timers and the test alarm must not inherit it.
        AppSettings(defaults = AlarmDefaults(antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = NEVER))),
    )
    private val controller = RingController(
        store, alarms, history, timers, settings, RecordingScheduler(), mockk(relaxed = true), pending, runtime, clock,
        notifier, CountingSurfaces(), SilentLog(),
    )

    private fun alarm(anti: AntiOversleepSettings) =
        alarms.add(Alarm(label = "must get up", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK, antiOversleep = anti))

    private suspend fun ring(a: Alarm): RingSession = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!

    /** The service's one-second tick, sped up: a tick every [stepMs] for [totalMs]. */
    private suspend fun ignoreFor(totalMs: Long, stepMs: Long = 30_000L) {
        var left = totalMs
        while (left > 0) {
            clock.now += stepMs
            left -= stepMs
            controller.tick()
        }
    }

    @Test fun `new alarms still default to ten minutes`() {
        assertEquals(10, AntiOversleepSettings().ringTimeoutMinutes)
    }

    @Test fun `an alarm set to Never keeps ringing however long it is ignored`() = runBlocking {
        val session = ring(alarm(AntiOversleepSettings(ringTimeoutMinutes = NEVER, backupEnabled = false)))
        ignoreFor(HOUR * 26)
        assertNotNull("still ringing after a day", store.current)
        assertEquals(AlarmOutcome.RINGING, history.get(session.eventId)!!.outcome)
        assertNull("the event stays open", history.get(session.eventId)!!.endedAt)
        verify(exactly = 0) { notifier.showMissed(any(), any()) }
    }

    @Test fun `a Never alarm still escalates when ignored`() = runBlocking {
        ring(alarm(AntiOversleepSettings(ringTimeoutMinutes = NEVER, backupEnabled = false)))
        ignoreFor(RingIds.INACTIVITY_ESCALATION_MS + 30_000L)
        assertTrue(store.current!!.intensified)
    }

    @Test fun `a backup firing during an endless ring upgrades it and the ring goes on`() = runBlocking {
        val a = alarm(AntiOversleepSettings(ringTimeoutMinutes = NEVER, backupDelayMinutes = 10, backupCount = 3))
        val first = ring(a)
        assertEquals(3, history.get(first.eventId)!!.pendingBackups.size)

        for (index in 1..3) {
            ignoreFor(10 * MINUTE)
            controller.begin(controller.onBackupFired(a.id, first.eventId, index)!!)
            val upgraded = store.current
            assertNotNull("backup $index must not end the ring", upgraded)
            assertEquals(RingKind.BACKUP, upgraded!!.kind)
            assertEquals("the same event, not a new ring", first.eventId, upgraded.eventId)
            assertEquals(first.startedAt, upgraded.startedAt)
            assertTrue(upgraded.intensified)
        }
        assertTrue("every backup has been used", history.get(first.eventId)!!.pendingBackups.isEmpty())

        ignoreFor(HOUR * 3)
        assertNotNull("no backups left is no reason to stop", store.current)
        assertEquals(AlarmOutcome.RINGING, history.get(first.eventId)!!.outcome)
        verify(exactly = 0) { notifier.showMissed(any(), any()) }
    }

    @Test fun `an endless ring can still be dismissed and records how it ended`() = runBlocking {
        val session = ring(alarm(AntiOversleepSettings(ringTimeoutMinutes = NEVER, backupEnabled = false)))
        ignoreFor(HOUR * 3)
        assertTrue(controller.dismiss())
        assertNull(store.current)
        val event = history.get(session.eventId)!!
        assertEquals(AlarmOutcome.DISMISSED, event.outcome)
        assertEquals(clock.now, event.endedAt)
    }

    @Test fun `an endless ring can still be snoozed, and its snooze comes back endless`() = runBlocking {
        val a = alarm(AntiOversleepSettings(ringTimeoutMinutes = NEVER, backupEnabled = false))
        val session = ring(a)
        ignoreFor(HOUR)
        assertTrue(controller.snooze() is SnoozeOutcome.Snoozed)
        clock.now += a.snooze.minutes * MINUTE
        controller.begin(controller.onSnoozeExpired(a.id, session.eventId)!!)
        ignoreFor(HOUR * 2)
        assertNotNull(store.current)
    }

    @Test fun `tomorrow's occurrence arriving during an endless ring merges into it`() = runBlocking {
        val a = alarm(AntiOversleepSettings(ringTimeoutMinutes = NEVER, backupEnabled = false))
        val today = ring(a)
        ignoreFor(HOUR * 24)
        controller.begin(controller.onAlarmFired(a.id, clock.now)!!)
        val current = store.current!!
        assertTrue(current.eventId != today.eventId)
        assertEquals("the old day is closed, not left open", AlarmOutcome.MISSED, history.get(today.eventId)!!.outcome)
        assertEquals(AlarmOutcome.RINGING, history.get(current.eventId)!!.outcome)
        ignoreFor(HOUR)
        assertNotNull(store.current)
    }

    @Test fun `housekeeping leaves the event of a live endless ring open`() = runBlocking {
        val session = ring(alarm(AntiOversleepSettings(ringTimeoutMinutes = NEVER, backupEnabled = false)))
        ignoreFor(HOUR * 7) // the watchdog runs this housekeeping every six hours
        controller.recoverStaleState()
        assertEquals(AlarmOutcome.RINGING, history.get(session.eventId)!!.outcome)
    }

    @Test fun `a process death after hours of ringing brings the ring back, and dismissing it is still recorded`() = runBlocking {
        val a = alarm(AntiOversleepSettings(ringTimeoutMinutes = NEVER, backupEnabled = false))
        val session = ring(a)
        ignoreFor(HOUR * 2)
        // The OS kills the process: a new process has no session, and its housekeeping closes the old event as missed.
        store.set(null)
        val fresh = RingController(
            store, alarms, history, timers, settings, RecordingScheduler(), mockk(relaxed = true), pending, runtime, clock,
            notifier, CountingSurfaces(), SilentLog(),
        )
        fresh.recoverStaleState()

        // Android redelivers the start with START_REDELIVER_INTENT.
        val redelivered = session.toRequest()
        assertTrue(fresh.stillUnhandled(redelivered))
        fresh.begin(redelivered)
        clock.now += HOUR * 2
        fresh.tick()
        assertNotNull("the restarted ring is endless too", store.current)
        assertTrue(fresh.dismiss())
        assertEquals(AlarmOutcome.DISMISSED, history.get(session.eventId)!!.outcome)
    }

    @Test fun `a nonsense negative timeout in a stored alarm is one minute and not Never`() = runBlocking {
        ring(alarm(AntiOversleepSettings(ringTimeoutMinutes = -3, backupEnabled = false)))
        ignoreFor(MINUTE)
        assertNull(store.current)
    }

    @Test fun `ordinary timeouts are unchanged`() = runBlocking {
        val session = ring(alarm(AntiOversleepSettings(ringTimeoutMinutes = 1, backupEnabled = false)))
        clock.now += MINUTE - 1
        controller.tick()
        assertNotNull(store.current)
        clock.now += 1
        controller.tick()
        assertNull(store.current)
        assertEquals(AlarmOutcome.MISSED, history.get(session.eventId)!!.outcome)
        verify { notifier.showMissed(any(), any()) }
    }

    // ---- timers and the test alarm --------------------------------------------------------------------------------

    @Test fun `a ringing timer still times out after three minutes when new alarms default to Never`() = runBlocking {
        timers.save(CountdownTimer(id = 5, durationMs = 60_000, state = TimerState.RINGING))
        controller.begin(RingRequest(RingIds.TIMER_ALARM_BASE - 5, -1, clock.now, RingKind.TIMER, timerId = 5))!!
        assertEquals(3, store.current!!.alarm.antiOversleep.ringTimeoutMinutes)
        clock.now += 3 * MINUTE - 1
        controller.tick()
        assertNotNull(store.current)
        clock.now += 1
        controller.tick()
        assertNull(store.current)
        assertEquals(TimerState.IDLE, timers.getTimer(5)!!.state)
    }

    @Test fun `the test alarm still times out after two minutes when new alarms default to Never`() = runBlocking {
        val scheduler = AlarmSchedulerImpl(
            alarms, timers, mockk(relaxed = true) { coEvery { getAll() } returns emptyList() }, history, settings, NextTriggerCalculator(),
            SchedulingContextProvider(
                settings,
                mockk<GroupRepository>().also { coEvery { it.getGroups() } returns emptyList() },
                mockk<ExceptionRepository>().also { coEvery { it.getDates() } returns emptyList() },
                mockk(relaxed = true), clock,
            ),
            pending, clock, runtime, CountingSurfaces(), SilentLog(), mockk(relaxed = true), store, notifier,
        )
        scheduler.scheduleTestAlarm(5)
        controller.begin(controller.onAlarmFired(RingIds.TEST_ALARM_ID, clock.now)!!)
        assertEquals(RingKind.TEST, store.current!!.kind)
        assertEquals(2, store.current!!.alarm.antiOversleep.ringTimeoutMinutes)
        clock.now += 2 * MINUTE
        controller.tick()
        assertNull(store.current)
    }

    // ---- the wake lock --------------------------------------------------------------------------------------------

    @Test fun `the ring wake lock never lapses during a ring far longer than its own timeout`() {
        var tick = 0
        val acquiredAtTick = mutableListOf<Int>()
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        every { lock.acquire(any()) } answers { acquiredAtTick += tick }
        val power = mockk<PowerManager>()
        every { power.newWakeLock(any(), any()) } returns lock
        val context = mockk<Context>()
        every { context.getSystemService(PowerManager::class.java) } returns power

        val locks = RingWakeLocks(context)
        locks.acquireRing()
        val eightHours = 8 * 3_600
        while (tick < eightHours) {
            tick++
            locks.onRingTick()
        }
        verify { lock.acquire(RingWakeLocks.RING_MAX_MS) }
        val gaps = (acquiredAtTick + eightHours).zipWithNext { a, b -> b - a }
        val longestGapMs = gaps.max() * 1_000L
        assertFalse("the lock would have lapsed: ${longestGapMs / 60_000} min without a renewal", longestGapMs >= RingWakeLocks.RING_MAX_MS)
    }

    private companion object {
        const val NEVER = 0
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
