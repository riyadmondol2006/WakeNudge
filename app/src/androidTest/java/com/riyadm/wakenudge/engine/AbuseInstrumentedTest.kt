package com.riyadm.wakenudge.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.data.backup.BackupManager
import com.riyadm.wakenudge.data.backup.ImportResult
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.model.SunriseSettings
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.model.WakeCheckSettings
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.repository.TimerRepository
import com.riyadm.wakenudge.domain.repository.WakeCheckRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.usecase.ActionResult
import com.riyadm.wakenudge.domain.usecase.AlarmActions
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.LocalDate
import java.util.TimeZone
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

/**
 * "What will real users do to this?" — rapid taps, deleting things that are mid-flight, two alarms at once, a timer
 * and an alarm colliding, hundreds of alarms, corrupt data, travelling across time zones. Each test states the
 * behaviour a person would expect, and fails if the app does something surprising instead.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AbuseInstrumentedTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var alarms: AlarmRepository
    @Inject lateinit var history: HistoryRepository
    @Inject lateinit var wakeChecks: WakeCheckRepository
    @Inject lateinit var timers: TimerRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var actions: AlarmActions
    @Inject lateinit var ring: RingController
    @Inject lateinit var store: RingSessionStore
    @Inject lateinit var wakeCheckController: WakeCheckController
    @Inject lateinit var pending: PendingAlarms
    @Inject lateinit var runtime: RuntimeState
    @Inject lateinit var time: TimeSource
    @Inject lateinit var backup: BackupManager
    @Inject lateinit var dataStore: DataStore<Preferences>

    private val originalZone: TimeZone = TimeZone.getDefault()

    @Before fun setUp() = hilt.inject()

    @After fun tearDown() = runBlocking {
        TimeZone.setDefault(originalZone)
        cancelEverything()
        store.set(null)
        while (ring.takeQueued() != null) Unit
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

    private fun alarmIn(minutes: Long, block: Alarm.() -> Alarm = { this }): Alarm {
        val at = time.now().plusMinutes(minutes)
        return Alarm(label = "abuse", hour = at.hour, minute = at.minute).block()
    }

    private suspend fun fireAndBegin(id: Long): RingSession {
        val at = scheduler.nextTriggerOf(id) ?: time.nowMillis()
        return ring.begin(ring.onAlarmFired(id, at)!!)!!
    }

    // ---- hammering the controls ------------------------------------------------------------------------------

    @Test fun flippingAnAlarmOnAndOffFiftyTimesAtOnceLeavesAConsistentState() = runBlocking {
        val id = alarms.save(alarmIn(90))
        scheduler.schedule(id)
        coroutineScope {
            (1..50).map { n -> async(Dispatchers.Default) { actions.setEnabled(id, n % 2 == 0, overrideLock = true) } }.awaitAll()
        }
        val enabled = alarms.getAlarm(id)!!.enabled
        assertEquals("registered iff enabled", enabled, pending.isScheduled(TriggerKind.ALARM, id))
    }

    @Test fun mashingDismissAndSnoozeResolvesTheAlarmExactlyOnce() = runBlocking {
        val id = alarms.save(alarmIn(1) { copy(snooze = SnoozeSettings(minutes = 5, maxCount = 5)) })
        val session = fireAndBegin(id)
        coroutineScope {
            (1..8).map { n -> async(Dispatchers.Default) { if (n % 2 == 0) ring.snooze() else ring.dismiss() } }.awaitAll()
        }
        assertNull(store.current)
        val e = history.get(session.eventId)!!
        assertTrue("exactly one resolution: $e", (e.outcome == AlarmOutcome.DISMISSED) xor (e.snoozeCount == 1 && e.outcome == AlarmOutcome.RINGING))
        assertTrue("never more than one snooze", e.snoozeCount <= 1)
        val snoozed = pending.isScheduled(TriggerKind.SNOOZE, id)
        assertEquals("a pending snooze exists only if the event is still open", e.outcome == AlarmOutcome.RINGING, snoozed)
    }

    @Test fun actionsOnADeadSessionDoNothingAndDoNotCrash() = runBlocking {
        assertNull(store.current)
        assertTrue(ring.snooze() is SnoozeOutcome.NotRinging)
        ring.dismiss(); ring.emergencyStop(); ring.tick(); ring.noteInteraction(); ring.silenceTemporarily()
        ring.dismissSnoozed(424242)
        assertNull(ring.wakeNow(424242))
        assertNull(ring.onSnoozeExpired(424242, 1))
        assertNull(ring.onBackupFired(424242, 99, 1))
        assertNull(ring.onWakeCheckDeadline(424242))
        assertNull(ring.onTimerFired(424242))
        wakeCheckController.answer(424242); wakeCheckController.onPrompt(424242); wakeCheckController.abandon(424242, null)
        assertNull(ring.onAlarmFired(424242, time.nowMillis()))
        ring.recoverStaleState()
    }

    // ---- things disappearing mid-flight ----------------------------------------------------------------------

    @Test fun deletingASnoozedAlarmSilencesEverythingItHadPending() = runBlocking {
        val id = alarms.save(alarmIn(1) { copy(wakeCheck = WakeCheckSettings(enabled = true)) })
        val session = fireAndBegin(id)
        ring.snooze()
        assertTrue(pending.isScheduled(TriggerKind.SNOOZE, id))
        actions.delete(id, overrideLock = true)
        assertFalse("snooze cancelled", pending.isScheduled(TriggerKind.SNOOZE, id))
        for (i in 1..3) assertFalse("backup $i cancelled", pending.isScheduled(TriggerKind.BACKUP, id, i))
        assertTrue("the open event is closed, not left ringing forever", history.get(session.eventId)!!.outcome != AlarmOutcome.RINGING)
    }

    @Test fun switchingASnoozedAlarmOffCancelsTheSnooze() = runBlocking {
        val id = alarms.save(alarmIn(1))
        val session = fireAndBegin(id)
        ring.snooze()
        actions.setEnabled(id, false)
        assertFalse("turning it off must stop the snooze from ringing", pending.isScheduled(TriggerKind.SNOOZE, id))
        assertTrue(history.get(session.eventId)!!.outcome != AlarmOutcome.RINGING)
    }

    @Test fun deletingAnAlarmDuringItsWakeCheckEndsTheCheck() = runBlocking {
        val id = alarms.save(alarmIn(1) { copy(wakeCheck = WakeCheckSettings(enabled = true, delayMinutes = 2)) })
        fireAndBegin(id)
        ring.dismiss()
        assertNotNull(wakeChecks.get(id))
        actions.delete(id, overrideLock = true)
        assertNull("no orphaned check", wakeChecks.get(id))
        assertFalse(pending.isScheduled(TriggerKind.WAKE_CHECK_PROMPT, id))
        assertFalse(pending.isScheduled(TriggerKind.WAKE_CHECK_DEADLINE, id))
    }

    @Test fun anAlarmDeletedWhileRingingStillRingsAndDismissesCleanly() = runBlocking {
        val id = alarms.save(alarmIn(1))
        val session = fireAndBegin(id)
        alarms.delete(id)
        assertNotNull("session unaffected", store.current)
        ring.dismiss()
        assertEquals(AlarmOutcome.DISMISSED, history.get(session.eventId)!!.outcome)
    }

    // ---- collisions ------------------------------------------------------------------------------------------

    @Test fun twoAlarmsInTheSameMinuteBothGetTheirTurn() = runBlocking {
        val a = alarms.save(alarmIn(1) { copy(label = "A") })
        val b = alarms.save(alarmIn(1) { copy(label = "B") })
        val first = ring.begin(ring.onAlarmFired(a, time.nowMillis())!!)!!
        val second = ring.begin(ring.onAlarmFired(b, time.nowMillis())!!)
        assertEquals("the ringing alarm is not hijacked", first.alarm.id, second!!.alarm.id)
        ring.dismiss()
        val next = ring.takeQueued()
        assertNotNull("the second alarm was queued, not lost", next)
        assertEquals(b, next!!.alarmId)
        assertEquals(b, ring.begin(next)!!.alarm.id)
        ring.dismiss()
        assertEquals(AlarmOutcome.DISMISSED, history.recent(5).first { it.alarmId == b }.outcome)
        assertNull(ring.takeQueued())
    }

    @Test fun anAlarmInterruptsARingingTimerAndTheTimerIsNotLeftStuck() = runBlocking {
        val timerId = timers.save(CountdownTimer(label = "pasta", durationMs = 60_000, state = TimerState.RUNNING, endsAtMillis = time.nowMillis() - 1, remainingMs = 0))
        val timerRequest = ring.onTimerFired(timerId)!!
        ring.begin(timerRequest)
        assertTrue(store.current!!.isTimer)
        val id = alarms.save(alarmIn(1))
        val s = ring.begin(ring.onAlarmFired(id, time.nowMillis())!!)!!
        assertEquals("the alarm wins", id, s.alarm.id)
        assertEquals("timer is no longer ringing", TimerState.IDLE, timers.getTimer(timerId)!!.state)
    }

    @Test fun aTimerFinishingDuringAnAlarmWaitsItsTurn() = runBlocking {
        val id = alarms.save(alarmIn(1))
        fireAndBegin(id)
        val timerId = timers.save(CountdownTimer(durationMs = 60_000, state = TimerState.RUNNING, endsAtMillis = time.nowMillis() - 1, remainingMs = 0))
        ring.begin(ring.onTimerFired(timerId)!!)
        assertEquals("alarm still on screen", id, store.current!!.alarm.id)
        ring.dismiss()
        val queued = ring.takeQueued()
        assertNotNull(queued)
        assertEquals(RingKind.TIMER, queued!!.kind)
        assertEquals(TimerState.RINGING, timers.getTimer(timerId)!!.state)
    }

    @Test fun ignoringTheFirstAlarmForeverStillLetsTheSecondRing() = runBlocking {
        val a = alarms.save(alarmIn(1) { copy(antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 1, backupEnabled = false)) })
        val b = alarms.save(alarmIn(1))
        ring.begin(ring.onAlarmFired(a, time.nowMillis())!!)
        ring.begin(ring.onAlarmFired(b, time.nowMillis())!!)
        store.update { it.copy(lastInteractionAt = time.nowMillis() - 90_000) }
        ring.tick()
        assertNull("first alarm timed out", store.current)
        assertEquals(b, ring.takeQueued()?.alarmId)
    }

    // ---- scale and corruption --------------------------------------------------------------------------------

    @Test fun twoHundredAlarmsAllArmWithinReasonableTime() = runBlocking {
        val base = time.now().plusMinutes(120)
        val ids = (0 until 200).map { n ->
            val t = base.plusMinutes(n * 3L)
            alarms.save(Alarm(label = "n$n", hour = t.hour, minute = t.minute, repeatDaysMask = Alarm.EVERY_DAY_MASK, sunrise = SunriseSettings(enabled = true), upcomingNoticeMinutes = 15))
        }
        val started = System.nanoTime()
        scheduler.rescheduleAll("abuse-200")
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("rescheduled 200 alarms in ${seconds}s", seconds < 10)
        assertEquals("every alarm armed", 200, ids.count { pending.isScheduled(TriggerKind.ALARM, it) })
    }

    @Test fun moreAlarmsThanTheOsAllowsDegradesGracefully() = runBlocking {
        val base = time.now().plusMinutes(60)
        val ids = (0 until 560).map { n ->
            val t = base.plusMinutes(n.toLong())
            alarms.save(Alarm(label = "n$n", hour = t.hour, minute = t.minute, repeatDaysMask = Alarm.EVERY_DAY_MASK))
        }
        scheduler.rescheduleAll("abuse-560")          // must not throw, whatever the OS limit is
        val armed = ids.count { pending.isScheduled(TriggerKind.ALARM, it) }
        assertTrue("armed $armed of ${ids.size}", armed >= 400)
        assertTrue("the soonest alarm is always among the armed ones", pending.isScheduled(TriggerKind.ALARM, ids.first()))
    }

    @Test fun oneCorruptAlarmCannotStopTheOthersFromBeingArmed() = runBlocking {
        val good = alarms.save(alarmIn(90))
        alarms.save(Alarm(label = "broken", hour = 99, minute = 99, repeatDaysMask = Alarm.EVERY_DAY_MASK))
        scheduler.rescheduleAll("abuse-corrupt")
        assertTrue("healthy alarm still armed", pending.isScheduled(TriggerKind.ALARM, good))
    }

    @Test fun garbageInTheSettingsStoreFallsBackToDefaults() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("app_settings_v1")] = "{ this is : not json ]]" }
        assertEquals(AppSettings(), settings.current())
        settings.update { it.copy(haptics = false) }
        assertFalse(settings.current().haptics)
    }

    @Test fun anExpiredVacationSwitchesItselfOffAndAlarmsKeepRinging() = runBlocking {
        val id = alarms.save(alarmIn(90) { copy(repeatDaysMask = Alarm.EVERY_DAY_MASK) })
        settings.update { it.copy(vacation = VacationMode(enabled = true, resumeEpochDay = LocalDate.now().toEpochDay() - 3)) }
        scheduler.rescheduleAll("abuse-vacation")
        assertTrue("alarm armed", pending.isScheduled(TriggerKind.ALARM, id))
        assertFalse("stale vacation flag cleared so the UI stops claiming alarms are paused", settings.current().vacation.enabled)
    }

    // ---- travel and clocks -----------------------------------------------------------------------------------

    @Test fun changingTimeZoneMovesTheRingToTheNewLocalTime() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val id = alarms.save(Alarm(hour = 7, minute = 30, repeatDaysMask = Alarm.EVERY_DAY_MASK))
        scheduler.rescheduleAll("tz-utc")
        val utc = scheduler.nextTriggerOf(id)!!
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        scheduler.rescheduleAll("tz-tokyo")
        val tokyo = scheduler.nextTriggerOf(id)!!
        val atTokyo = java.time.Instant.ofEpochMilli(tokyo).atZone(java.time.ZoneId.of("Asia/Tokyo"))
        assertEquals(7, atTokyo.hour); assertEquals(30, atTokyo.minute)
        assertTrue("different instant than in UTC", tokyo != utc)
        assertEquals("what is armed matches what is calculated", tokyo, runtime.registeredTriggers()[id])
    }

    // ---- crash and update recovery ---------------------------------------------------------------------------

    @Test fun staleRingingEventsAndStuckTimersAreCleanedUpAfterACrash() = runBlocking {
        val id = alarms.save(alarmIn(120))
        val old = time.nowMillis() - 3 * 3_600_000L
        val stale = history.start(AlarmEvent(alarmId = id, label = "x", scheduledAt = old, firedAt = old, outcome = AlarmOutcome.RINGING))
        val stuck = timers.save(CountdownTimer(durationMs = 60_000, state = TimerState.RINGING, remainingMs = 0))
        ring.recoverStaleState()
        assertEquals(AlarmOutcome.MISSED, history.get(stale)!!.outcome)
        assertEquals("a timer cannot stay RINGING with nothing ringing", TimerState.IDLE, timers.getTimer(stuck)!!.state)
    }

    @Test fun restoringABackupWhileAlarmsAreSnoozedLeavesNoGhostTriggers() = runBlocking {
        val id = alarms.save(alarmIn(1))
        fireAndBegin(id)
        ring.snooze()
        assertTrue(pending.isScheduled(TriggerKind.SNOOZE, id))
        val result = backup.import(
            """{"formatVersion":1,"app":"WakeNudge","appVersion":"1","createdAt":0,"settings":{},"alarms":[],"groups":[],"worldClocks":[],"exceptionDates":[]}""",
        )
        assertTrue(result is ImportResult.Success)
        assertTrue(alarms.getAlarms().isEmpty())
        assertFalse("ghost snooze after restore", pending.isScheduled(TriggerKind.SNOOZE, id))
    }

    // ---- hostile text ----------------------------------------------------------------------------------------

    @Test fun labelsWithEmojiRightToLeftAndHugeLengthSurviveSavingWithoutBeingGarbled() = runBlocking {
        val emojiHeavy = "👩‍👩‍👧‍👦".repeat(40)            // multi-code-unit emoji, far over the label limit
        val id = (actions.save(alarmIn(90) { copy(label = emojiHeavy) }) as ActionResult.Done).value
        val saved = alarms.getAlarm(id)!!.label
        assertTrue("limited", saved.codePointCount(0, saved.length) <= 60)
        assertTrue("no lone surrogate from a bad cut", saved.indices.none { i -> Character.isHighSurrogate(saved[i]) && (i + 1 >= saved.length || !Character.isLowSurrogate(saved[i + 1])) })
        val rtl = (actions.save(alarmIn(95) { copy(label = "مرحبا بالعالم — שלום עולם") }) as ActionResult.Done).value
        assertEquals("مرحبا بالعالم — שלום עולם", alarms.getAlarm(rtl)!!.label)
        val huge = (actions.save(alarmIn(99) { copy(label = "x".repeat(100_000)) }) as ActionResult.Done).value
        assertTrue(alarms.getAlarm(huge)!!.label.length <= 60)
    }
}
