package com.riyadm.wakenudge.engine

import android.content.Intent
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.engine.audio.AlarmAudioEngine
import com.riyadm.wakenudge.engine.notify.Notifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A trigger that fires is a real alarm: if the engine breaks while deciding, it still rings with what the trigger carries. */
class TriggerHandlerResilienceTest {
    private val ring = mockk<RingController>(relaxed = true)
    private val started = mutableListOf<RingRequest>()
    private val clock = TestClock()
    private val handler = TriggerHandler(
        ring, mockk(relaxed = true), RingStarter { started += it }, mockk<AlarmScheduler>(relaxed = true), mockk<Notifier>(relaxed = true),
        MemorySettings(), realRuntime(), clock, SilentLog(),
    )

    private fun intent(kind: TriggerKind, id: Long, at: Long = clock.now, eventId: Long = -1, index: Int = 0): Intent = mockk {
        every { action } returns Triggers.action(kind)
        every { getLongExtra(Triggers.EXTRA_ID, any()) } returns id
        every { getLongExtra(Triggers.EXTRA_AT, any()) } returns at
        every { getLongExtra(Triggers.EXTRA_EVENT_ID, any()) } returns eventId
        every { getIntExtra(Triggers.EXTRA_INDEX, any()) } returns index
    }

    @Test fun `when the engine throws while deciding, each ringing trigger still rings with its own kind`() = runBlocking {
        val boom = IOException("database unreadable")
        coEvery { ring.onAlarmFired(any(), any()) } throws boom
        coEvery { ring.onSnoozeExpired(any(), any()) } throws boom
        coEvery { ring.onBackupFired(any(), any(), any()) } throws boom
        coEvery { ring.onWakeCheckDeadline(any()) } throws boom
        for (kind in listOf(TriggerKind.ALARM, TriggerKind.SNOOZE, TriggerKind.BACKUP, TriggerKind.WAKE_CHECK_DEADLINE)) {
            handler.handle(intent(kind, id = 9, at = 1234, eventId = 5))
        }
        assertEquals(
            listOf(RingKind.PRIMARY, RingKind.SNOOZE_RETURN, RingKind.BACKUP, RingKind.WAKE_CHECK_RERING),
            started.map { it.kind },
        )
        assertTrue(started.all { it.alarmId == 9L && it.eventId == 5L && it.scheduledAt == 1234L })
    }

    @Test fun `the test alarm falls back to a test ring and not to a real one`() = runBlocking {
        coEvery { ring.onAlarmFired(any(), any()) } throws IOException("x")
        handler.handle(intent(TriggerKind.ALARM, RingIds.TEST_ALARM_ID))
        assertEquals(RingKind.TEST, started.single().kind)
    }

    @Test fun `when the engine decides not to ring nothing rings`() = runBlocking {
        coEvery { ring.onAlarmFired(any(), any()) } returns null
        coEvery { ring.onSnoozeExpired(any(), any()) } returns null
        handler.handle(intent(TriggerKind.ALARM, 1))
        handler.handle(intent(TriggerKind.SNOOZE, 1))
        assertTrue(started.isEmpty())
    }

    @Test fun `a rescue trigger carries the ring kind and the attempt number to the engine`() = runBlocking {
        val request = RingRequest(3, 8, 77, RingKind.BACKUP, attempt = 2)
        coEvery { ring.onRetry(3, 8, 77, RingKind.BACKUP, 1) } returns request
        handler.handle(intent(TriggerKind.RING_RETRY, id = 3, at = 77, eventId = 8, index = RingRetry.encode(RingKind.BACKUP, 1)))
        assertEquals(listOf(request), started)
    }

    @Test fun `cancellation is not swallowed by the safety net`() {
        coEvery { ring.onAlarmFired(any(), any()) } throws kotlinx.coroutines.CancellationException("job cancelled")
        var cancelled = false
        try {
            runBlocking { handler.handle(intent(TriggerKind.ALARM, 1)) }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue(started.isEmpty())
    }
}

class SystemEventHandlerTest {
    private val notifier = mockk<Notifier>(relaxed = true)
    private val audio = mockk<AlarmAudioEngine>(relaxed = true)
    private val scheduler = mockk<AlarmScheduler>(relaxed = true)
    private val ring = mockk<RingController>(relaxed = true)
    private val recovery = mockk<MissedAlarmRecovery>(relaxed = true)
    private val runtime = realRuntime()
    private val handler = SystemEventHandler(notifier, audio, scheduler, ring, recovery, runtime, TestClock(), SilentLog())

    @Test fun `a clock change reschedules and then rings or records alarms it made overdue, using the pre-change schedule`() = runBlocking {
        runtime.setRegisteredTriggers(mapOf(4L to 1_000L))
        coEvery { scheduler.rescheduleAll(any()) } coAnswers { runtime.setRegisteredTriggers(mapOf(4L to 99_000L)) }
        handler.handle(Intent.ACTION_TIME_CHANGED)
        coVerifyOrder {
            scheduler.rescheduleAll("system:${Intent.ACTION_TIME_CHANGED}")
            recovery.process(mapOf(4L to 1_000L))
        }
    }

    @Test fun `a boot re-arms everything and then looks for alarms that fell due while off`() = runBlocking {
        handler.handle(Intent.ACTION_BOOT_COMPLETED)
        coVerifyOrder {
            scheduler.rescheduleAll(any())
            ring.recoverStaleState()
            recovery.process(any())
        }
    }

    @Test fun `an app update re-arms but does not replay missed alarms`() = runBlocking {
        handler.handle(Intent.ACTION_MY_PACKAGE_REPLACED)
        coVerify { scheduler.rescheduleAll(any()) }
        coVerify(exactly = 0) { recovery.process(any()) }
    }

    @Test fun `a failing step never keeps the alarms from being armed or the rest from running`() = runBlocking {
        every { notifier.ensureChannels() } throws IllegalStateException("no channels")
        every { audio.restoreSystemVolumeIfStale() } throws IllegalStateException("no audio")
        coEvery { ring.recoverStaleState() } throws IOException("database")
        handler.handle(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        coVerify { scheduler.rescheduleAll(any()) }
        coVerify { recovery.process(any()) }
    }

    @Test fun `even when rescheduling itself fails the overdue alarms are still examined`() = runBlocking {
        coEvery { scheduler.rescheduleAll(any()) } throws IOException("database")
        handler.handle(Intent.ACTION_TIME_CHANGED)
        coVerify { recovery.process(any()) }
    }

    @Test fun `unknown broadcasts are ignored`() = runBlocking {
        handler.handle("com.example.not.ours")
        coVerify(exactly = 0) { scheduler.rescheduleAll(any()) }
    }
}

class RingCrashGuardTest {
    private val clock = TestClock()
    private val store = RingSessionStore()
    private val runtime = realRuntime()
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
    }
    private val guard = RingCrashGuard(store, runtime, pending, clock, SilentLog())

    private fun ringing(kind: RingKind = RingKind.PRIMARY, phase: RingPhase = RingPhase.RINGING, alarmId: Long = 5) {
        store.set(RingSession(Alarm(id = alarmId), kind, phase, eventId = 11, scheduledAt = 42, startedAt = clock.now))
    }

    @Test fun `a crash while an alarm rings arms a rescue for exactly that alarm and event`() {
        ringing(RingKind.BACKUP)
        assertTrue(guard.onCrash(RuntimeException("boom")))
        verify {
            pending.setAlarmClock(
                TriggerKind.RING_RETRY, 5, clock.now + RingRetry.DELAY_MS, RingRetry.encode(RingKind.BACKUP, 0), 11, 42,
            )
        }
        assertEquals(listOf(clock.now), runtime.ringCrashTimes())
    }

    @Test fun `a crash while nothing rings arms nothing`() {
        assertFalse(guard.onCrash(RuntimeException("boom")))
        verify(exactly = 0) { pending.setAlarmClock(any(), any(), any(), any(), any(), any()) }
        assertTrue(runtime.ringCrashTimes().isEmpty())
    }

    @Test fun `silent sunrise glows, timers and test alarms are not rescued`() {
        ringing(phase = RingPhase.SUNRISE)
        assertFalse(guard.onCrash(RuntimeException()))
        ringing(kind = RingKind.TIMER)
        assertFalse(guard.onCrash(RuntimeException()))
        ringing(kind = RingKind.TEST)
        assertFalse(guard.onCrash(RuntimeException()))
    }

    @Test fun `a crash loop gives up instead of re-launching for ever`() {
        ringing()
        repeat(CrashRescuePolicy.MAX_RESCUES) { assertTrue("rescue ${it + 1}", guard.onCrash(RuntimeException())) }
        assertFalse(guard.onCrash(RuntimeException()))
    }

    @Test fun `the rescue is reported as not armed when the OS refuses it`() {
        every { pending.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns false
        ringing()
        assertFalse(guard.onCrash(RuntimeException()))
    }
}
