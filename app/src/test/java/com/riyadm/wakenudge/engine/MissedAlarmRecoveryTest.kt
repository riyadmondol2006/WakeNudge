package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.data.backup.RecordingScheduler
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.engine.notify.Notifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An alarm that fell due while the phone was off rings if the phone is back within the alarm's window, and is reported as missed if not. */
class MissedAlarmRecoveryTest {
    private val clock = TestClock()
    private val alarms = MemoryAlarms()
    private val history = MemoryHistory()
    private val notifier = mockk<Notifier>(relaxed = true)
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
    }
    private val scheduler = RecordingScheduler()
    private val log = SilentLog()
    private val runtime = realRuntime()
    private val started = mutableListOf<RingRequest>()
    private val controller = RingController(
        RingSessionStore(), alarms, history, MemoryTimers(), MemorySettings(), scheduler, mockk(relaxed = true), pending, runtime, clock, notifier, CountingSurfaces(), log,
    )
    private val recovery = MissedAlarmRecovery(alarms, history, controller, { started += it }, scheduler, notifier, clock, log)

    private fun alarm(window: Int) = alarms.add(
        Alarm(label = "work", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK, antiOversleep = AntiOversleepSettings(resumeWindowMinutes = window)),
    )

    private fun lateBy(window: Int, minutes: Long, rings: Boolean) = runBlocking {
        val a = alarm(window)
        val at = clock.now - minutes * 60_000L
        recovery.process(mapOf(a.id to at), dueBy = clock.now)
        assertEquals("$minutes min late on a $window min window", rings, started.any { it.alarmId == a.id })
        val event = history.rows.values.single { it.alarmId == a.id }
        if (rings) {
            assertEquals(AlarmOutcome.RINGING, event.outcome)
            verify(exactly = 0) { notifier.showMissed(any(), any()) }
        } else {
            assertEquals(AlarmOutcome.MISSED, event.outcome)
            verify { notifier.showMissed(any(), at) }
        }
    }

    @Test fun `Easy rings within 10 minutes`() = lateBy(10, 10, true)

    @Test fun `Easy past 10 minutes is missed`() = lateBy(10, 11, false)

    @Test fun `Normal rings within 2 hours`() = lateBy(120, 120, true)

    @Test fun `Normal past 2 hours is missed`() = lateBy(120, 121, false)

    @Test fun `Hard and No mercy ring within 12 hours`() = lateBy(720, 720, true)

    @Test fun `Hard and No mercy past 12 hours are missed`() = lateBy(720, 721, false)

    @Test fun `a window shorter than 10 minutes keeps the 10 minutes every alarm has always had`() = lateBy(0, 10, true)

    @Test fun `an occurrence that already rang is not repeated`() = runBlocking {
        val a = alarm(720)
        val at = clock.now - 60 * 60_000L
        history.start(AlarmEvent(alarmId = a.id, label = "work", scheduledAt = at, firedAt = at, outcome = AlarmOutcome.DISMISSED))
        recovery.process(mapOf(a.id to at), dueBy = clock.now)
        assertTrue(started.isEmpty())
    }

    @Test fun `a one-time alarm past its window is switched off, not left for tomorrow`() = runBlocking {
        val a = alarms.add(Alarm(label = "once", hour = 7, minute = 0, antiOversleep = AntiOversleepSettings(resumeWindowMinutes = 120)))
        recovery.process(mapOf(a.id to clock.now - 121 * 60_000L), dueBy = clock.now)
        assertFalse(alarms.rows.getValue(a.id).enabled)
    }

    @Test fun `an unreadable database still rings an overdue alarm, within the window its saved copy has`() = runBlocking {
        val a = alarm(720).copy(missions = listOf(com.riyadm.wakenudge.domain.model.MissionConfig(com.riyadm.wakenudge.domain.model.MissionType.MATH)))
        AlarmSnapshots(runtime, log).update(a)
        alarms.failReads = IOException("database unreadable")
        history.failure = IOException("database unreadable")
        recovery.process(mapOf(a.id to clock.now - 300 * 60_000L), dueBy = clock.now)
        assertEquals(1, started.count { it.alarmId == a.id })
    }

    @Test fun `an unreadable database with no saved copy rings within the plain 10 minutes and not after`() = runBlocking {
        alarms.failReads = IOException("database unreadable")
        history.failure = IOException("database unreadable")
        recovery.process(mapOf(41L to clock.now - 9 * 60_000L, 42L to clock.now - 11 * 60_000L), dueBy = clock.now)
        assertEquals(listOf(41L), started.map { it.alarmId })
    }

    @Test fun `an unreadable history alone still rings an overdue alarm`() = runBlocking {
        val a = alarm(120)
        history.failure = IOException("database unreadable")
        recovery.process(mapOf(a.id to clock.now - 60 * 60_000L), dueBy = clock.now)
        assertEquals(1, started.count { it.alarmId == a.id })
    }

    @Test fun `past the window with an unwritable history the user is still told`() = runBlocking {
        val a = alarm(10)
        history.failure = IOException("disk full")
        val at = clock.now - 60 * 60_000L
        recovery.process(mapOf(a.id to at), dueBy = clock.now)
        assertTrue(started.isEmpty())
        verify { notifier.showMissed(any(), at) }
    }
}
