package com.riyadm.wakenudge.engine

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.RecordingStarter
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.domain.model.WakeCheckSettings
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.repository.TimerRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The phone was off all night, the OS replays a stale trigger, a timer is mashed, settings are written in parallel. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RealWorldInstrumentedTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var alarms: AlarmRepository
    @Inject lateinit var history: HistoryRepository
    @Inject lateinit var timers: TimerRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var recovery: MissedAlarmRecovery
    @Inject lateinit var handler: TriggerHandler
    @Inject lateinit var ring: RingController
    @Inject lateinit var timerController: TimerController
    @Inject lateinit var pending: PendingAlarms
    @Inject lateinit var runtime: RuntimeState
    @Inject lateinit var store: RingSessionStore
    @Inject lateinit var starter: RecordingStarter
    @Inject lateinit var time: TimeSource

    @Before fun setUp() = hilt.inject()

    @After fun tearDown() = runBlocking {
        cancelEverything()
        timers.getTimers().forEach { timerController.delete(it.id) }
        store.set(null)
    }

    /** Cancels everything the test could have armed; ids come from what actually exists, not from a blind range. */
    private suspend fun cancelEverything() {
        val ids = alarms.getAlarms().map { it.id } + history.recent(500).map { it.alarmId } + RingIds.TEST_ALARM_ID
        for (id in ids.toSet()) {
            scheduler.cancel(id)
            for (kind in TriggerKind.entries) for (i in 0..3) if (pending.isScheduled(kind, id, i)) pending.cancel(kind, id, i)
        }
        for (t in timers.getTimers()) {
            pending.cancel(TriggerKind.TIMER, t.id)
        }
    }

    private fun trigger(kind: TriggerKind, id: Long, at: Long, eventId: Long = -1, index: Int = 0) =
        Intent(Triggers.action(kind)).putExtra(Triggers.EXTRA_ID, id).putExtra(Triggers.EXTRA_AT, at)
            .putExtra(Triggers.EXTRA_EVENT_ID, eventId).putExtra(Triggers.EXTRA_INDEX, index)

    // ---- the phone was off -----------------------------------------------------------------------------------

    @Test fun anAlarmDueMinutesAgoWhilePoweredOffStillRings() = runBlocking {
        val id = alarms.save(Alarm(label = "late", hour = 7, minute = 0))
        val dueAt = time.nowMillis() - 3 * 60_000L
        recovery.process(mapOf(id to dueAt))
        assertEquals(1, starter.requests.size)
        assertEquals(id, starter.requests.single().alarmId)
        assertEquals(RingKind.PRIMARY, starter.requests.single().kind)
    }

    @Test fun anAlarmMissedByHoursIsRecordedAndNeverQuietlyRearmedForTomorrow() = runBlocking {
        val id = alarms.save(Alarm(label = "missed", hour = 7, minute = 0))
        recovery.process(mapOf(id to time.nowMillis() - 3 * 3_600_000L))
        assertTrue("no ring this late", starter.requests.isEmpty())
        val event = history.recent(5).single { it.alarmId == id }
        assertEquals(AlarmOutcome.MISSED, event.outcome)
        assertFalse("a one-time alarm that was missed is switched off, not silently moved to tomorrow", alarms.getAlarm(id)!!.enabled)
        assertFalse(pending.isScheduled(TriggerKind.ALARM, id))
    }

    @Test fun aRepeatingAlarmMissedByHoursKeepsItsScheduleForTomorrow() = runBlocking {
        val id = alarms.save(Alarm(label = "daily", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK))
        recovery.process(mapOf(id to time.nowMillis() - 3 * 3_600_000L))
        scheduler.rescheduleAll("test")
        assertTrue(alarms.getAlarm(id)!!.enabled)
        assertTrue(pending.isScheduled(TriggerKind.ALARM, id))
    }

    @Test fun anAlarmThatAlreadyRangIsNotReplayedAfterReboot() = runBlocking {
        val id = alarms.save(Alarm(label = "done", hour = 7, minute = 0))
        val at = time.nowMillis() - 2 * 60_000L
        history.start(AlarmEvent(alarmId = id, label = "done", scheduledAt = at, firedAt = at, outcome = AlarmOutcome.DISMISSED, endedAt = at + 30_000))
        recovery.process(mapOf(id to at))
        assertTrue(starter.requests.isEmpty())
    }

    @Test fun anAlarmSwitchedOffBeforeTheRebootStaysQuiet() = runBlocking {
        val id = alarms.save(Alarm(label = "off", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK, enabled = false))
        recovery.process(mapOf(id to time.nowMillis() - 60_000L))
        assertTrue(starter.requests.isEmpty())
    }

    // ---- the OS delivers triggers late, twice, or for things that no longer exist -----------------------------

    @Test fun anAlarmTriggerStartsExactlyOneRing() = runBlocking {
        val id = alarms.save(Alarm(label = "now", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK))
        val at = time.nowMillis()
        handler.handle(trigger(TriggerKind.ALARM, id, at))
        assertEquals(1, starter.requests.size)
        ring.begin(starter.requests.single())                     // what the service does with the request
        handler.handle(trigger(TriggerKind.ALARM, id, at))         // duplicate delivery of the very same occurrence
        assertEquals("duplicate trigger must not double-ring", 1, starter.requests.size)
        assertNotNull(history.findOccurrence(id, at))
    }

    @Test fun theFirstSavedAlarmOnAFreshInstallArmsTheWatchdog() = runBlocking {
        pending.cancel(TriggerKind.WATCHDOG, 0)                    // what a fresh install looks like
        assertFalse(pending.isScheduled(TriggerKind.WATCHDOG, 0))
        val id = alarms.save(Alarm(label = "first", hour = 6, minute = 30, repeatDaysMask = Alarm.EVERY_DAY_MASK))
        scheduler.schedule(id)
        assertTrue("the safety net is armed once an alarm exists", pending.isScheduled(TriggerKind.WATCHDOG, 0))
    }

    @Test fun aLateDuplicateTriggerCannotOverrideASnooze() = runBlocking {
        val id = alarms.save(Alarm(label = "snz", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK))
        val at = time.nowMillis()
        handler.handle(trigger(TriggerKind.ALARM, id, at))
        ring.begin(starter.requests.single())
        assertTrue(ring.snooze() is SnoozeOutcome.Snoozed)
        starter.requests.clear()
        handler.handle(trigger(TriggerKind.ALARM, id, at))
        assertTrue("the snooze stays in charge", starter.requests.isEmpty())
        assertTrue(pending.isScheduled(TriggerKind.SNOOZE, id))
    }

    @Test fun staleSnoozeAndBackupTriggersAfterDismissalStayQuiet() = runBlocking {
        val id = alarms.save(Alarm(label = "x", hour = 7, minute = 0))
        val request = ring.onAlarmFired(id, time.nowMillis())!!
        ring.begin(request)
        ring.dismiss()
        starter.requests.clear()
        handler.handle(trigger(TriggerKind.SNOOZE, id, 0, request.eventId))
        handler.handle(trigger(TriggerKind.BACKUP, id, 0, request.eventId, index = 1))
        assertTrue("nothing rings for a resolved event", starter.requests.isEmpty())
    }

    @Test fun triggersForDeletedAlarmsAndTimersAreHarmless() = runBlocking {
        for (kind in TriggerKind.entries) handler.handle(trigger(kind, 31337, time.nowMillis(), eventId = 77))
        assertTrue(starter.requests.isEmpty())
    }

    @Test fun garbageBroadcastsAreIgnored() = runBlocking {
        handler.handle(Intent())
        handler.handle(Intent("com.example.not.ours"))
        handler.handle(Intent(Triggers.ACTION_PREFIX + "NOT_A_KIND"))
        handler.handle(Intent(Triggers.action(TriggerKind.ALARM)))   // missing every extra
        assertTrue(starter.requests.isEmpty())
    }

    @Test fun aWakeCheckPromptForADeletedAlarmDoesNotCrashOrNotify() = runBlocking {
        val id = alarms.save(Alarm(label = "wc", hour = 7, minute = 0, wakeCheck = WakeCheckSettings(enabled = true)))
        alarms.delete(id)
        handler.handle(trigger(TriggerKind.WAKE_CHECK_PROMPT, id, time.nowMillis()))
        handler.handle(trigger(TriggerKind.WAKE_CHECK_DEADLINE, id, time.nowMillis()))
        assertTrue(starter.requests.isEmpty())
    }

    // ---- timers ----------------------------------------------------------------------------------------------

    @Test fun mashingStartAndPauseOnATimerKeepsItConsistent() = runBlocking {
        val id = timerController.create(120_000, "mash", startNow = false)
        repeat(40) { n -> if (n % 2 == 0) timerController.start(id) else timerController.pause(id) }
        val t = timers.getTimer(id)!!
        assertTrue("remaining within the duration: $t", t.remainingMs in 0..t.durationMs)
        assertEquals("a trigger exists exactly while the timer runs", t.state == TimerState.RUNNING, pending.isScheduled(TriggerKind.TIMER, id))
    }

    @Test fun parallelTimerCommandsNeverLeaveAnOrphanTrigger() = runBlocking {
        val id = timerController.create(120_000, "race", startNow = false)
        coroutineScope {
            (1..30).map { n -> async(Dispatchers.Default) { if (n % 3 == 0) timerController.pause(id) else if (n % 3 == 1) timerController.start(id) else timerController.addMinute(id) } }.awaitAll()
        }
        val t = timers.getTimer(id)!!
        assertEquals(t.state == TimerState.RUNNING, pending.isScheduled(TriggerKind.TIMER, id))
    }

    @Test fun deletingARunningTimerCancelsItsTrigger() = runBlocking {
        val id = timerController.create(60_000, "gone", startNow = true)
        assertTrue(pending.isScheduled(TriggerKind.TIMER, id))
        timerController.delete(id)
        assertFalse(pending.isScheduled(TriggerKind.TIMER, id))
        assertNull(ring.onTimerFired(id))
    }

    @Test fun absurdTimerDurationsAreClamped() = runBlocking {
        val zero = timers.getTimer(timerController.create(0, "", false))!!
        assertTrue("at least a second: ${zero.durationMs}", zero.durationMs >= 1_000)
        val huge = timers.getTimer(timerController.create(Long.MAX_VALUE, "", false))!!
        assertTrue("capped near 100 hours: ${huge.durationMs}", huge.durationMs <= 100L * 3_600_000)
        val negative = timers.getTimer(timerController.create(-5_000, "", false))!!
        assertTrue(negative.durationMs >= 1_000)
    }

    @Test fun aTimerThatFiresTwiceRingsOnce() = runBlocking {
        val id = timers.save(CountdownTimer(durationMs = 60_000, state = TimerState.RUNNING, endsAtMillis = time.nowMillis() - 1, remainingMs = 0))
        assertNotNull(ring.onTimerFired(id))
        assertNull("second delivery ignored", ring.onTimerFired(id))
    }

    // ---- shared state ----------------------------------------------------------------------------------------

    @Test fun concurrentSettingsWritesDoNotLoseUpdates() = runBlocking {
        val before = settings.current().emergencyStopHoldSeconds
        coroutineScope {
            (1..100).map { async(Dispatchers.Default) { settings.update { it.copy(emergencyStopHoldSeconds = it.emergencyStopHoldSeconds + 1) } } }.awaitAll()
        }
        assertEquals(before + 100, settings.current().emergencyStopHoldSeconds)
    }
}
