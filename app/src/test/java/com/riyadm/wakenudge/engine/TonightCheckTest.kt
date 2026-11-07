package com.riyadm.wakenudge.engine

import android.app.NotificationManager
import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.NextAlarm
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.health.HealthChecker
import com.riyadm.wakenudge.health.HealthFixKind
import com.riyadm.wakenudge.health.HealthSnapshot
import com.riyadm.wakenudge.missions.MissionProbe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOUR = 3_600_000L

class TonightCheckPolicyTest {
    private val now = 1_800_000_000_000L
    private val at = now + 8 * HOUR
    private val fine = HealthSnapshot(nowMillis = now)
    private val qr = MissionConfig(MissionType.QR_BARCODE, params = mapOf(MissionConfig.PARAM_CODE to "abc"))
    private val nfc = MissionConfig(MissionType.NFC, params = mapOf(MissionConfig.PARAM_TAG_ID to "04aa"))
    private val photo = MissionConfig(MissionType.PHOTO, params = mapOf(MissionConfig.PARAM_SIGNATURE to "sig"))

    private fun problems(
        snapshot: HealthSnapshot = fine,
        missions: List<MissionConfig> = emptyList(),
        armed: Boolean = true,
        battery: BatteryReading? = BatteryReading(80, charging = false),
        readiness: (MissionType) -> MissionReadiness = { MissionReadiness() },
    ) = TonightCheckPolicy.problems(snapshot, Alarm(id = 1, missions = missions, hour = 7, minute = 0), at, armed, battery, readiness)

    @Test fun `nothing wrong finds nothing`() {
        assertEquals(emptyList<TonightProblem>(), problems())
        assertEquals(emptyList<TonightProblem>(), problems(missions = listOf(qr, nfc, photo, MissionConfig(MissionType.MATH), MissionConfig(MissionType.STEPS))))
    }

    @Test fun `each problem with the phone is found, with the screen that fixes it`() {
        val cases = mapOf(
            fine.copy(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE) to TonightProblem(TonightProblemKind.SILENCED_BY_DND, fix = HealthFixKind.DND_SETTINGS),
            fine.copy(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY, dndAllowsAlarms = false) to TonightProblem(TonightProblemKind.SILENCED_BY_DND, fix = HealthFixKind.DND_SETTINGS),
            fine.copy(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY, alarmStreamMuted = true) to TonightProblem(TonightProblemKind.SILENCED_BY_DND, fix = HealthFixKind.DND_SETTINGS),
            fine.copy(canScheduleExactAlarms = false) to TonightProblem(TonightProblemKind.EXACT_ALARMS_OFF, fix = HealthFixKind.EXACT_ALARMS),
            fine.copy(postNotificationsGranted = false) to TonightProblem(TonightProblemKind.NOTIFICATIONS_OFF, fix = HealthFixKind.APP_NOTIFICATIONS),
            fine.copy(notificationsEnabled = false) to TonightProblem(TonightProblemKind.NOTIFICATIONS_OFF, fix = HealthFixKind.APP_NOTIFICATIONS),
            fine.copy(ringChannelImportance = NotificationManager.IMPORTANCE_NONE) to TonightProblem(TonightProblemKind.NOTIFICATIONS_OFF, fix = HealthFixKind.RING_CHANNEL),
            fine.copy(fullScreenIntentAllowed = false) to TonightProblem(TonightProblemKind.FULL_SCREEN_OFF, fix = HealthFixKind.FULL_SCREEN_INTENT),
            fine.copy(backgroundRestricted = true) to TonightProblem(TonightProblemKind.BACKGROUND_RESTRICTED, fix = HealthFixKind.APP_DETAILS),
        )
        for ((snapshot, expected) in cases) assertEquals(snapshot.toString(), listOf(expected), problems(snapshot))
    }

    @Test fun `things that do not stop an alarm are not reported`() {
        assertEquals(emptyList<TonightProblem>(), problems(fine.copy(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS)))
        assertEquals("battery optimisation does not delay an alarm clock", emptyList<TonightProblem>(), problems(fine.copy(ignoringBatteryOptimizations = false)))
        assertEquals("a channel not created yet is created when it rings", emptyList<TonightProblem>(), problems(fine.copy(ringChannelImportance = null)))
    }

    @Test fun `a missing trigger and a low battery are found`() {
        assertEquals(listOf(TonightProblem(TonightProblemKind.TRIGGER_MISSING)), problems(armed = false))
        assertEquals(listOf(TonightProblem(TonightProblemKind.LOW_BATTERY)), problems(battery = BatteryReading(15, charging = false)))
        assertEquals(emptyList<TonightProblem>(), problems(battery = BatteryReading(15, charging = true)))
    }

    @Test fun `every mission an alarm has is checked, not only the one the morning will pick`() {
        val rotation = listOf(MissionConfig(MissionType.MATH), qr, nfc, photo)
        for (mission in rotation.filter { it.type != MissionType.MATH }) {
            val broken = problems(missions = rotation) { if (it == mission.type) MissionReadiness(permissionMissing = it != MissionType.NFC, nfcOff = it == MissionType.NFC) else MissionReadiness() }
            assertEquals(mission.type.name, listOf(mission.type), broken.map { it.mission })
        }
    }

    @Test fun `each problem with a mission is found`() {
        assertEquals(
            listOf(TonightProblem(TonightProblemKind.MISSION_NEEDS_PERMISSION, MissionType.QR_BARCODE, HealthFixKind.APP_DETAILS)),
            problems(missions = listOf(qr)) { MissionReadiness(permissionMissing = true) },
        )
        assertEquals(
            listOf(TonightProblem(TonightProblemKind.MISSION_NEEDS_NFC, MissionType.NFC, HealthFixKind.NFC_SETTINGS)),
            problems(missions = listOf(nfc)) { MissionReadiness(nfcOff = true) },
        )
        assertEquals(
            listOf(TonightProblem(TonightProblemKind.MISSION_NOT_SET_UP, MissionType.QR_BARCODE)),
            problems(missions = listOf(MissionConfig(MissionType.QR_BARCODE))),
        )
        assertEquals(
            listOf(TonightProblem(TonightProblemKind.MISSION_NO_HARDWARE, MissionType.STEPS)),
            problems(missions = listOf(MissionConfig(MissionType.STEPS))) { MissionReadiness(hardwareMissing = true) },
        )
    }

    @Test fun `two of the same mission broken the same way are one problem, and the worst comes first`() {
        val found = problems(fine.copy(fullScreenIntentAllowed = false, interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE), listOf(qr, qr.copy(params = mapOf(MissionConfig.PARAM_CODE to "x")))) {
            MissionReadiness(permissionMissing = true)
        }
        assertEquals(listOf(TonightProblemKind.SILENCED_BY_DND, TonightProblemKind.MISSION_NEEDS_PERMISSION, TonightProblemKind.FULL_SCREEN_OFF), found.map { it.kind })
    }

    @Test fun `the silent re-arm is half an hour before the alarm, and never in the past`() {
        assertEquals(at - 30 * 60_000L, TonightCheckPolicy.rearmAt(at, now))
        assertEquals(null, TonightCheckPolicy.rearmAt(now + 10 * 60_000L, now))
        assertEquals(null, TonightCheckPolicy.rearmAt(null, now))
    }
}

/** The check as it runs: reads, tells once, puts a lost trigger back, and stays out of a ring's way. */
class TonightCheckTest {
    private val clock = TestClock()
    private val notifier = mockk<Notifier>(relaxed = true)
    private val scheduler = mockk<AlarmScheduler>(relaxed = true).also { coEvery { it.rearm(any(), any()) } returns true }
    private val settings = MemorySettings()
    private val runtime = realRuntime()
    private val sessions = RingSessionStore()
    private var armed = true
    private val pending = mockk<PendingAlarms>(relaxed = true).also { every { it.isScheduled(TriggerKind.ALARM, any(), any()) } answers { armed } }
    private var snapshot = HealthSnapshot(nowMillis = clock.now)
    private val health = mockk<HealthChecker>().also { coEvery { it.snapshot() } answers { snapshot } }
    private var cameraOff = false
    private val probe = mockk<MissionProbe>().also {
        every { it.readiness(any()) } answers { MissionReadiness(permissionMissing = cameraOff && firstArg<MissionType>() == MissionType.QR_BARCODE) }
    }
    private var battery: BatteryReading? = BatteryReading(90, charging = false)
    private val check = TonightCheck(scheduler, notifier, settings, pending, sessions, runtime, clock, SilentLog(), BatteryGauge { battery }, health, probe)

    private val qrAlarm = Alarm(id = 4, label = "work", hour = 7, minute = 0, missions = listOf(MissionConfig(MissionType.QR_BARCODE, params = mapOf(MissionConfig.PARAM_CODE to "c"))))
    private var nextAt = clock.now + 8 * HOUR

    init {
        coEvery { scheduler.nextAlarm() } answers { NextAlarm(qrAlarm, Instant.ofEpochMilli(nextAt).atZone(ZoneId.of("UTC"))) }
    }

    @Test fun `everything fine means no notification at all`() = runBlocking {
        check.run()
        verify(exactly = 0) { notifier.showTonightProblem(any(), any(), any(), any()) }
        verify(exactly = 0) { notifier.showLowBattery(any(), any(), any()) }
        coVerify(exactly = 0) { scheduler.rearm(any(), any()) }
    }

    @Test fun `camera access revoked in the evening names the mission, with the button that fixes it`() = runBlocking {
        cameraOff = true
        check.run()
        verify(exactly = 1) {
            notifier.showTonightProblem(qrAlarm, nextAt, TonightProblem(TonightProblemKind.MISSION_NEEDS_PERMISSION, MissionType.QR_BARCODE, HealthFixKind.APP_DETAILS), 0)
        }
    }

    @Test fun `one notification for several problems, saying how many more there are`() = runBlocking {
        cameraOff = true
        snapshot = snapshot.copy(fullScreenIntentAllowed = false)
        battery = BatteryReading(5, charging = false)
        check.run()
        verify(exactly = 1) { notifier.showTonightProblem(any(), any(), match { it.kind == TonightProblemKind.MISSION_NEEDS_PERMISSION }, 2) }
        verify(exactly = 0) { notifier.showLowBattery(any(), any(), any()) }
    }

    @Test fun `a missing trigger is put back without telling anyone`() = runBlocking {
        armed = false
        check.run()
        coVerify(exactly = 1) { scheduler.rearm(qrAlarm.id, nextAt) }
        verify(exactly = 0) { notifier.showTonightProblem(any(), any(), any(), any()) }
        verify(exactly = 0) { notifier.showSchedulingProblem(any()) }
    }

    @Test fun `a trigger the OS will not take back is said out loud`() = runBlocking {
        armed = false
        coEvery { scheduler.rearm(any(), any()) } returns false
        check.run()
        verify { notifier.showSchedulingProblem(1) }
    }

    @Test fun `the check changes no alarm and asks the scheduler for nothing else`() = runBlocking {
        armed = false
        cameraOff = true
        check.run()
        check.rearmBeforeAlarm()
        coVerify(exactly = 0) { scheduler.schedule(any(), any()) }
        coVerify(exactly = 0) { scheduler.rescheduleAll(any()) }
        coVerify(exactly = 0) { scheduler.cancel(any()) }
        coVerify(exactly = 0) { scheduler.cancelFollowUps(any()) }
    }

    @Test fun `while a ring is on the check does nothing at all`() = runBlocking {
        sessions.set(RingSession(Alarm(id = 9), RingKind.PRIMARY, RingPhase.RINGING, 1, clock.now, clock.now))
        armed = false
        cameraOff = true
        check.run()
        check.rearmBeforeAlarm()
        coVerify(exactly = 0) { scheduler.nextAlarm() }
        coVerify(exactly = 0) { scheduler.rearm(any(), any()) }
        verify(exactly = 0) { notifier.showTonightProblem(any(), any(), any(), any()) }
    }

    @Test fun `an alarm already looked at is not looked at again, unless bedtime asks`() = runBlocking {
        cameraOff = true
        check.run()
        check.run()
        verify(exactly = 1) { notifier.showTonightProblem(any(), any(), any(), any()) }
        check.run(askedByBedtime = true)
        verify(exactly = 2) { notifier.showTonightProblem(any(), any(), any(), any()) }
    }

    @Test fun `with the warning switched off nobody is told, but a lost trigger is still put back`() = runBlocking {
        settings.replace(AppSettings(lowBatteryWarning = false))
        armed = false
        cameraOff = true
        check.run()
        coVerify { scheduler.rearm(qrAlarm.id, nextAt) }
        verify(exactly = 0) { notifier.showTonightProblem(any(), any(), any(), any()) }
    }

    @Test fun `an alarm a whole day away is not warned about yet`() = runBlocking {
        cameraOff = true
        nextAt = clock.now + 20 * HOUR
        check.run()
        verify(exactly = 0) { notifier.showTonightProblem(any(), any(), any(), any()) }
    }

    @Test fun `a check that cannot read the phone fails quietly`() = runBlocking {
        coEvery { health.snapshot() } throws IOException("database unreadable")
        check.run()
        coEvery { scheduler.nextAlarm() } throws IOException("database unreadable")
        check.run()
        check.rearmBeforeAlarm()
        verify(exactly = 0) { notifier.showTonightProblem(any(), any(), any(), any()) }
    }

    @Test fun `the re-arm before the alarm registers its trigger again and tells nobody`() = runBlocking {
        check.rearmBeforeAlarm()
        coVerify(exactly = 1) { scheduler.rearm(qrAlarm.id, nextAt) }
        verify(exactly = 0) { notifier.showTonightProblem(any(), any(), any(), any()) }
    }
}

/** How many wake-ups a night costs: one look per alarm, and one silent re-arm. */
class TonightWakeUpsTest {
    private val clock = TestClock(start = 1_800_000_000_000L - 1_800_000_000_000L % (24 * HOUR))
    private val alarms = MemoryAlarms()
    private val runtime = realRuntime()
    private val armedAt = mutableMapOf<Pair<Long, Int>, Long>()
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
        every { it.setExact(any(), any(), any(), any(), any(), any()) } returns true
        every { it.setInexact(TriggerKind.BATTERY_CHECK, 0, any(), any()) } answers { armedAt[0L to arg<Int>(3)] = arg(2); true }
        every { it.setInexact(TriggerKind.WATCHDOG, any(), any(), any()) } returns true
        every { it.cancel(TriggerKind.BATTERY_CHECK, 0, any()) } answers { armedAt.remove(0L to arg<Int>(2)); Unit }
        every { it.fallbackCount } returns 0
    }
    private val groups = mockk<GroupRepository>().also { coEvery { it.getGroups() } returns emptyList() }
    private val exceptions = mockk<ExceptionRepository>().also { coEvery { it.getDates() } returns emptyList() }
    private val settings = MemorySettings()
    private val scheduler = AlarmSchedulerImpl(
        alarms, MemoryTimers(), mockk(relaxed = true) { coEvery { getAll() } returns emptyList() }, MemoryHistory(), settings, NextTriggerCalculator(),
        SchedulingContextProvider(settings, groups, exceptions, mockk(relaxed = true), clock),
        pending, clock, runtime, CountingSurfaces(), SilentLog(), mockk(relaxed = true), RingSessionStore(), mockk<Notifier>(relaxed = true),
    )

    @Test fun `reschedules and restarts through the night do not add a second look at the same alarm`() = runBlocking {
        // 20:00, with the alarm at 07:00: looked at 23:00, re-armed 06:30.
        clock.now += 20 * HOUR
        val a = alarms.add(Alarm(label = "a", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK))
        scheduler.rescheduleAll("test")
        val alarmAt = runtime.registeredTriggers().getValue(a.id)
        assertEquals(alarmAt - 8 * HOUR, armedAt[0L to 0])
        assertEquals(alarmAt - 30 * 60_000L, armedAt[0L to TonightCheckPolicy.REARM_INDEX])
        // The look happens; then the watchdog, a reboot and another reschedule.
        clock.now = alarmAt - 8 * HOUR
        runtime.tonightCheckedFor = alarmAt
        clock.now += HOUR
        scheduler.rescheduleAll("watchdog")
        scheduler.rescheduleAll("system:boot")
        assertEquals("no second look", null, armedAt[0L to 0])
        assertEquals("the re-arm stays", alarmAt - 30 * 60_000L, armedAt[0L to TonightCheckPolicy.REARM_INDEX])
    }

    @Test fun `changing the alarm earns it a fresh look`() = runBlocking {
        clock.now += 20 * HOUR
        val a = alarms.add(Alarm(label = "a", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK))
        scheduler.rescheduleAll("test")
        runtime.tonightCheckedFor = runtime.registeredTriggers().getValue(a.id)
        scheduler.schedule(a.id)
        assertTrue(armedAt.containsKey(0L to 0))
    }

    @Test fun `re-arming registers the main trigger and nothing else, and records it`() = runBlocking {
        val a = alarms.add(Alarm(label = "a", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK))
        val at = clock.now + 5 * HOUR
        assertTrue(scheduler.rearm(a.id, at))
        verify(exactly = 1) { pending.setAlarmClock(TriggerKind.ALARM, a.id, at, any(), any(), any()) }
        verify(exactly = 0) { pending.cancel(TriggerKind.ALARM, any(), any()) }
        assertEquals(at, runtime.registeredTriggers()[a.id])
        assertEquals("the alarm itself is untouched", a, alarms.rows[a.id])
    }
}

/** Which trigger does what. */
class TonightTriggerTest {
    private val tonight = mockk<TonightCheck>(relaxed = true)
    private val settings = MemorySettings()
    private val handler = TriggerHandler(
        mockk(relaxed = true), mockk(relaxed = true), RingStarter { }, mockk<AlarmScheduler>(relaxed = true), mockk<Notifier>(relaxed = true),
        settings, realRuntime(), TestClock(), SilentLog(), tonight,
    )

    private fun intent(kind: TriggerKind, index: Int): android.content.Intent = mockk {
        every { action } returns Triggers.action(kind)
        every { getLongExtra(Triggers.EXTRA_ID, any()) } returns 0
        every { getLongExtra(Triggers.EXTRA_AT, any()) } returns 0
        every { getLongExtra(Triggers.EXTRA_EVENT_ID, any()) } returns -1
        every { getIntExtra(Triggers.EXTRA_INDEX, any()) } returns index
    }

    @Test fun `the evening trigger checks, the later one only re-arms, and the bedtime reminder checks again`() = runBlocking {
        handler.handle(intent(TriggerKind.BATTERY_CHECK, 0))
        coVerify(exactly = 1) { tonight.run(false) }
        coVerify(exactly = 0) { tonight.rearmBeforeAlarm() }
        handler.handle(intent(TriggerKind.BATTERY_CHECK, TonightCheckPolicy.REARM_INDEX))
        coVerify(exactly = 1) { tonight.rearmBeforeAlarm() }
        settings.update { it.copy(bedtime = it.bedtime.copy(enabled = true)) }
        handler.handle(intent(TriggerKind.BEDTIME, 0))
        coVerify(exactly = 1) { tonight.run(true) }
    }
}
