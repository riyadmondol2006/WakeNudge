package com.riyadm.wakenudge.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.model.SoundMode
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.domain.model.VibrationPattern
import com.riyadm.wakenudge.domain.model.VibrationSettings
import com.riyadm.wakenudge.domain.model.WakeCheck
import com.riyadm.wakenudge.domain.model.WakeCheckSettings
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.repository.WakeCheckRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.usecase.ActionResult
import com.riyadm.wakenudge.domain.usecase.AlarmActions
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
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
 * Exercises the alarm engine against the real AlarmManager and a real database on a device: registration, the
 * snooze → backup → dismiss state machine, reboot/update recovery and the still-awake check. The audio/UI side of a
 * ring is covered separately; here the session is driven through [RingController] directly.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EngineInstrumentedTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var alarms: AlarmRepository
    @Inject lateinit var history: HistoryRepository
    @Inject lateinit var wakeChecks: WakeCheckRepository
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var actions: AlarmActions
    @Inject lateinit var ring: RingController
    @Inject lateinit var store: RingSessionStore
    @Inject lateinit var wakeCheckController: WakeCheckController
    @Inject lateinit var pending: PendingAlarms
    @Inject lateinit var time: TimeSource
    @Inject lateinit var runtime: RuntimeState

    @Before fun setUp() = hilt.inject()

    @After fun tearDown() = runBlocking {
        alarms.getAlarms().forEach { scheduler.cancel(it.id) }
        for (kind in TriggerKind.entries) for (id in -2L..30L) for (i in 0..3) pending.cancel(kind, id, i)
        store.set(null)
    }

    /** An alarm that rings [minutesAhead] minutes from now (one-time, so it fires exactly once). */
    private fun alarmIn(minutesAhead: Long, block: Alarm.() -> Alarm = { this }): Alarm {
        val at = time.now().plusMinutes(minutesAhead)
        return Alarm(label = "test", hour = at.hour, minute = at.minute).block()
    }

    @Test fun enabledAlarmIsRegisteredAndMatchesTheCalculatedTime() = runBlocking {
        val id = alarms.save(alarmIn(90))
        scheduler.schedule(id)
        assertTrue(pending.isScheduled(TriggerKind.ALARM, id))
        val next = scheduler.nextTriggerOf(id)!!
        val minutes = (next - time.nowMillis()) / 60_000
        assertTrue("expected ~90 min, was $minutes", minutes in 88..91)
        assertEquals(id, scheduler.nextAlarm()?.alarm?.id)
    }

    @Test fun disablingAnAlarmCancelsItsTrigger() = runBlocking {
        val id = alarms.save(alarmIn(90))
        scheduler.schedule(id)
        assertTrue(pending.isScheduled(TriggerKind.ALARM, id))
        actions.setEnabled(id, false)
        assertFalse(pending.isScheduled(TriggerKind.ALARM, id))
        assertNull(scheduler.nextTriggerOf(id))
    }

    @Test fun deletingAnAlarmCancelsItsTrigger() = runBlocking {
        val id = alarms.save(alarmIn(90))
        scheduler.schedule(id)
        actions.delete(id)
        assertFalse(pending.isScheduled(TriggerKind.ALARM, id))
        assertNull(alarms.getAlarm(id))
    }

    @Test fun testAlarmUsesTheRealPipeline() = runBlocking {
        val at = scheduler.scheduleTestAlarm(600)
        assertTrue(at > time.nowMillis())
        assertTrue(pending.isScheduled(TriggerKind.ALARM, RingIds.TEST_ALARM_ID))
        pending.cancel(TriggerKind.ALARM, RingIds.TEST_ALARM_ID)
    }

    @Test fun skipNextMovesTheRingByExactlyOneOccurrence() = runBlocking {
        val base = alarmIn(60) { copy(repeatDaysMask = Alarm.EVERY_DAY_MASK) }
        val id = alarms.save(base)
        scheduler.schedule(id)
        val first = scheduler.nextTriggerOf(id)!!
        assertTrue(actions.skipNext(id) is ActionResult.Done)
        val second = scheduler.nextTriggerOf(id)!!
        assertTrue(second - first in 23 * 3_600_000L..25 * 3_600_000L)
        actions.clearSkip(id)
        assertEquals(first, scheduler.nextTriggerOf(id))
    }

    @Test fun editProtectionRefusesLateChangesUntilOverridden() = runBlocking {
        val id = alarms.save(alarmIn(5) { copy(antiOversleep = AntiOversleepSettings(editLockMinutes = 15)) })
        scheduler.schedule(id)
        assertTrue(actions.setEnabled(id, false) is ActionResult.Locked)
        assertTrue(actions.delete(id) is ActionResult.Locked)
        assertTrue("still registered", pending.isScheduled(TriggerKind.ALARM, id))
        assertTrue(actions.setEnabled(id, false, overrideLock = true) is ActionResult.Done)
        assertFalse(pending.isScheduled(TriggerKind.ALARM, id))
    }

    @Test fun fullAlarmRoundTripsThroughRoom() = runBlocking {
        val original = alarmIn(30) {
            copy(
                repeatDaysMask = Alarm.WEEKDAYS_MASK,
                sound = SoundSettings(mode = SoundMode.RANDOM_CUSTOM, playlist = listOf("content://a/1", "content://b/2"), randomPool = setOf("bell")),
                vibration = VibrationSettings(pattern = VibrationPattern.CUSTOM, strength = 70, customTimings = listOf(0, 200, 100, 300)),
                snooze = SnoozeSettings(minutes = 7, maxCount = 4, decreasing = true, decreaseStepMinutes = 2),
                wakeCheck = WakeCheckSettings(enabled = true, delayMinutes = 3, responseSeconds = 45, maxChecks = 2, maxReRings = 4),
                missions = listOf(
                    MissionConfig(MissionType.MATH, Difficulty.HARD, 5),
                    MissionConfig(MissionType.QR_BARCODE, params = mapOf(MissionConfig.PARAM_CODE to "ABC-123")),
                ),
                snoozeMissions = listOf(MissionConfig(MissionType.SHAKE, Difficulty.EASY, 20)),
            )
        }
        val id = alarms.save(original)
        val loaded = alarms.getAlarm(id)!!
        assertEquals(original.copy(id = id, createdAt = loaded.createdAt, updatedAt = loaded.updatedAt), loaded)
    }

    // ---- the ring state machine ------------------------------------------------------------------------------

    private suspend fun startRing(alarm: Alarm): Pair<Long, RingSession> {
        val id = alarms.save(alarm)
        scheduler.schedule(id)
        val scheduledAt = scheduler.nextTriggerOf(id)!!
        val request = ring.onAlarmFired(id, scheduledAt)!!
        val session = ring.begin(request)!!
        return id to session
    }

    @Test fun snoozeThenReturnThenDismissLeavesNothingBehind() = runBlocking {
        val (id, session) = startRing(alarmIn(1) { copy(snooze = SnoozeSettings(minutes = 5, maxCount = 2)) })
        assertEquals(RingKind.PRIMARY, session.kind)
        assertTrue("backup armed at fire time", pending.isScheduled(TriggerKind.BACKUP, id, 1))
        assertTrue(history.get(session.eventId)!!.pendingBackups.isNotEmpty())

        val snoozed = ring.snooze() as SnoozeOutcome.Snoozed
        assertEquals(5, snoozed.minutes)
        assertNull(store.current)
        assertTrue(pending.isScheduled(TriggerKind.SNOOZE, id))
        assertFalse("backups wait while snoozed", pending.isScheduled(TriggerKind.BACKUP, id, 1))
        val afterSnooze = history.get(session.eventId)!!
        assertEquals(1, afterSnooze.snoozeCount)
        assertTrue(afterSnooze.snoozeUntil > time.nowMillis())

        val back = ring.onSnoozeExpired(id, session.eventId)!!
        assertEquals(RingKind.SNOOZE_RETURN, back.kind)
        assertEquals(1, back.snoozeCount)
        ring.begin(back)
        assertTrue(pending.isScheduled(TriggerKind.BACKUP, id, 1))

        ring.dismiss()
        val done = history.get(session.eventId)!!
        assertEquals(AlarmOutcome.DISMISSED, done.outcome)
        assertNotNull(done.endedAt)
        assertEquals(0L, done.snoozeUntil)
        assertTrue(done.pendingBackups.isEmpty())
        assertFalse(pending.isScheduled(TriggerKind.BACKUP, id, 1))
        assertFalse(pending.isScheduled(TriggerKind.SNOOZE, id))
    }

    @Test fun snoozeLimitIsEnforced() = runBlocking {
        val (_, _) = startRing(alarmIn(1) { copy(snooze = SnoozeSettings(minutes = 1, maxCount = 1)) })
        assertTrue(ring.snooze() is SnoozeOutcome.Snoozed)
        val event = history.recent(5).first()
        val again = ring.onSnoozeExpired(event.alarmId, event.id)!!
        ring.begin(again)
        assertTrue("second snooze refused at the limit", ring.snooze() is SnoozeOutcome.NotAllowed)
        assertNotNull(store.current)
    }

    @Test fun emergencyStopIsRecordedHonestlyAndSkipsFollowUps() = runBlocking {
        val (id, session) = startRing(alarmIn(1) { copy(missions = listOf(MissionConfig(MissionType.MATH))) })
        ring.emergencyStop()
        assertNull(store.current)
        assertEquals(AlarmOutcome.EMERGENCY_STOPPED, history.get(session.eventId)!!.outcome)
        assertFalse(pending.isScheduled(TriggerKind.BACKUP, id, 1))
    }

    @Test fun oneTimeAlarmSwitchesItselfOffWhenItFires() = runBlocking {
        val (id, _) = startRing(alarmIn(1))
        assertFalse(alarms.getAlarm(id)!!.enabled)
        assertFalse(pending.isScheduled(TriggerKind.ALARM, id))
    }

    @Test fun repeatingAlarmArmsTomorrowImmediatelyAtFireTime() = runBlocking {
        val (id, _) = startRing(alarmIn(1) { copy(repeatDaysMask = Alarm.EVERY_DAY_MASK) })
        assertTrue(alarms.getAlarm(id)!!.enabled)
        assertTrue(pending.isScheduled(TriggerKind.ALARM, id))
        // The fire was simulated a minute early, so judge by what the scheduler actually armed, not by "now".
        val armed = runtime.registeredTriggers()[id]!!
        assertTrue("armed for about a day later, was ${(armed - time.nowMillis()) / 60_000} min", armed - time.nowMillis() > 23 * 3_600_000L)
    }

    @Test fun duplicateTriggerForTheSameOccurrenceIsIgnored() = runBlocking {
        val id = alarms.save(alarmIn(1) { copy(repeatDaysMask = Alarm.EVERY_DAY_MASK) })
        val at = time.nowMillis()
        assertNotNull(ring.onAlarmFired(id, at))
        // The first trigger created an event; once it is resolved a second delivery must not ring again.
        val event = history.recent(1).first()
        history.update(event.copy(outcome = AlarmOutcome.DISMISSED, endedAt = time.nowMillis()))
        assertNull(ring.onAlarmFired(id, at))
    }

    // ---- reboot / update recovery ---------------------------------------------------------------------------

    @Test fun snoozeAndBackupsAreRearmedAfterAnOsWipe() = runBlocking {
        val id = alarms.save(alarmIn(120))
        val now = time.nowMillis()
        val eventId = history.start(
            AlarmEvent(
                alarmId = id, label = "x", scheduledAt = now - 60_000, firedAt = now - 60_000, outcome = AlarmOutcome.RINGING,
                snoozeUntil = now + 10 * 60_000, pendingBackups = listOf(now + 20 * 60_000, now + 30 * 60_000),
            ),
        )
        // What a reboot or app update does: every registration disappears.
        pending.cancel(TriggerKind.SNOOZE, id)
        for (i in 1..3) pending.cancel(TriggerKind.BACKUP, id, i)
        assertFalse(pending.isScheduled(TriggerKind.SNOOZE, id))

        scheduler.rescheduleAll("test-reboot")

        assertTrue("snooze restored", pending.isScheduled(TriggerKind.SNOOZE, id))
        assertTrue("backup 1 restored", pending.isScheduled(TriggerKind.BACKUP, id, 1))
        assertTrue("backup 2 restored", pending.isScheduled(TriggerKind.BACKUP, id, 2))
        assertTrue(history.get(eventId) != null)
    }

    // ---- "Still awake?" -------------------------------------------------------------------------------------

    private fun wakeCheckAlarm(maxChecks: Int = 1, maxReRings: Int = 2) = alarmIn(1) {
        copy(wakeCheck = WakeCheckSettings(enabled = true, delayMinutes = 2, responseSeconds = 30, maxChecks = maxChecks, maxReRings = maxReRings))
    }

    @Test fun answeredWakeCheckPasses() = runBlocking {
        val (id, session) = startRing(wakeCheckAlarm())
        ring.dismiss()
        assertEquals(WakeCheck.Phase.WAITING, wakeChecks.get(id)!!.phase)
        assertTrue(pending.isScheduled(TriggerKind.WAKE_CHECK_PROMPT, id))
        assertEquals(WakeCheckStatus.PENDING, history.get(session.eventId)!!.wakeCheck)

        wakeCheckController.onPrompt(id)
        assertEquals(WakeCheck.Phase.PROMPTED, wakeChecks.get(id)!!.phase)
        assertTrue(pending.isScheduled(TriggerKind.WAKE_CHECK_DEADLINE, id))

        wakeCheckController.answer(id)
        assertNull(wakeChecks.get(id))
        assertFalse(pending.isScheduled(TriggerKind.WAKE_CHECK_DEADLINE, id))
        assertEquals(WakeCheckStatus.PASSED, history.get(session.eventId)!!.wakeCheck)
    }

    @Test fun unansweredWakeCheckRingsAgainThenGivesUpAtTheLimit() = runBlocking {
        val (id, session) = startRing(wakeCheckAlarm(maxReRings = 1))
        ring.dismiss()
        wakeCheckController.onPrompt(id)

        val rering = ring.onWakeCheckDeadline(id)!!
        assertEquals(RingKind.WAKE_CHECK_RERING, rering.kind)
        assertEquals(1, history.get(session.eventId)!!.wakeCheckReRings)
        ring.begin(rering)
        ring.dismiss()
        assertEquals("re-ring count carried into the new check", 1, wakeChecks.get(id)!!.reRings)

        wakeCheckController.onPrompt(id)
        assertNull("limit reached: no more ringing", ring.onWakeCheckDeadline(id))
        assertNull(wakeChecks.get(id))
        assertEquals(WakeCheckStatus.FAILED, history.get(session.eventId)!!.wakeCheck)
    }

    @Test fun explicitlyCancellingAWakeCheckLeavesNothingScheduled() = runBlocking {
        val (id, session) = startRing(wakeCheckAlarm())
        ring.dismiss()
        wakeCheckController.abandon(id, WakeCheckStatus.CANCELLED)
        assertNull(wakeChecks.get(id))
        assertFalse(pending.isScheduled(TriggerKind.WAKE_CHECK_PROMPT, id))
        assertEquals(WakeCheckStatus.CANCELLED, history.get(session.eventId)!!.wakeCheck)
    }
}
