package com.riyadm.wakenudge.domain

import com.riyadm.wakenudge.data.backup.BackupManager
import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.ExceptionDate
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.model.VolumeKeyAction
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.EditLock
import com.riyadm.wakenudge.domain.scheduling.NextAlarm
import com.riyadm.wakenudge.domain.usecase.ActionResult
import com.riyadm.wakenudge.domain.usecase.ActiveRingQuery
import com.riyadm.wakenudge.domain.usecase.AlarmActions
import com.riyadm.wakenudge.domain.usecase.GlobalChanges
import com.riyadm.wakenudge.engine.MemoryAlarms
import com.riyadm.wakenudge.engine.MemorySettings
import com.riyadm.wakenudge.engine.TestClock
import com.riyadm.wakenudge.tile.toggleVacation
import com.riyadm.wakenudge.ui.alarms.AlarmsViewModel
import com.riyadm.wakenudge.ui.settings.ExceptionsViewModel
import com.riyadm.wakenudge.ui.settings.GroupsViewModel
import com.riyadm.wakenudge.ui.settings.SettingsViewModel
import com.riyadm.wakenudge.widget.SurfaceEntryPoint
import io.mockk.every
import io.mockk.mockk
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 5's acceptance on the JVM: a Hard alarm at 23:30 for a 07:00 ring cannot be defused through any side door, and
 * every user-reachable change to alarms, groups, vacation and exceptions is classified, so a new one has to be too.
 */
class LockCoverageTest {

    private val clock = TestClock(Instant.parse("2026-11-09T23:30:00Z").toEpochMilli())
    private val sevenAm = Instant.parse("2026-11-10T07:00:00Z").toEpochMilli()
    private val tomorrow = Instant.ofEpochMilli(sevenAm).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()

    /** Stamps `updatedAt` on every write, as Room does, so the grace after a save can be tested. */
    private val alarms = object : AlarmRepository {
        val inner = MemoryAlarms()
        override fun observeAlarms(): Flow<List<Alarm>> = inner.observeAlarms()
        override fun observeAlarm(id: Long): Flow<Alarm?> = inner.observeAlarm(id)
        override suspend fun getAlarms(): List<Alarm> = inner.getAlarms()
        override suspend fun getAlarm(id: Long): Alarm? = inner.getAlarm(id)
        override suspend fun save(alarm: Alarm): Long = inner.save(alarm.copy(updatedAt = clock.now))
        override suspend fun delete(id: Long) = inner.delete(id)
        override suspend fun setEnabled(id: Long, enabled: Boolean) {
            inner.setEnabled(id, enabled)
            inner.rows[id]?.let { inner.rows[id] = it.copy(updatedAt = clock.now) }
        }
        override suspend fun setSkipDay(id: Long, epochDay: Long?) {
            inner.setSkipDay(id, epochDay)
            inner.rows[id]?.let { inner.rows[id] = it.copy(updatedAt = clock.now) }
        }
        override suspend fun replaceAll(alarms: List<Alarm>) = inner.replaceAll(alarms)
    }

    private val groups = object : GroupRepository {
        val rows = LinkedHashMap<Long, AlarmGroup>()
        override fun observeGroups(): Flow<List<AlarmGroup>> = flowOf(rows.values.toList())
        override suspend fun getGroups(): List<AlarmGroup> = rows.values.toList()
        override suspend fun save(group: AlarmGroup): Long { rows[group.id] = group; return group.id }
        override suspend fun delete(id: Long) { rows.remove(id) }
        override suspend fun setEnabled(id: Long, enabled: Boolean) { rows[id]?.let { rows[id] = it.copy(enabled = enabled) } }
        override suspend fun replaceAll(groups: List<AlarmGroup>) { rows.clear(); groups.forEach { rows[it.id] = it } }
    }

    private val exceptions = object : ExceptionRepository {
        val rows = LinkedHashMap<Long, ExceptionDate>()
        override fun observeDates(): Flow<List<ExceptionDate>> = flowOf(rows.values.toList())
        override suspend fun getDates(): List<ExceptionDate> = rows.values.toList()
        override suspend fun add(date: ExceptionDate) { rows[date.epochDay] = date }
        override suspend fun remove(epochDay: Long) { rows.remove(epochDay) }
        override suspend fun pruneBefore(epochDay: Long) = Unit
        override suspend fun replaceAll(dates: List<ExceptionDate>) = Unit
    }

    private val scheduler = object : AlarmScheduler {
        val next = HashMap<Long, Long>()
        val cancelled = mutableListOf<Long>()
        var reschedules = 0
        override suspend fun rescheduleAll(reason: String) { reschedules++ }
        override suspend fun schedule(alarmId: Long, afterMillis: Long) = Unit
        override suspend fun rearm(alarmId: Long, atMillis: Long) = true
        override suspend fun cancel(alarmId: Long, byUser: Boolean) { cancelled += alarmId }
        override suspend fun cancelFollowUps(alarmId: Long, byUser: Boolean) { cancelled += alarmId }
        override suspend fun nextAlarm(): NextAlarm? = null
        override suspend fun nextTriggerOf(alarmId: Long): Long? = next[alarmId]
        override suspend fun refreshBedtime() = Unit
        override suspend fun scheduleTestAlarm(delaySeconds: Int): Long = 0
    }

    private val ringing = HashSet<Long>()
    private val ringQuery = object : ActiveRingQuery {
        override suspend fun isActiveOrSnoozed(alarmId: Long) = alarmId in ringing
    }
    private val settings = MemorySettings(AppSettings())
    private val actions = AlarmActions(alarms, groups, scheduler, CountingSurfaces(), clock, ringQuery, SilentLog())
    private val global = GlobalChanges(actions, alarms, groups, exceptions, settings, scheduler, clock)

    private val work = AlarmGroup(id = 5, name = "Work")

    /** Hard: a mission, an 8-hour window, saved last week. */
    private fun hard(window: Int = 480, missions: Boolean = true): Alarm {
        runBlocking { groups.save(work) }
        val a = runBlocking {
            val id = alarms.save(
                Alarm(
                    hour = 7, repeatDaysMask = Alarm.EVERY_DAY_MASK, groupId = work.id,
                    missions = if (missions) listOf(MissionConfig(MissionType.MATH)) else emptyList(),
                    antiOversleep = AntiOversleepSettings(editLockMinutes = window, lockOverrideMission = true),
                ),
            )
            alarms.getAlarm(id)!!
        }
        // Saved a week ago, so the grace after a save is long over.
        alarms.inner.rows[a.id] = a.copy(updatedAt = clock.now - 7 * 86_400_000L)
        scheduler.next[a.id] = sevenAm
        return alarms.inner.rows[a.id]!!
    }

    private fun <T> ActionResult<T>.locked(): EditLock = (this as? ActionResult.Locked)?.lock ?: fail("expected a refusal, got $this") as Nothing

    // ---- the policy ---------------------------------------------------------------------------------------------

    @Test fun `windows reach 12 hours and say how much is left`() = runBlocking {
        val a = hard(window = 720)
        assertEquals(EditLock(450, whileRinging = false), actions.lockOf(a))
        clock.now = sevenAm - 13 * 3_600_000L
        assertNull("outside the window everything works", actions.lockOf(a))
    }

    @Test fun `a ring or snooze of an alarm with a mission is locked even with no window, and nothing lifts it`() = runBlocking {
        val a = hard(window = 0)
        assertNull(actions.lockOf(a))
        ringing += a.id
        val lock = actions.lockOf(a)!!
        assertTrue(lock.whileRinging)
        assertFalse(lock.canOverride)
        // Proving to be awake lifts a window, never a ring or a snooze.
        actions.delete(a.id, overrideLock = true).locked()
        actions.setEnabled(a.id, false, overrideLock = true).locked()
        actions.save(a.copy(hour = 9), overrideLock = true).locked()
        actions.skipNext(a.id, overrideLock = true).locked()
        assertNotNull(alarms.getAlarm(a.id))
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test fun `a ringing alarm without a mission or window is not locked, as before`() = runBlocking {
        val a = hard(window = 0, missions = false)
        ringing += a.id
        assertNull(actions.lockOf(a))
    }

    @Test fun `a just-saved alarm stays editable for ten minutes, then locks`() = runBlocking {
        val a = hard()
        alarms.save(a.copy(label = "typo"))
        assertNull(actions.lockOf(a))
        clock.now += 9 * 60_000L
        assertNull(actions.lockOf(a))
        clock.now += 61_000L
        assertNotNull(actions.lockOf(a))
    }

    @Test fun `switching on an alarm that is on, or clearing a skip that is not there, does not open the grace`() = runBlocking {
        val a = hard()
        assertTrue(actions.setEnabled(a.id, true) is ActionResult.Done)
        actions.clearSkip(a.id)
        assertNotNull("no write happened, so the lock stands", actions.lockOf(a))
    }

    @Test fun `the window overrides with proof, outside a ring`() = runBlocking {
        val a = hard()
        assertTrue(actions.setEnabled(a.id, false, overrideLock = true) is ActionResult.Done)
    }

    // ---- every side door at 23:30 for 07:00 -------------------------------------------------------------------

    @Test fun `at 23 30 for a 07 00 Hard alarm every side door is refused with the same lock`() = runBlocking {
        val a = hard()
        val expected = actions.lockOf(a)!!
        val refusals = listOf(
            "edit" to actions.save(a.copy(hour = 9)),
            "delete" to actions.delete(a.id),
            "disable" to actions.setEnabled(a.id, false),
            "skip" to actions.skipNext(a.id),
            "vacation" to global.setVacation(true, null),
            "vacation, tile" to global.replaceVacation(VacationMode(enabled = true)),
            "group off" to global.setGroupEnabled(work.id, false),
            "group delete" to global.deleteGroup(work.id),
            "profile" to run { groups.save(AlarmGroup(id = 6, name = "Weekend", enabled = false)); global.activateProfileOnly(6) },
            "exception day" to global.addExceptionDate(tomorrow, "lie-in"),
            "calendar exceptions" to global.setCalendarExceptions(true),
        )
        for ((door, result) in refusals) assertEquals(door, expected, result.locked())
        assertEquals("restore", expected, global.restoreLock())
        // Nothing changed on the way.
        assertEquals(a, alarms.getAlarm(a.id))
        assertFalse(settings.current().vacation.enabled)
        assertTrue(groups.getGroups().first { it.id == work.id }.enabled)
        assertTrue(exceptions.rows.isEmpty())
        assertFalse(settings.current().calendarExceptions.enabled)
        assertEquals(VolumeKeyAction.SNOOZE, settings.current().volumeKeyAction)
        assertEquals(0, scheduler.reschedules)
    }

    @Test fun `the tile's pause is refused the same way`() = runBlocking {
        val a = hard()
        val entry = mockk<SurfaceEntryPoint> {
            every { time() } returns clock
            every { settings() } returns settings
            every { globalChanges() } returns global
            every { surfaces() } returns CountingSurfaces()
        }
        assertEquals(actions.lockOf(a), toggleVacation(entry))
        assertFalse(settings.current().vacation.enabled)
    }

    @Test fun `before the window opens every side door works`() = runBlocking {
        val a = hard()
        clock.now = sevenAm - 9 * 3_600_000L
        assertTrue(global.setVacation(true, null) is ActionResult.Done)
        assertTrue(global.setVacation(false, null) is ActionResult.Done)
        assertTrue(global.setGroupEnabled(work.id, false) is ActionResult.Done)
        assertTrue(global.setGroupEnabled(work.id, true) is ActionResult.Done)
        assertTrue(global.addExceptionDate(tomorrow, "lie-in") is ActionResult.Done)
        assertTrue(global.setCalendarExceptions(true) is ActionResult.Done)
        assertNull(global.restoreLock())
        assertTrue(actions.skipNext(a.id) is ActionResult.Done)
        assertTrue(actions.setEnabled(a.id, false) is ActionResult.Done)
    }

    @Test fun `changes that only bring alarms back are never refused`() = runBlocking {
        val a = hard()
        settings.update { it.copy(vacation = VacationMode(enabled = true, resumeEpochDay = tomorrow + 3)) }
        groups.setEnabled(work.id, true)
        assertTrue("ending a vacation", global.setVacation(false, null) is ActionResult.Done)
        assertTrue("switching a group on", global.setGroupEnabled(work.id, true) is ActionResult.Done)
        exceptions.add(ExceptionDate(tomorrow, "x"))
        global.removeExceptionDate(tomorrow)
        assertTrue(exceptions.rows.isEmpty())
        assertTrue("calendar off", global.setCalendarExceptions(false) is ActionResult.Done)
        assertTrue("switching an alarm on", actions.setEnabled(a.id, true) is ActionResult.Done)
    }

    @Test fun `an exception day that does not touch the locked ring is allowed`() = runBlocking {
        hard()
        assertTrue(global.addExceptionDate(tomorrow + 5, "trip") is ActionResult.Done)
    }

    @Test fun `a longer vacation is refused, a shorter one is not`() = runBlocking {
        hard()
        clock.now = sevenAm - 9 * 3_600_000L
        assertTrue(global.setVacation(true, tomorrow + 2) is ActionResult.Done)
        clock.now = sevenAm - 3_600_000L
        global.setVacation(true, tomorrow + 9).locked()
        assertTrue(global.setVacation(true, tomorrow + 1) is ActionResult.Done)
    }

    @Test fun `a ringing alarm without a mission does not block the others`() = runBlocking {
        val plain = hard(window = 0, missions = false)
        ringing += plain.id
        assertTrue(global.setVacation(true, null) is ActionResult.Done)
    }

    @Test fun `a restore is refused while an alarm is locked, before the file is even read`() = runBlocking {
        val rig = com.riyadm.wakenudge.data.backup.BackupRig()
        rig.lock = EditLock(450, whileRinging = false)
        val result = rig.manager.import("not even json") as com.riyadm.wakenudge.data.backup.ImportResult.Failure
        assertEquals(com.riyadm.wakenudge.data.backup.ImportFailure.Locked(EditLock(450, false)), result.reason)
        assertTrue(rig.scheduler.reasons.isEmpty())
    }

    // ---- classification -----------------------------------------------------------------------------------------

    private enum class Kind {
        /** Can stop or soften an alarm: asks the lock first (tested above). */
        GUARDED,

        /** Only brings alarms back or adds one: never refused. */
        STRENGTHENS,

        /** Changes no alarm, group, vacation or exception. */
        NONE,
    }

    /**
     * Every public method of the classes through which the user changes alarms, groups, vacation and exception days.
     * A new one fails this test until it is classified here, and a GUARDED one needs a refusal in the test above.
     */
    private val classified: Map<Class<*>, Map<String, Kind>> = mapOf(
        AlarmActions::class.java to mapOf(
            "save" to Kind.GUARDED, "setEnabled" to Kind.GUARDED, "delete" to Kind.GUARDED, "skipNext" to Kind.GUARDED,
            "restore" to Kind.STRENGTHENS, "clearSkip" to Kind.STRENGTHENS, "duplicate" to Kind.STRENGTHENS,
            "lockOf" to Kind.NONE, "lockedMinutes" to Kind.NONE, "guardGlobalChange" to Kind.NONE,
        ),
        GlobalChanges::class.java to mapOf(
            "setVacation" to Kind.GUARDED, "replaceVacation" to Kind.GUARDED, "setGroupEnabled" to Kind.GUARDED,
            "activateProfileOnly" to Kind.GUARDED, "restoreGroupStates" to Kind.GUARDED, "deleteGroup" to Kind.GUARDED,
            "addExceptionDate" to Kind.GUARDED, "setCalendarExceptions" to Kind.GUARDED, "selectCalendar" to Kind.GUARDED,
            "restoreLock" to Kind.NONE, "removeExceptionDate" to Kind.STRENGTHENS,
        ),
        // The view models only pass on to the two above; listed so a new button that writes directly is noticed.
        AlarmsViewModel::class.java to mapOf(
            "toggle" to Kind.GUARDED, "delete" to Kind.GUARDED, "skipNext" to Kind.GUARDED, "setGroupEnabled" to Kind.GUARDED,
            "activateProfileOnly" to Kind.GUARDED, "undoProfileOnly" to Kind.GUARDED, "setVacation" to Kind.GUARDED,
            "undoPause" to Kind.GUARDED, "undoDelete" to Kind.STRENGTHENS, "clearSkip" to Kind.STRENGTHENS,
            "duplicate" to Kind.STRENGTHENS, "setFilter" to Kind.NONE, "getState" to Kind.NONE, "getEvents" to Kind.NONE,
            // Read-only look at the next alarm, and a flag for a one-time card: neither changes an alarm.
            "refreshReadiness" to Kind.NONE, "getReadiness" to Kind.NONE, "dismissLevelsCard" to Kind.NONE,
        ),
        SettingsViewModel::class.java to mapOf(
            "setVacation" to Kind.GUARDED,
            // The general settings write and the new-alarm defaults change no existing alarm.
            "update" to Kind.NONE, "updateDefaults" to Kind.NONE, "setDefaultLevel" to Kind.NONE,
            "getSettings" to Kind.NONE, "getDefaultsAlarm" to Kind.NONE, "getDefaultLevel" to Kind.NONE, "getLockNotices" to Kind.NONE,
            "getSoundPreviewer" to Kind.NONE, "getVibrator" to Kind.NONE,
        ),
        GroupsViewModel::class.java to mapOf(
            "setEnabled" to Kind.GUARDED, "delete" to Kind.GUARDED, "create" to Kind.NONE, "rename" to Kind.NONE,
            "getState" to Kind.NONE, "getLockNotices" to Kind.NONE,
        ),
        ExceptionsViewModel::class.java to mapOf(
            "setVacation" to Kind.GUARDED, "addDate" to Kind.GUARDED, "setCalendarEnabled" to Kind.GUARDED,
            "toggleCalendar" to Kind.GUARDED, "removeDate" to Kind.STRENGTHENS, "onPermissionResult" to Kind.NONE,
            "getState" to Kind.NONE, "getLockNotices" to Kind.NONE,
        ),
        BackupManager::class.java to mapOf(
            "import" to Kind.GUARDED, "importFrom" to Kind.GUARDED,
            "export" to Kind.NONE, "exportTo" to Kind.NONE, "writeTo" to Kind.NONE, "readFrom" to Kind.NONE, "suggestedFileName" to Kind.NONE,
        ),
    )

    @Test fun `every user-reachable change to alarms, groups, vacation and exceptions is classified`() {
        for ((type, kinds) in classified) {
            val public = type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge && '$' !in it.name }
                .map { it.name }
                .toSet()
            val unclassified = public - kinds.keys
            assertTrue("${type.simpleName}: classify ${unclassified.sorted()} in LockCoverageTest", unclassified.isEmpty())
            val gone = kinds.keys - public
            assertTrue("${type.simpleName}: ${gone.sorted()} no longer exist; remove them here", gone.isEmpty())
        }
    }
}
