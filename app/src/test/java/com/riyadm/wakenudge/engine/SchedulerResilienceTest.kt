package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.engine.notify.Notifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arming the alarms is the one job that must succeed; everything around it is allowed to fail. */
class SchedulerResilienceTest {
    private val clock = TestClock()
    private val alarms = MemoryAlarms()
    private val history = MemoryHistory()
    private val timers = MemoryTimers()
    private val settings = MemorySettings()
    private val runtime = realRuntime()
    private val groups = mockk<GroupRepository>().also { coEvery { it.getGroups() } returns emptyList() }
    private val exceptions = mockk<ExceptionRepository>().also { coEvery { it.getDates() } returns emptyList() }
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
        every { it.setExact(any(), any(), any(), any(), any(), any()) } returns true
        every { it.setInexact(any(), any(), any()) } returns true
        every { it.fallbackCount } returns 0
        every { it.lastFailure } returns "SecurityException: refused"
    }
    private val notifier = mockk<Notifier>(relaxed = true)

    private fun scheduler(contextSettings: com.riyadm.wakenudge.domain.repository.SettingsRepository = settings) = AlarmSchedulerImpl(
        alarms, timers, mockk(relaxed = true) { coEvery { getAll() } returns emptyList() }, history, settings, NextTriggerCalculator(),
        SchedulingContextProvider(contextSettings, groups, exceptions, mockk(relaxed = true), clock),
        pending, clock, runtime, CountingSurfaces(), SilentLog(), mockk(relaxed = true), RingSessionStore(), notifier,
    )

    private fun alarm(hour: Int = 7) = alarms.add(Alarm(label = "a", hour = hour, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK))

    @Test fun `alarms are armed even when settings can be neither read nor written`() = runBlocking {
        val a = alarm()
        settings.failure = IOException("settings unreadable")
        scheduler().rescheduleAll("test")
        verify { pending.setAlarmClock(TriggerKind.ALARM, a.id, any(), any(), any(), any()) }
        assertTrue(a.id in runtime.registeredTriggers())
        verify { pending.setInexact(TriggerKind.WATCHDOG, 0, any()) }
    }

    @Test fun `an unreadable vacation setting cannot keep an alarm from being armed`() = runBlocking {
        val a = alarm()
        settings.update { it.copy(vacation = VacationMode(enabled = true, resumeEpochDay = 1)) } // long expired
        settings.failure = IOException("disk full") // so clearing the stale flag fails
        scheduler().rescheduleAll("test")
        verify { pending.setAlarmClock(TriggerKind.ALARM, a.id, any(), any(), any(), any()) }
    }

    @Test fun `unreadable groups and exceptions still arm the alarm, as if nothing were paused`() = runBlocking {
        val a = alarm()
        coEvery { groups.getGroups() } throws IOException("database")
        scheduler().rescheduleAll("test")
        verify { pending.setAlarmClock(TriggerKind.ALARM, a.id, any(), any(), any(), any()) }
    }

    @Test fun `follow-up restoration failing does not skip the watchdog or the bookkeeping`() = runBlocking {
        val a = alarm()
        history.failure = IOException("history unreadable")
        scheduler().rescheduleAll("test")
        verify { pending.setInexact(TriggerKind.WATCHDOG, 0, any()) }
        assertEquals(clock.now, runtime.lastRescheduleAt)
        assertTrue(a.id in runtime.registeredTriggers())
    }

    @Test fun `a corrupt row is skipped and the others are armed`() = runBlocking {
        val good = alarm(6)
        val bad = alarm(8)
        val calculator = mockk<NextTriggerCalculator>()
        every { calculator.next(match { it.id == bad.id }, any(), any()) } throws IllegalStateException("corrupt row")
        every { calculator.next(match { it.id == good.id }, any(), any()) } returns clock.now().plusHours(3)
        val s = AlarmSchedulerImpl(
            alarms, timers, mockk(relaxed = true) { coEvery { getAll() } returns emptyList() }, history, settings, calculator,
            SchedulingContextProvider(settings, groups, exceptions, mockk(relaxed = true), clock),
            pending, clock, runtime, CountingSurfaces(), SilentLog(), mockk(relaxed = true), RingSessionStore(), notifier,
        )
        s.rescheduleAll("test")
        verify { pending.setAlarmClock(TriggerKind.ALARM, good.id, any(), any(), any(), any()) }
        verify(exactly = 0) { pending.setAlarmClock(TriggerKind.ALARM, bad.id, any(), any(), any(), any()) }
    }

    // ---- telling the user ------------------------------------------------------------------------------------------

    @Test fun `when the OS refuses an alarm the user is told, nothing is claimed as armed, and the next good pass clears it`() = runBlocking {
        val a = alarm()
        every { pending.setAlarmClock(TriggerKind.ALARM, a.id, any(), any(), any(), any()) } returns false
        val s = scheduler()
        s.rescheduleAll("test")
        assertFalse(a.id in runtime.registeredTriggers())
        assertTrue(runtime.lastSchedulingProblem.contains("could not be armed"))
        verify { notifier.showSchedulingProblem(1) }

        every { pending.setAlarmClock(TriggerKind.ALARM, a.id, any(), any(), any(), any()) } returns true
        s.rescheduleAll("test")
        assertEquals("", runtime.lastSchedulingProblem)
        verify { notifier.cancelSchedulingProblem() }
    }

    @Test fun `alarms beyond the OS limit are counted as unarmed and the user is told how many`() = runBlocking {
        repeat(450) { alarm(it % 24) }
        scheduler().rescheduleAll("test")
        verify { notifier.showSchedulingProblem(10) }
    }

    @Test fun `an alarm armed the second-best way is reported without raising the alarm`() = runBlocking {
        alarm()
        every { pending.fallbackCount } returnsMany listOf(0, 1)
        every { pending.lastFallback } returns "123|exact wake-up used because the OS refused the alarm clock"
        scheduler().rescheduleAll("test")
        assertTrue(runtime.lastSchedulingProblem.contains("exact wake-up used"))
        verify(exactly = 0) { notifier.showSchedulingProblem(any()) }
    }

    @Test fun `saving a single alarm that the OS refuses also tells the user`() = runBlocking {
        val a = alarm()
        every { pending.setAlarmClock(TriggerKind.ALARM, a.id, any(), any(), any(), any()) } returns false
        scheduler().schedule(a.id)
        verify { notifier.showSchedulingProblem(1) }
        assertFalse(a.id in runtime.registeredTriggers())
    }

    @Test fun `history rows with pending backups are re-armed after the OS forgot them`() = runBlocking {
        val a = alarm()
        val soon = clock.now + 5 * 60_000
        history.start(AlarmEvent(alarmId = a.id, label = "x", scheduledAt = 1, firedAt = 1, pendingBackups = listOf(soon), snoozeUntil = soon))
        scheduler().rescheduleAll("test")
        verify { pending.setAlarmClock(TriggerKind.BACKUP, a.id, soon, 1, any(), any()) }
        verify { pending.setAlarmClock(TriggerKind.SNOOZE, a.id, soon, 0, any(), any()) }
    }
}
