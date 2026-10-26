package com.riyadm.wakenudge.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.usecase.ActionResult
import com.riyadm.wakenudge.domain.usecase.AlarmActions
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
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
 * Bugs found by reading the engine as a user would break it, each pinned against the real AlarmManager and Room:
 * a force-stop that wipes every alarm, a group switched back on through one of its alarms, a double "skip next", and
 * a schedule change that should replace a pending skip.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AuditFixesInstrumentedTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var alarms: AlarmRepository
    @Inject lateinit var groups: GroupRepository
    @Inject lateinit var history: HistoryRepository
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var actions: AlarmActions
    @Inject lateinit var pending: PendingAlarms
    @Inject lateinit var runtime: RuntimeState
    @Inject lateinit var repair: StartupRepair
    @Inject lateinit var time: TimeSource
    @Inject lateinit var store: RingSessionStore
    @Inject lateinit var recovery: MissedAlarmRecovery
    @Inject lateinit var ring: RingController
    @Inject lateinit var starter: com.riyadm.wakenudge.RecordingStarter
    @Inject lateinit var wakeCheckRepo: com.riyadm.wakenudge.domain.repository.WakeCheckRepository
    @Inject lateinit var wakeCheckController: WakeCheckController

    @Before fun setUp() = hilt.inject()

    @After fun tearDown() = runBlocking {
        starter.requests.clear()
        val ids = alarms.getAlarms().map { it.id } + history.recent(500).map { it.alarmId }
        for (id in ids.toSet()) {
            scheduler.cancel(id)
            for (kind in TriggerKind.entries) for (i in 0..3) pending.cancel(kind, id, i)
        }
        store.set(null)
    }

    private fun alarmIn(minutesAhead: Long, block: Alarm.() -> Alarm = { this }): Alarm {
        val at = time.now().plusMinutes(minutesAhead)
        return Alarm(label = "audit", hour = at.hour, minute = at.minute).block()
    }

    /** What Android does on a force-stop: every alarm and pending intent of the app is gone, and nobody is told. */
    private fun forceStop(alarmIds: List<Long>) {
        for (id in alarmIds) for (kind in TriggerKind.entries) for (i in 0..3) pending.cancel(kind, id, i)
        pending.cancel(TriggerKind.WATCHDOG, 0)
        pending.cancel(TriggerKind.BEDTIME, 0)
        pending.cancel(TriggerKind.BATTERY_CHECK, 0)
    }

    // ---- force-stop ------------------------------------------------------------------------------------------

    @Test fun aForceStopThatWipedEveryAlarmIsRepairedTheNextTimeTheAppStarts() = runBlocking {
        val daily = alarms.save(alarmIn(120) { copy(repeatDaysMask = Alarm.EVERY_DAY_MASK) })
        val once = alarms.save(alarmIn(300))
        scheduler.rescheduleAll("test")
        assertTrue(pending.isScheduled(TriggerKind.ALARM, daily) && pending.isScheduled(TriggerKind.ALARM, once))

        forceStop(listOf(daily, once))
        assertFalse("the force-stop really removed them", pending.isScheduled(TriggerKind.ALARM, daily))

        assertTrue("the start noticed", repair.repairIfNeeded())
        assertTrue(pending.isScheduled(TriggerKind.ALARM, daily))
        assertTrue(pending.isScheduled(TriggerKind.ALARM, once))
        assertTrue(pending.isScheduled(TriggerKind.WATCHDOG, 0))
        assertFalse("nothing to do on the next start", repair.repairIfNeeded())
    }

    @Test fun oneAlarmThatAndroidLostIsAlsoPutBack() = runBlocking {
        val id = alarms.save(alarmIn(90))
        scheduler.rescheduleAll("test")
        pending.cancel(TriggerKind.ALARM, id)
        assertTrue(repair.repairIfNeeded())
        assertTrue(pending.isScheduled(TriggerKind.ALARM, id))
    }

    // ---- groups ----------------------------------------------------------------------------------------------

    @Test fun switchingOnOneAlarmOfASwitchedOffGroupArmsTheGroupsOtherAlarmsToo() = runBlocking {
        val work = groups.save(AlarmGroup(name = "Work", enabled = false))
        val early = alarms.save(alarmIn(60) { copy(groupId = work) })
        val tapped = alarms.save(alarmIn(120) { copy(groupId = work, enabled = false) })
        scheduler.rescheduleAll("test")
        assertFalse("group off: nothing armed", pending.isScheduled(TriggerKind.ALARM, early))

        val result = actions.setEnabled(tapped, true)
        assertEquals("the caller is told the group came back", "Work", (result as ActionResult.Done).value)
        assertTrue(groups.getGroups().single().enabled)
        assertTrue("the tapped alarm", pending.isScheduled(TriggerKind.ALARM, tapped))
        assertTrue("and the group's other alarm, which the list now shows as due", pending.isScheduled(TriggerKind.ALARM, early))
    }

    @Test fun switchingOnAnUngroupedAlarmReportsNoGroup() = runBlocking {
        val id = alarms.save(alarmIn(60) { copy(enabled = false) })
        assertNull((actions.setEnabled(id, true) as ActionResult.Done).value)
        assertTrue(pending.isScheduled(TriggerKind.ALARM, id))
    }

    // ---- skip next -------------------------------------------------------------------------------------------

    @Test fun skippingTheSameRingTwiceDoesNotSkipTheOneAfterIt() = runBlocking {
        val id = alarms.save(alarmIn(60) { copy(repeatDaysMask = Alarm.EVERY_DAY_MASK) })
        scheduler.schedule(id)
        val today = scheduler.nextTriggerOf(id)!!
        // A widget or a heads-up shows "Skip" for today's ring; the user taps it twice.
        actions.skipNext(id, occurrenceAt = today)
        val afterFirst = scheduler.nextTriggerOf(id)!!
        actions.skipNext(id, occurrenceAt = today)
        assertEquals("the second tap changes nothing", afterFirst, scheduler.nextTriggerOf(id))
        assertEquals(Instant.ofEpochMilli(today).atZone(time.zone()).toLocalDate().toEpochDay(), alarms.getAlarm(id)!!.skipEpochDay)
    }

    @Test fun movingTheTimeReplacesAPendingSkipButFixingTheLabelKeepsIt() = runBlocking {
        val id = alarms.save(alarmIn(60) { copy(repeatDaysMask = Alarm.EVERY_DAY_MASK) })
        scheduler.schedule(id)
        val today = scheduler.nextTriggerOf(id)!!
        actions.skipNext(id, occurrenceAt = today)

        val skipped = alarms.getAlarm(id)!!
        actions.save(skipped.copy(label = "Gym"))
        assertNotNull("a label fix keeps the skip", alarms.getAlarm(id)!!.skipEpochDay)

        // "I'll sleep in": the user moves the alarm an hour later. It must still ring today, at the new time.
        val later = alarms.getAlarm(id)!!.let { it.copy(hour = (it.hour + 1) % 24) }
        actions.save(later)
        assertNull(alarms.getAlarm(id)!!.skipEpochDay)
        val next = scheduler.nextTriggerOf(id)!!
        assertTrue("rings within about two hours, not tomorrow", next - time.nowMillis() < 2 * 3_600_000L + 60_000L)
    }

    @Test fun deletingASwitchedOffGroupKeepsItsAlarmsSilent() = runBlocking {
        val nights = groups.save(AlarmGroup(name = "Night shift", enabled = false))
        val id = alarms.save(alarmIn(90) { copy(groupId = nights) })
        scheduler.rescheduleAll("test")
        assertFalse(pending.isScheduled(TriggerKind.ALARM, id))

        groups.delete(nights)
        scheduler.rescheduleAll("group-delete")
        val alarm = alarms.getAlarm(id)!!
        assertNull("ungrouped", alarm.groupId)
        assertFalse("but still off: it was silent because of its group, and nobody asked for it to ring", alarm.enabled)
        assertFalse(pending.isScheduled(TriggerKind.ALARM, id))
    }

    @Test fun deletingASwitchedOnGroupLeavesItsAlarmsAsTheyWere() = runBlocking {
        val work = groups.save(AlarmGroup(name = "Work", enabled = true))
        val id = alarms.save(alarmIn(90) { copy(groupId = work) })
        groups.delete(work)
        assertTrue(alarms.getAlarm(id)!!.enabled)
    }

    // ---- reboot ----------------------------------------------------------------------------------------------

    @Test fun anAlarmDueASecondBeforeTheRescheduleAfterABootStillRings() = runBlocking {
        val id = alarms.save(alarmIn(0))
        // The reschedule after the boot read the clock just after this alarm's time and moved it to tomorrow; the
        // recovery that follows must count it as overdue, not as "due a moment ago, the OS will deliver it".
        val dueAt = time.nowMillis() - 1_000L
        recovery.process(mapOf(id to dueAt), dueBy = time.nowMillis())
        assertEquals(id, starter.requests.single().alarmId)
    }

    // ---- fired follow-ups ------------------------------------------------------------------------------------

    @Test fun aSnoozeThatHasFiredNoLongerCountsAsPending() = runBlocking {
        val id = alarms.save(alarmIn(60) { copy(repeatDaysMask = Alarm.EVERY_DAY_MASK) })
        val eventId = history.start(com.riyadm.wakenudge.domain.model.AlarmEvent(alarmId = id, label = "", scheduledAt = time.nowMillis(), firedAt = time.nowMillis()))
        pending.setAlarmClock(TriggerKind.SNOOZE, id, time.nowMillis() + 60_000, eventId = eventId)
        assertTrue(pending.isScheduled(TriggerKind.SNOOZE, id))
        // The snooze period ends: its trigger is handled.
        assertNotNull(ring.onSnoozeExpired(id, eventId))
        assertFalse("nothing (edit lock, missed notice) may go on thinking a snooze is pending", pending.isScheduled(TriggerKind.SNOOZE, id))
    }

    // ---- found by the review of these fixes ----------------------------------------------------------------------

    @Test fun anAlarmDueWhileNothingWasArmedIsRungByTheStartUpRepairToo() = runBlocking {
        // A boot (or force-stop) wiped everything; the start-up pass may run before the boot broadcast reads the
        // armed times, so it looks for overdue alarms itself.
        val id = alarms.save(alarmIn(0))
        runtime.setRegisteredTriggers(mapOf(id to time.nowMillis() - 60_000L))
        forceStop(listOf(id))
        assertTrue(repair.repairIfNeeded())
        assertEquals("the alarm due a minute ago rings", id, starter.requests.single().alarmId)
    }

    @Test fun aDeleteAfterUseAlarmTheUserTouchedDuringItsCheckIsNotDeleted() = runBlocking {
        val id = alarms.save(alarmIn(60) { copy(deleteAfterUse = true, enabled = false, wakeCheck = com.riyadm.wakenudge.domain.model.WakeCheckSettings(enabled = true)) })
        wakeCheckRepo.save(com.riyadm.wakenudge.domain.model.WakeCheck(id, 1, com.riyadm.wakenudge.domain.model.WakeCheck.Phase.WAITING, time.nowMillis() + 60_000, 0))
        // Switched on, then off again: the user's decision, not the end of the check.
        scheduler.cancelFollowUps(id)
        assertNotNull(alarms.getAlarm(id))

        wakeCheckRepo.save(com.riyadm.wakenudge.domain.model.WakeCheck(id, 1, com.riyadm.wakenudge.domain.model.WakeCheck.Phase.WAITING, time.nowMillis() + 60_000, 0))
        // Cancelled from the check's own prompt: the check is over, and the used alarm goes.
        wakeCheckController.cancel(id)
        assertNull(alarms.getAlarm(id))
    }

    @Test fun reRingsCarryOverOnlyWithinTheSameRing() = runBlocking {
        val id = alarms.save(alarmIn(60))
        wakeCheckRepo.save(com.riyadm.wakenudge.domain.model.WakeCheck(id, 7, com.riyadm.wakenudge.domain.model.WakeCheck.Phase.PROMPTED, 0, 0, reRings = 2))
        assertEquals(2, wakeCheckController.reRingsFor(id, 7))
        assertEquals("a check left over from yesterday's ring does not count today", 0, wakeCheckController.reRingsFor(id, 8))
        wakeCheckRepo.delete(id)
    }

    @Test fun aSkipForARingThatHasAlreadyPassedSkipsTheNextOne() = runBlocking {
        val id = alarms.save(alarmIn(60) { copy(repeatDaysMask = Alarm.EVERY_DAY_MASK) })
        scheduler.schedule(id)
        val next = scheduler.nextTriggerOf(id)!!
        // The list was drawn before this morning's ring and still names it.
        actions.skipNext(id, occurrenceAt = time.nowMillis() - 3_600_000L)
        assertEquals(Instant.ofEpochMilli(next).atZone(time.zone()).toLocalDate().toEpochDay(), alarms.getAlarm(id)!!.skipEpochDay)
    }
}
