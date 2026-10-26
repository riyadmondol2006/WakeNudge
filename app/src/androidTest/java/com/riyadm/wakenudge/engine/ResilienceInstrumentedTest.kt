package com.riyadm.wakenudge.engine

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteFullException
import android.media.AudioAttributes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.RecordingStarter
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.repository.TimerRepository
import com.riyadm.wakenudge.domain.repository.WakeCheckRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import com.riyadm.wakenudge.domain.usecase.AlarmActions
import com.riyadm.wakenudge.engine.notify.Channels
import com.riyadm.wakenudge.engine.notify.Notifier
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

/** A history that fails the way a full disk does, on demand, while delegating everything else to the real one. */
private class FlakyHistory(private val real: HistoryRepository) : HistoryRepository by real {
    @Volatile var failing = false
    private fun check() {
        if (failing) throw SQLiteFullException("database or disk is full")
    }

    override suspend fun start(event: AlarmEvent): Long { check(); return real.start(event) }
    override suspend fun update(event: AlarmEvent) { check(); real.update(event) }
    override suspend fun get(id: Long): AlarmEvent? { check(); return real.get(id) }
    override suspend fun findOccurrence(alarmId: Long, scheduledAt: Long): AlarmEvent? { check(); return real.findOccurrence(alarmId, scheduledAt) }
    override suspend fun openEvents(): List<AlarmEvent> { check(); return real.openEvents() }
    override suspend fun withFollowUps(): List<AlarmEvent> { check(); return real.withFollowUps() }
}

/** An alarm table that cannot be read, like a database that is locked, corrupt or being rebuilt. */
private class FlakyAlarms(private val real: AlarmRepository) : AlarmRepository by real {
    @Volatile var failing = false
    private fun check() {
        if (failing) throw IOException("database unreadable")
    }

    override suspend fun getAlarm(id: Long): Alarm? { check(); return real.getAlarm(id) }
    override suspend fun getAlarms(): List<Alarm> { check(); return real.getAlarms() }
}

/** A settings store that cannot be read or written at all. */
private object BrokenSettings : SettingsRepository {
    override val settings: Flow<AppSettings> = flow { throw IOException("settings unreadable") }
    override suspend fun current(): AppSettings = throw IOException("settings unreadable")
    override suspend fun update(transform: (AppSettings) -> AppSettings): Unit = throw IOException("disk full")
    override suspend fun replace(settings: AppSettings): Unit = throw IOException("disk full")
}

/**
 * The failures a phone has at 6:59 on the worst morning: a full disk, a corrupt database, an OS that refuses to arm or
 * start things, an app that crashes mid-ring. In every case the alarm must still ring, or at least try again.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ResilienceInstrumentedTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var alarms: AlarmRepository
    @Inject lateinit var history: HistoryRepository
    @Inject lateinit var timers: TimerRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var groups: GroupRepository
    @Inject lateinit var exceptions: ExceptionRepository
    @Inject lateinit var wakeChecks: WakeCheckRepository
    @Inject lateinit var wakeCheckController: WakeCheckController
    @Inject lateinit var calculator: NextTriggerCalculator
    @Inject lateinit var calendar: CalendarHolidayProvider
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var actions: AlarmActions
    @Inject lateinit var ring: RingController
    @Inject lateinit var store: RingSessionStore
    @Inject lateinit var pending: PendingAlarms
    @Inject lateinit var runtime: RuntimeState
    @Inject lateinit var time: TimeSource
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var surfaces: SurfaceRefresher
    @Inject lateinit var diagnostics: DiagnosticsLog
    @Inject lateinit var locks: RingWakeLocks
    @Inject lateinit var handler: TriggerHandler
    @Inject lateinit var systemEvents: SystemEventHandler
    @Inject lateinit var crashGuard: RingCrashGuard
    @Inject lateinit var starter: RecordingStarter

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val knownIds = mutableSetOf(RingIds.TEST_ALARM_ID)

    @Before fun setUp() {
        hilt.inject()
        // The runtime state lives in the app's real device-protected preferences and survives between tests and runs.
        runtime.setRegisteredTriggers(emptyMap())
        runtime.clearRingCrashes()
        runtime.lastSchedulingProblem = ""
    }

    @After fun tearDown() {
        pending.faultInjector = null
        notifier.cancelSchedulingProblem()
        runtime.setRegisteredTriggers(emptyMap())
        runtime.clearRingCrashes()
        store.set(null)
        locks.releaseAll()
        notifier.cancelRing()
        // Cancel by id, never through the repositories: some tests leave the database closed.
        for (id in knownIds) for (kind in TriggerKind.entries) for (i in 0..3) if (pending.isScheduled(kind, id, i)) pending.cancel(kind, id, i)
        pending.cancel(TriggerKind.WATCHDOG, 0)
    }

    private suspend fun savedAlarm(block: Alarm.() -> Alarm = { this }): Long {
        val at = time.now().plusMinutes(90)
        val id = alarms.save(Alarm(label = "resilience", hour = at.hour, minute = at.minute, repeatDaysMask = Alarm.EVERY_DAY_MASK).block())
        knownIds += id
        return id
    }

    private fun trigger(kind: TriggerKind, id: Long, at: Long, eventId: Long = -1, index: Int = 0) =
        Intent(Triggers.action(kind)).putExtra(Triggers.EXTRA_ID, id).putExtra(Triggers.EXTRA_AT, at)
            .putExtra(Triggers.EXTRA_EVENT_ID, eventId).putExtra(Triggers.EXTRA_INDEX, index)

    private fun controllerWith(history: HistoryRepository) = RingController(
        store, alarms, history, timers, settings, scheduler, wakeCheckController, pending, runtime, time, notifier, surfaces, diagnostics,
    )

    // ---- a full disk or a failing database must not decide whether an alarm rings ---------------------------------

    @Test fun aRingStartsEvenIfTheHistoryCannotBeWritten() = runBlocking {
        val id = savedAlarm()
        val flaky = FlakyHistory(history).also { it.failing = true }
        val controller = controllerWith(flaky)

        val request = controller.onAlarmFired(id, time.nowMillis())
        assertNotNull("the alarm rings although nothing can be recorded", request)
        assertEquals("no history row could be made", -1L, request!!.eventId)
        assertTrue("the backup alarm is armed in the OS regardless", pending.isScheduled(TriggerKind.BACKUP, id, 1))

        val session = controller.begin(request)!!
        assertEquals(id, session.alarm.id)
        controller.dismiss()
        assertNull("dismissing works without a database too", store.current)
        assertFalse("and still cancels the backup", pending.isScheduled(TriggerKind.BACKUP, id, 1))
    }

    @Test fun dismissingEndsTheRingEvenWhenTheHistoryThrowsAtTheEnd() = runBlocking {
        val id = savedAlarm()
        val flaky = FlakyHistory(history)
        val controller = controllerWith(flaky)
        val session = controller.begin(controller.onAlarmFired(id, time.nowMillis())!!)!!
        assertTrue(session.eventId > 0)

        flaky.failing = true // the disk fills up while the alarm is ringing
        controller.dismiss()
        assertNull("the session ends, so the sound stops", store.current)
        assertFalse("the backup is cancelled first, it needs no database", pending.isScheduled(TriggerKind.BACKUP, id, 1))
    }

    @Test fun aSnoozeSurvivesAHistoryFailureAndStillComesBack() = runBlocking {
        val id = savedAlarm { copy(snooze = SnoozeSettings(minutes = 5, maxCount = 3)) }
        val flaky = FlakyHistory(history).also { it.failing = true }
        val controller = controllerWith(flaky)
        controller.begin(controller.onAlarmFired(id, time.nowMillis())!!)

        assertTrue(controller.snooze() is SnoozeOutcome.Snoozed)
        assertNull(store.current)
        assertTrue("the snooze trigger is armed", pending.isScheduled(TriggerKind.SNOOZE, id))

        val back = controller.onSnoozeExpired(id, -1)
        assertNotNull("a snooze set without a history row still rings when it expires", back)
        assertEquals(RingKind.SNOOZE_RETURN, back!!.kind)
    }

    /** The engine as the triggers see it, built on repositories that can be made to fail on demand. */
    private fun brokenEngine(alarmsFail: Boolean = true, historyFails: Boolean = true): Pair<RingController, TriggerHandler> {
        val flakyAlarms = FlakyAlarms(alarms).also { it.failing = alarmsFail }
        val flakyHistory = FlakyHistory(history).also { it.failing = historyFails }
        val controller = RingController(
            store, flakyAlarms, flakyHistory, timers, settings, scheduler, wakeCheckController, pending, runtime, time, notifier, surfaces, diagnostics,
        )
        val triggers = TriggerHandler(controller, wakeCheckController, starter, scheduler, notifier, settings, runtime, time, diagnostics, BatteryGauge { null })
        return controller to triggers
    }

    @Test fun anUnreadableDatabaseStillRingsADefaultAlarm() = runBlocking {
        val id = savedAlarm()
        val (controller, triggers) = brokenEngine()

        triggers.handle(trigger(TriggerKind.ALARM, id, time.nowMillis()))
        val request = starter.requests.single()
        assertEquals(id, request.alarmId)
        assertEquals(RingKind.PRIMARY, request.kind)

        val session = controller.begin(request)!!
        assertEquals("a plain default alarm", "Alarm", session.alarm.label)
        assertNotNull(store.current)
        controller.dismiss()
        assertNull(store.current)
    }

    @Test fun triggersThatFailInsideTheEngineRingAnywayWithWhatTheTriggerCarries() = runBlocking {
        val id = savedAlarm()
        val (_, triggers) = brokenEngine(alarmsFail = false)
        val at = time.nowMillis()
        triggers.handle(trigger(TriggerKind.SNOOZE, id, at, eventId = 5))
        triggers.handle(trigger(TriggerKind.BACKUP, id, at, eventId = 5, index = 1))
        assertEquals(listOf(RingKind.SNOOZE_RETURN, RingKind.BACKUP), starter.requests.map { it.kind })
        assertTrue(starter.requests.all { it.alarmId == id && it.eventId == 5L })
    }

    @Test fun anAlarmArmedWithTheOsButMissingFromTheDatabaseIsRungNotIgnored() = runBlocking {
        val lost = savedAlarm()
        scheduler.schedule(lost)
        assertTrue(lost in runtime.registeredTriggers())
        alarms.delete(lost) // vanishes behind the scheduler's back, the way a reset or corrupted database would lose it

        val request = ring.onAlarmFired(lost, time.nowMillis())
        assertNotNull("a trigger we armed is a real alarm", request)
        assertFalse("and it is not remembered as armed any more", lost in runtime.registeredTriggers())
        assertEquals("default alarm", "Alarm", ring.begin(request!!)!!.alarm.label)
    }

    @Test fun anAlarmThatWasReallyDeletedStaysQuiet() = runBlocking {
        val gone = savedAlarm()
        scheduler.schedule(gone)
        actions.delete(gone, overrideLock = true)
        assertNull("deleting cancels the trigger and the bookkeeping, so a straggler is ignored", ring.onAlarmFired(gone, time.nowMillis()))
    }

    @Test fun reschedulingStillArmsEveryAlarmAndTheWatchdogWhenSettingsCannotBeReadOrWritten() = runBlocking {
        val id = savedAlarm()
        val brokenContext = SchedulingContextProvider(BrokenSettings, groups, exceptions, calendar, time)
        val scheduler2 = AlarmSchedulerImpl(
            alarms, timers, wakeChecks, history, BrokenSettings, calculator, brokenContext, pending, time, runtime,
            surfaces, diagnostics, wakeCheckController, store, notifier,
        )
        pending.cancel(TriggerKind.WATCHDOG, 0)
        scheduler2.rescheduleAll("test: settings unreadable")
        assertTrue("alarm armed although settings were unreadable", pending.isScheduled(TriggerKind.ALARM, id))
        assertTrue("watchdog armed too", pending.isScheduled(TriggerKind.WATCHDOG, 0))
    }

    @Test fun housekeepingAfterAnUnreadableDatabaseDoesNotThrow() = runBlocking {
        val (controller, _) = brokenEngine()
        controller.recoverStaleState()
        assertTrue(starter.requests.isEmpty())
    }

    // ---- an OS that refuses ----------------------------------------------------------------------------------------

    @Test fun aRefusedAlarmClockFallsBackToAnExactWakeupAndIsReported() = runBlocking {
        val id = savedAlarm()
        pending.faultInjector = { call -> if (call == "setAlarmClock") throw SecurityException("alarm clocks refused by the test") }
        scheduler.rescheduleAll("test: alarm clock refused")
        assertTrue("armed anyway, the second-best way", pending.isScheduled(TriggerKind.ALARM, id))
        assertNotNull(pending.lastFallback)
        assertTrue(runtime.lastSchedulingProblem.isNotBlank())
        assertTrue("registered for the missed-alarm bookkeeping too", id in runtime.registeredTriggers())
    }

    @Test fun ifExactWakeupsAreRefusedTooAnInexactOneIsUsed() = runBlocking {
        val id = savedAlarm()
        pending.faultInjector = { call -> if (call != "setAndAllowWhileIdle") throw SecurityException("only inexact allowed") }
        scheduler.schedule(id)
        assertTrue(pending.isScheduled(TriggerKind.ALARM, id))
        assertTrue(pending.lastFallback!!.contains("inexact"))
    }

    @Test fun whenTheOsRefusesEverythingTheUserIsToldAndNothingIsClaimedToBeArmed() = runBlocking {
        val id = savedAlarm()
        pending.faultInjector = { throw SecurityException("nothing is allowed") }
        scheduler.rescheduleAll("test: everything refused")
        assertFalse(pending.isScheduled(TriggerKind.ALARM, id))
        assertFalse("bookkeeping does not claim the alarm is armed", id in runtime.registeredTriggers())
        assertTrue(runtime.lastSchedulingProblem.contains("could not be armed"))

        pending.faultInjector = null
        scheduler.rescheduleAll("test: recovered")
        assertTrue(pending.isScheduled(TriggerKind.ALARM, id))
        assertEquals("the problem record clears once everything is armed", "", runtime.lastSchedulingProblem)
    }

    @Test fun aSnoozeTheOsWillNotArmLeavesTheAlarmRingingInsteadOfSilencingIt() = runBlocking {
        val id = savedAlarm()
        ring.begin(ring.onAlarmFired(id, time.nowMillis())!!)
        pending.faultInjector = { throw SecurityException("nothing is allowed") }
        assertTrue(ring.snooze() is SnoozeOutcome.NotAllowed)
        assertNotNull("still ringing: a snooze that never comes back would be the worst outcome", store.current)
    }

    @Test fun aRefusedServiceStartIsRetriedFromAnAlarmTriggerButNotForever() = runBlocking {
        val launcher = RingServiceLauncher(context, runtime, notifier, diagnostics, pending, locks, time)
        launcher.startService = { _, _ -> throw IllegalStateException("ForegroundServiceStartNotAllowedException (simulated)") }
        val id = savedAlarm()
        launcher.start(RingRequest(id, 7, time.nowMillis(), RingKind.PRIMARY))
        assertTrue("retry armed", pending.isScheduled(TriggerKind.RING_RETRY, id))
        assertTrue(runtime.lastServiceStartFailure.contains("ForegroundServiceStartNotAllowed"))

        pending.cancel(TriggerKind.RING_RETRY, id)
        launcher.start(RingRequest(id, 7, time.nowMillis(), RingKind.PRIMARY, attempt = RingRetry.MAX_ATTEMPTS))
        assertFalse("gives up after the attempt limit", pending.isScheduled(TriggerKind.RING_RETRY, id))

        launcher.start(RingRequest(RingIds.TIMER_ALARM_BASE - 1, -1, time.nowMillis(), RingKind.TIMER, timerId = 1))
        assertFalse("timers are not retried", pending.isScheduled(TriggerKind.RING_RETRY, RingIds.TIMER_ALARM_BASE - 1))
    }

    @Test fun aSuccessfulServiceStartHandsTheCpuWakeLockOverToTheService() = runBlocking {
        val launcher = RingServiceLauncher(context, runtime, notifier, diagnostics, pending, locks, time)
        launcher.startService = { _, _ -> }
        launcher.start(RingRequest(savedAlarm(), 7, time.nowMillis(), RingKind.PRIMARY))
        assertTrue("held until the service takes over (it releases it itself, and it times out anyway)", locks.handoffHeld)
        locks.releaseHandoff()
        assertFalse(locks.handoffHeld)
    }

    @Test fun wakeLocksCanBeAcquiredAndReleasedRepeatedlyInAnyOrder() {
        assertFalse(locks.ringHeld)
        locks.releaseRing(); locks.releaseHandoff(); locks.releaseScreen() // releasing what is not held is harmless
        assertTrue(locks.acquireRing(60_000)); assertTrue(locks.acquireRing(60_000))
        assertTrue(locks.ringHeld)
        locks.releaseAll(); locks.releaseAll()
        assertFalse(locks.ringHeld)
    }

    @Test fun theFallbackAlarmNotificationSoundsByItselfAndRepeatsUntilDismissed() {
        notifier.ensureChannels()
        val n = notifier.ringFallback()
        assertEquals(Channels.RING_FALLBACK, n.channelId)
        assertTrue("repeats until dismissed", n.flags and Notification.FLAG_INSISTENT != 0)
        assertNotNull(n.fullScreenIntent)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(Channels.RING_FALLBACK)!!
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNotNull("the channel itself carries the alarm tone", channel.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
    }

    // ---- the clock changes under a waiting alarm -----------------------------------------------------------------

    @Test fun aClockSetForwardPastAnArmedAlarmStillRingsIt() = runBlocking {
        val id = savedAlarm()
        scheduler.schedule(id)
        runtime.setRegisteredTriggers(mapOf(id to time.nowMillis() - 3 * 60_000L)) // it was armed for 3 minutes ago
        systemEvents.handle(Intent.ACTION_TIME_CHANGED)
        val request = starter.requests.singleOrNull { it.alarmId == id }
        assertNotNull("rescheduling alone would have moved it to tomorrow and lost it", request)
        assertEquals(RingKind.PRIMARY, request!!.kind)
    }

    @Test fun aLongClockJumpRecordsTheAlarmAsMissedInsteadOfRingingHoursLate() = runBlocking {
        val id = savedAlarm()
        scheduler.schedule(id)
        runtime.setRegisteredTriggers(mapOf(id to time.nowMillis() - 3 * 3_600_000L))
        systemEvents.handle(Intent.ACTION_TIME_CHANGED)
        assertTrue(starter.requests.none { it.alarmId == id })
        assertEquals(AlarmOutcome.MISSED, history.recent(5).single { it.alarmId == id }.outcome)
    }

    @Test fun aClockChangeNeverRepeatsAnAlarmThatAlreadyRang() = runBlocking {
        val id = savedAlarm()
        val at = time.nowMillis() - 2 * 60_000L
        history.start(AlarmEvent(alarmId = id, label = "done", scheduledAt = at, firedAt = at, outcome = AlarmOutcome.DISMISSED, endedAt = at + 20_000))
        runtime.setRegisteredTriggers(mapOf(id to at))
        systemEvents.handle(Intent.ACTION_TIME_CHANGED)
        assertTrue(starter.requests.isEmpty())
    }

    @Test fun aTimeZoneChangeRingsNothingByItselfAndKeepsTheAlarmArmed() = runBlocking {
        val id = savedAlarm()
        scheduler.schedule(id)
        systemEvents.handle(Intent.ACTION_TIMEZONE_CHANGED)
        assertTrue(starter.requests.isEmpty())
        assertTrue(pending.isScheduled(TriggerKind.ALARM, id))
    }

    @Test fun anExpiredVacationDoesNotKeepAlarmsOffAfterTheClockChanges() = runBlocking {
        val id = savedAlarm()
        settings.update { it.copy(vacation = VacationMode(enabled = true, resumeEpochDay = LocalDate.now().toEpochDay() - 2)) }
        systemEvents.handle(Intent.ACTION_TIME_CHANGED)
        assertTrue(pending.isScheduled(TriggerKind.ALARM, id))
    }

    // ---- the app dying while an alarm rings --------------------------------------------------------------------------

    private suspend fun ringingAlarm(block: Alarm.() -> Alarm = { this }): Pair<Long, RingSession> {
        val id = savedAlarm(block)
        return id to ring.begin(ring.onAlarmFired(id, time.nowMillis())!!)!!
    }

    @Test fun aCrashWhileRingingArmsARescueForTheSameAlarmAndEvent() = runBlocking {
        val (id, session) = ringingAlarm()
        assertTrue(crashGuard.onCrash(IllegalStateException("the ring screen blew up")))
        assertTrue(pending.isScheduled(TriggerKind.RING_RETRY, id))
        assertEquals(1, runtime.ringCrashTimes().size)

        // A fresh process: nothing in memory, the rescue trigger fires.
        store.set(null)
        pending.cancel(TriggerKind.RING_RETRY, id)
        starter.requests.clear()
        handler.handle(trigger(TriggerKind.RING_RETRY, id, session.scheduledAt, session.eventId, RingRetry.encode(session.kind, 0)))
        val again = starter.requests.single()
        assertEquals(id, again.alarmId)
        assertEquals(session.eventId, again.eventId)
        assertEquals(RingKind.PRIMARY, again.kind)
        assertEquals(1, again.attempt)
    }

    @Test fun aCrashWhileNothingRingsArmsNothing() = runBlocking {
        assertNull(store.current)
        assertFalse(crashGuard.onCrash(RuntimeException("a crash in the alarm list")))
        assertTrue(runtime.ringCrashTimes().isEmpty())
    }

    @Test fun aRescueForAnAlarmThatWasHandledMeanwhileStaysQuiet() = runBlocking {
        val (id, session) = ringingAlarm()
        ring.dismiss()
        starter.requests.clear()
        handler.handle(trigger(TriggerKind.RING_RETRY, id, session.scheduledAt, session.eventId, RingRetry.encode(session.kind, 0)))
        assertTrue("it was dismissed, so nothing rings again", starter.requests.isEmpty())
    }

    @Test fun aRescueDoesNotRingOverSomethingThatIsAlreadyRinging() = runBlocking {
        val (id, session) = ringingAlarm()
        starter.requests.clear()
        handler.handle(trigger(TriggerKind.RING_RETRY, id, session.scheduledAt, session.eventId, RingRetry.encode(session.kind, 0)))
        assertTrue(starter.requests.isEmpty())
    }

    @Test fun aCrashLoopStopsBeingRescuedSoItCannotCycleForEver() = runBlocking {
        val (id, session) = ringingAlarm()
        repeat(CrashRescuePolicy.MAX_RESCUES + 1) { runtime.recordRingCrash(time.nowMillis() - it) }
        assertFalse(crashGuard.rescue(session, "test"))
        assertFalse(pending.isScheduled(TriggerKind.RING_RETRY, id))
    }

    @Test fun afterTwoCrashesTheNotificationCarriesTheControlsInsteadOfLaunchingTheScreen() = runBlocking {
        val id = savedAlarm()
        val normal = ring.begin(ring.onAlarmFired(id, time.nowMillis())!!)!!
        assertFalse(normal.safeMode)
        assertNotNull("normally the full-screen alarm is attached", notifier.ring(normal).fullScreenIntent)
        ring.dismiss()

        runtime.recordRingCrash(time.nowMillis() - 60_000)
        runtime.recordRingCrash(time.nowMillis() - 5_000)
        val request = RingRequest(id, -1, time.nowMillis(), RingKind.PRIMARY)
        val safe = ring.begin(request)!!
        assertTrue(safe.safeMode)
        val notification = notifier.ring(safe)
        assertNull("the screen that keeps crashing is no longer launched on its own", notification.fullScreenIntent)
        val titles = notification.actions.map { it.title.toString() }
        assertTrue("a direct way to stop: $titles", context.getString(R.string.action_dismiss) in titles)
        assertTrue(context.getString(R.string.action_snooze) in titles)
    }
}
