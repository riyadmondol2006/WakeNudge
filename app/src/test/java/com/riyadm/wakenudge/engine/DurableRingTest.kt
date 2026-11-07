package com.riyadm.wakenudge.engine

import android.app.ApplicationExitInfo
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteFullException
import com.riyadm.wakenudge.BuildConfig
import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.data.backup.BackupRig
import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.data.backup.ImportFailure
import com.riyadm.wakenudge.data.backup.ImportResult
import com.riyadm.wakenudge.data.backup.RecordingScheduler
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.engine.audio.AlarmAudioEngine
import com.riyadm.wakenudge.engine.audio.VolumePlanner
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.engine.policy.RingProof
import com.riyadm.wakenudge.health.ExitReasons
import com.riyadm.wakenudge.health.ProcessExit
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A ring against everything that can take its process away: a kill, a restart, a force-stop, failing storage, a moved clock. */
class DurableRingTest {
    private val clock = TestClock()
    private val alarms = MemoryAlarms()
    private val history = MemoryHistory()
    private val timers = MemoryTimers()
    private val store = RingSessionStore()
    private val prefs = spyk(MapPrefs())
    private val runtime = realRuntime(prefs).also { it.bootCountSource = { boots } }
    private var boots = 7
    private val notifier = mockk<Notifier>(relaxed = true)
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
    }
    private val log = SilentLog()
    private val durable = DurableRingStore(runtime, log)

    private fun newController() = RingController(
        store, alarms, history, timers, MemorySettings(), RecordingScheduler(), mockk(relaxed = true), pending, runtime, clock, notifier, CountingSurfaces(), log,
    )

    private var controller = newController()

    private val twoMissions = listOf(MissionConfig(MissionType.MATH), MissionConfig(MissionType.TYPING))

    private fun anti(minutes: Int, never: Boolean = true) =
        AntiOversleepSettings(resumeWindowMinutes = minutes, ringTimeoutMinutes = if (never) AntiOversleepSettings.RING_TIMEOUT_NEVER else 10, backupEnabled = false)

    private fun alarm(block: Alarm.() -> Alarm = { this }) =
        alarms.add(Alarm(label = "work", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK).block())

    private fun hard() = alarm { copy(missions = twoMissions, snooze = SnoozeSettings(enabled = false), antiOversleep = anti(720).copy(emergencyStopEnabled = false)) }

    private suspend fun ring(a: Alarm, at: Long = clock.now): RingSession = controller.begin(controller.onAlarmFired(a.id, at)!!)!!

    /** The process is gone: nothing in memory, and a new controller where the old one was. */
    private fun processDied() {
        store.set(null)
        controller = newController()
    }

    private fun sentinelArmed(times: Int = 1) =
        verify(atLeast = times) { pending.setAlarmClock(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID, any(), any(), any(), any()) }

    // ---- the record ------------------------------------------------------------------------------------------------

    @Test fun `a record survives the trip through JSON`() {
        val session = RingSession(
            Alarm(id = 3, label = "x", missions = twoMissions), RingKind.BACKUP, RingPhase.RINGING, eventId = 9, scheduledAt = 11, startedAt = 12, snoozeCount = 2, missionsDone = 1,
            mutesUsed = 1, fallback = true, wayOutProgress = 4, pauses = 2, resumes = 1, pullBacks = 6,
        )
        val record = DurableRingRecord.of(session, bootCount = 5, elapsed = 77)
        durable.writeRing(record)
        val back = durable.ring()!!
        assertEquals(record, back)
        assertEquals(session.alarm, back.alarm())
        assertEquals(RingRequest(3, 9, 11, RingKind.BACKUP, 2, RingPhase.RINGING), back.toRequest())
    }

    @Test fun `corrupt JSON counts as no record and never throws`() {
        for (garbage in listOf("", "{", "not json", "[1,2,3]", "{\"alarmId\":\"x\"}", "{\"alarmJson\":\"{broken\",\"alarmId\":1}", "null")) {
            runtime.durableRingJson = garbage
            assertNull("for: $garbage", durable.ring())
            assertNull("and it is removed: $garbage", runtime.durableRingJson)
        }
    }

    @Test fun `an unknown ring kind from another version does not lose the ring`() {
        val record = DurableRingRecord.of(RingSession(Alarm(id = 3), RingKind.BACKUP, RingPhase.RINGING, 9, 11, 12), 1, 1)
        val json = WakeJson.instance.encodeToString(DurableRingRecord.serializer(), record).replace("\"BACKUP\"", "\"SOMETHING_NEW\"")
        runtime.durableRingJson = json
        assertEquals(RingKind.PRIMARY, durable.ring()!!.kind)
    }

    @Test fun `storage that throws on every read and write does not stop anything`() = runBlocking {
        val runtime = realRuntime(ThrowingPrefs())
        val store2 = DurableRingStore(runtime, log)
        assertNull(store2.ring())
        assertEquals(emptyList<RingRequest>(), store2.queue())
        store2.writeRing(DurableRingRecord(alarmJson = "{}", alarmId = 1))
        store2.clearRing()
        store2.writeQueue(emptyList())

        val a = alarms.add(Alarm(label = "work"))
        val c = RingController(store, alarms, history, timers, MemorySettings(), RecordingScheduler(), mockk(relaxed = true), pending, runtime, clock, notifier, CountingSurfaces(), log)
        val session = c.begin(RingRequest(a.id, -1, clock.now, RingKind.PRIMARY))
        assertNotNull("the ring starts anyway", session)
        c.noteMissionDone(1)
        c.tick()
        assertTrue(c.dismiss())
        assertNull(store.current)
    }

    // ---- when it is written ------------------------------------------------------------------------------------------

    @Test fun `a ring is saved when it begins and at each mission step, not on every touch`() = runBlocking {
        val a = hard()
        val s = ring(a)
        assertEquals(a.id, durable.ring()!!.alarmId)
        assertEquals(s.eventId, durable.ring()!!.eventId)
        val before = runtime.durableRingJson
        clock.now += 5_000
        controller.noteInteraction()
        assertEquals("a touch writes nothing", before, runtime.durableRingJson)
        controller.noteMissionDone(1)
        assertEquals(1, durable.ring()!!.missionsDone)
    }

    @Test fun `timers and test alarms have no record`() = runBlocking {
        timers.save(CountdownTimer(id = 5, durationMs = 60_000, state = TimerState.RINGING))
        controller.begin(RingRequest(RingIds.TIMER_ALARM_BASE - 5, -1, clock.now, RingKind.TIMER, timerId = 5))
        assertNull(durable.ring())
        controller.dismiss()
        controller.begin(RingRequest(RingIds.TEST_ALARM_ID, -1, clock.now, RingKind.TEST))
        assertNull(durable.ring())
    }

    @Test fun `a sunrise glow is saved only once it really rings`() = runBlocking {
        val a = alarm { copy(antiOversleep = anti(720)) }
        controller.begin(RingRequest(a.id, -1, clock.now, RingKind.PRIMARY, phase = RingPhase.SUNRISE))
        assertNull(durable.ring())
        controller.begin(RingRequest(a.id, -1, clock.now, RingKind.PRIMARY))
        assertNotNull(durable.ring())
    }

    // ---- every way a ring ends leaves no record --------------------------------------------------------------------

    private suspend fun assertNoRecordAfter(failingStorage: Boolean, end: suspend (RingSession, Alarm) -> Unit) {
        val a = alarm { copy(snooze = SnoozeSettings(minutes = 5), antiOversleep = anti(720, never = false).copy(ringTimeoutMinutes = 1)) }
        val s = ring(a)
        assertNotNull(durable.ring())
        clearMocks(pending, answers = false)
        if (failingStorage) history.failure = SQLiteFullException("database or disk is full")
        end(s, a)
        assertNull(store.current)
        assertNull("no record left", durable.ring())
        verify { pending.cancel(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID, any()) }
        history.failure = null
        processDied()
        assertNull("the sentinel finds nothing to ring", controller.onSentinelFired())
        assertFalse("and neither does anything looking at start-up", controller.reviveDurableRing())
    }

    @Test fun `a dismissed ring leaves no record`() = runBlocking { assertNoRecordAfter(false) { s, _ -> controller.dismiss(RingTarget.of(s)) } }

    @Test fun `a dismissed ring leaves no record when the disk is full`() = runBlocking { assertNoRecordAfter(true) { s, _ -> controller.dismiss(RingTarget.of(s)) } }

    @Test fun `an emergency-stopped ring leaves no record`() = runBlocking { assertNoRecordAfter(false) { s, _ -> controller.emergencyStop(RingTarget.of(s), RingProof.Hold) } }

    @Test fun `an emergency-stopped ring leaves no record when the disk is full`() = runBlocking { assertNoRecordAfter(true) { s, _ -> controller.emergencyStop(RingTarget.of(s), RingProof.Hold) } }

    @Test fun `a snoozed ring leaves no record`() = runBlocking { assertNoRecordAfter(false) { s, _ -> controller.snooze(RingTarget.of(s)) } }

    @Test fun `a snoozed ring leaves no record when the disk is full`() = runBlocking { assertNoRecordAfter(true) { s, _ -> controller.snooze(RingTarget.of(s)) } }

    @Test fun `a timed out ring leaves no record`() = runBlocking {
        assertNoRecordAfter(false) { _, _ ->
            clock.now += 61_000
            controller.tick()
        }
    }

    @Test fun `a timed out ring leaves no record when the disk is full`() = runBlocking {
        assertNoRecordAfter(true) { _, _ ->
            clock.now += 61_000
            controller.tick()
        }
    }

    @Test fun `a record of a ring that was dealt with just before the process died does not ring`() = runBlocking {
        val a = alarm { copy(antiOversleep = anti(720)) }
        val s = ring(a)
        // Closed in the history, killed before the record went.
        history.update(history.get(s.eventId)!!.copy(outcome = AlarmOutcome.DISMISSED))
        processDied()
        assertNull(controller.onSentinelFired())
        assertNull(durable.ring())
    }

    // ---- killed without warning ------------------------------------------------------------------------------------

    @Test fun `the sentinel runs only for an alarm that keeps coming back`() = runBlocking {
        val plain = ring(alarm())
        verify(exactly = 0) { pending.setAlarmClock(TriggerKind.RING_SENTINEL, any(), any(), any(), any(), any()) }
        controller.dismiss(RingTarget.of(plain))
        ring(alarm { copy(antiOversleep = anti(720)) })
        sentinelArmed()
    }

    @Test fun `the sentinel is armed 30 s ahead, stays armed while ringing and goes when the ring ends`() = runBlocking {
        val a = hard()
        val s = ring(a)
        verify { pending.setAlarmClock(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID, clock.now + RingIds.SENTINEL_MS, any(), any(), any()) }
        clearMocks(pending, answers = false)
        clock.now += RingIds.SENTINEL_REFRESH_MS
        controller.tick()
        verify { pending.setAlarmClock(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID, clock.now + RingIds.SENTINEL_MS, any(), any(), any()) }

        clearMocks(pending, answers = false)
        assertNull("still ringing: firing only pushes it on", controller.onSentinelFired())
        verify { pending.setAlarmClock(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID, any(), any(), any(), any()) }
        assertNotNull(store.current)

        controller.noteMissionDone(1)
        controller.noteMissionDone(2)
        assertTrue(controller.dismiss(RingTarget.of(s), RingProof.MissionsComplete))
        verify { pending.cancel(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID, any()) }
    }

    @Test fun `a sentinel that keeps firing after a kill rings it back with its strictness and progress`() = runBlocking {
        val a = hard()
        val s = ring(a)
        controller.noteMissionDone(1)
        processDied()
        alarms.failReads = IOException("database unreadable")

        val request = controller.onSentinelFired()!!
        assertEquals(RingRequest(a.id, s.eventId, s.scheduledAt, RingKind.PRIMARY), request)
        val back = controller.begin(request)!!
        assertEquals("the mission list came from the saved ring", twoMissions, back.alarm.missions)
        assertEquals(1, back.missionsDone)
        assertEquals(s.startedAt, back.startedAt)
        assertEquals(false, back.alarm.antiOversleep.emergencyStopEnabled)
        assertFalse("still cannot be ended by anything but its missions", controller.dismiss(RingTarget.of(back)))
        assertNotNull(store.current)
        sentinelArmed()
    }

    @Test fun `a redelivered start picks up the saved progress too`() = runBlocking {
        val a = hard()
        val s = ring(a)
        controller.noteMissionDone(1)
        processDied()
        val back = controller.begin(s.toRequest())!!
        assertEquals(1, back.missionsDone)
    }

    @Test fun `progress restored is never past what the alarm has`() = runBlocking {
        val a = hard()
        val s = ring(a)
        durable.writeRing(durable.ring()!!.copy(missionsDone = 99))
        processDied()
        assertEquals(2, controller.begin(s.toRequest())!!.missionsDone)
    }

    @Test fun `housekeeping at start leaves the saved ring and its event alone`() = runBlocking {
        val a = hard()
        val s = ring(a)
        clock.now += 13 * 60 * 60_000L
        processDied()
        controller.recoverStaleState()
        assertEquals(AlarmOutcome.RINGING, history.get(s.eventId)!!.outcome)
        assertEquals("the ring is still marked active", a.id, runtime.activeAlarmId)
        assertNotNull(durable.ring())
    }

    @Test fun `a ring killed long after its window comes back as long as the phone did not restart`() = runBlocking {
        val a = hard()
        ring(a)
        clock.now += 20 * 60 * 60_000L
        processDied()
        assertTrue(controller.reviveDurableRing())
        assertNotNull(controller.onSentinelFired())
    }

    @Test fun `opening the app after a plain alarm's ring was stopped brings it back shortly`() = runBlocking {
        val a = alarm()
        ring(a)
        clearMocks(pending, answers = false)
        processDied()
        assertTrue(controller.reviveDurableRing())
        verify { pending.setAlarmClock(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID, clock.now + RingIds.RESUME_DELAY_MS, any(), any(), any()) }
    }

    @Test fun `a plain alarm stopped hours ago does not ring out of nowhere when the app next starts`() = runBlocking {
        val a = alarm()
        val s = ring(a)
        clock.now += 2 * 60 * 60_000L
        processDied()
        assertFalse(controller.reviveDurableRing())
        assertNull(controller.onSentinelFired())
        assertNull(durable.ring())
        verify { notifier.showMissed(any(), s.scheduledAt) }
        assertEquals(AlarmOutcome.MISSED, history.get(s.eventId)!!.outcome)
    }

    @Test fun `a plain alarm killed part way through a long ring is still rung by the system handing it back`() = runBlocking {
        val a = alarm { copy(antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 30, backupEnabled = false)) }
        val s = ring(a)
        clock.now += 15 * 60_000L
        processDied()
        assertTrue("past its 10-minute restart window, but no restart happened", controller.reviveDurableRing())
        assertEquals(s.eventId, controller.begin(s.toRequest())!!.eventId)
    }

    @Test fun `with nothing saved nothing comes back`() = runBlocking {
        processDied()
        assertFalse(controller.reviveDurableRing())
        assertNull(controller.onSentinelFired())
        verify { pending.cancel(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID, any()) }
    }

    // ---- the phone restarts ---------------------------------------------------------------------------------------

    /** A restart: the OS forgets everything and a new process starts in a new boot. */
    private fun restarted(offForMs: Long = 60_000L) {
        processDied()
        boots++
        clock.restart(offForMs)
        clearMocks(pending, answers = false)
    }

    private fun resumeLimits(window: Int, limit: Int) = runBlocking {
        val a = alarm { copy(snooze = SnoozeSettings(maxCount = 5), antiOversleep = anti(window).copy(emergencyStopEnabled = false, maxResumes = limit), missions = twoMissions) }
        val s = ring(a)
        controller.noteMissionDone(1)
        for (restart in 1..limit) {
            restarted()
            assertTrue("restart $restart comes back", controller.reviveDurableRing())
            verify { pending.setAlarmClock(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID, clock.now + RingIds.RESUME_DELAY_MS, any(), any(), any()) }
            val back = controller.begin(controller.onSentinelFired()!!)!!
            assertEquals(1, back.missionsDone)
            assertEquals(restart, back.resumes)
        }
        restarted()
        assertFalse("restart ${limit + 1} in one ring does not ring", controller.reviveDurableRing())
        assertNull(controller.onSentinelFired())
        assertNull(durable.ring())
        verify { notifier.showMissed(any(), s.scheduledAt) }
        val event = history.get(s.eventId)!!
        assertEquals(AlarmOutcome.MISSED, event.outcome)
        assertEquals(limit, event.resumes)
        assertEquals(1, event.missionsDone)
    }

    @Test fun `three restarts come back and a fourth does not, on Easy`() = resumeLimits(10, 3)

    @Test fun `three restarts come back and a fourth does not, on Normal`() = resumeLimits(120, 3)

    @Test fun `five restarts come back and a sixth does not, on Hard`() = resumeLimits(720, 5)

    @Test fun `ten restarts come back and an eleventh does not, on No mercy`() = resumeLimits(720, 10)

    @Test fun `a saved ring from before the limit became a setting gets the old limit of three`() = runBlocking {
        val a = hard()
        ring(a)
        val record = durable.ring()!!
        val oldAlarm = record.alarmJson.replace(Regex(""""maxResumes":\d+,?"""), "")
        assertFalse(oldAlarm.contains("maxResumes"))
        durable.writeRing(record.copy(alarmJson = oldAlarm))
        repeat(3) {
            restarted()
            assertTrue(controller.reviveDurableRing())
            controller.begin(controller.onSentinelFired()!!)
        }
        restarted()
        assertFalse(controller.reviveDurableRing())
    }

    @Test fun `a nonsense stored limit never means endless, and never means no restart at all`() {
        val alarmJson = WakeJson.instance.encodeToString(Alarm.serializer(), Alarm(id = 1))
        fun comesBack(limit: Int, resumes: Int) = ResumePolicy.afterProcessStart(
            DurableRingRecord(alarmJson, alarmId = 1, resumes = resumes, startedAtWall = 0, bootCount = 1),
            window = 720, maxResumes = limit, keepsComingBack = true, now = 0, elapsed = 1, bootCount = 2, versionCode = 1, triggersWiped = false,
        ) is ResumeDecision.Resume
        assertTrue(comesBack(limit = -4, resumes = 0))
        assertFalse(comesBack(limit = -4, resumes = 1))
        assertTrue(comesBack(limit = 99_999, resumes = AntiOversleepSettings.MAX_RESUMES_LIMIT - 1))
        assertFalse(comesBack(limit = 99_999, resumes = AntiOversleepSettings.MAX_RESUMES_LIMIT))
    }

    // ---- a restart does not pay --------------------------------------------------------------------------------------

    /** A ring cut off by [times] restarts, back on. */
    private suspend fun resumed(a: Alarm, times: Int = 1): RingSession {
        ring(a)
        repeat(times) {
            restarted()
            assertTrue(controller.reviveDurableRing())
            controller.begin(controller.onSentinelFired()!!)
        }
        return store.current!!
    }

    @Test fun `a ring that comes back after a restart is at full volume at once, with no ramp`() = runBlocking {
        val a = alarm { copy(volume = VolumeSettings(level = 40, rampSeconds = 600), antiOversleep = anti(720)) }
        val first = ring(a)
        assertFalse(first.intensified)
        assertEquals("a ring that has not been restarted plays as set", a.volume, first.soundAlarm.volume)
        val back = run { restarted(); controller.reviveDurableRing(); controller.begin(controller.onSentinelFired()!!)!! }
        assertTrue(back.intensified)
        assertEquals(0, back.soundAlarm.volume.rampSeconds)
        val plan = VolumePlanner.plan(back.soundAlarm.volume, streamMax = 7, currentStreamIndex = 1, headsetRoute = false, hearingCapEnabled = true, capPercent = 50, escalated = true, sourceLufs = null)
        assertEquals("the whole stream", 7, plan.streamIndex)
        assertEquals("the alarm as saved is not changed by how this ring sounds", a.volume, back.alarm.volume)
        assertEquals(a.volume, durable.ring()!!.alarm()!!.volume)
    }

    @Test fun `full volume after a restart holds even for an alarm that does not get louder by itself`() = runBlocking {
        val a = alarm { copy(volume = VolumeSettings(level = 30, escalateWhenIgnored = false), antiOversleep = anti(720)) }
        val back = resumed(a)
        val plan = VolumePlanner.plan(back.soundAlarm.volume, 7, 1, false, true, 50, back.intensified, null)
        assertEquals(7, plan.streamIndex)
    }

    @Test fun `hearing protection on headphones still wins over the full volume of a restart`() = runBlocking {
        val back = resumed(alarm { copy(antiOversleep = anti(720)) })
        val plan = VolumePlanner.plan(back.soundAlarm.volume, 10, 1, headsetRoute = true, hearingCapEnabled = true, capPercent = 50, escalated = back.intensified, sourceLufs = null)
        assertEquals(5, plan.streamIndex)
        assertTrue(plan.cappedForHearing)
    }

    @Test fun `touching the screen does not calm a ring that came back after a restart`() = runBlocking {
        val back = resumed(alarm { copy(antiOversleep = anti(720)) })
        assertTrue(back.intensified)
        clock.now += 5_000
        controller.noteInteraction()
        assertTrue(store.current!!.intensified)
    }

    @Test fun `a restart takes the snooze away`() = runBlocking {
        val a = alarm { copy(snooze = SnoozeSettings(maxCount = 5), antiOversleep = anti(720)) }
        assertTrue(ring(a).canSnooze)
        val back = run { restarted(); controller.reviveDurableRing(); controller.begin(controller.onSentinelFired()!!)!! }
        assertFalse(back.canSnooze)
        assertEquals(SnoozeOutcome.NotAllowed, controller.snooze(RingTarget.of(back), RingProof.Gesture))
        assertEquals(back.eventId, store.current!!.eventId)
    }

    @Test fun `a ring that was only killed comes back as it was, with its snooze and its ramp`() = runBlocking {
        val a = alarm { copy(antiOversleep = anti(720), snooze = SnoozeSettings(maxCount = 5)) }
        ring(a)
        processDied()
        val back = controller.begin(controller.onSentinelFired()!!)!!
        assertEquals(0, back.resumes)
        assertFalse(back.intensified)
        assertTrue(back.canSnooze)
    }

    @Test fun `a ring started from nothing when the engine failed still knows it came back`() = runBlocking {
        val a = hard()
        ring(a)
        controller.noteMissionDone(1)
        restarted()
        controller.reviveDurableRing()
        val request = controller.onSentinelFired()!!
        // Anything throwing inside begin lands on the emergency session: the saved ring has to speak for it.
        every { prefs.getString("ring_crash_times", any()) } throws IllegalStateException("broken")
        val session = controller.begin(request)!!
        assertEquals(1, session.resumes)
        assertEquals(1, session.missionsDone)
        assertTrue(session.intensified)
        assertFalse(session.canSnooze)
        assertEquals(1, durable.ring()!!.resumes)
        assertEquals(1, durable.ring()!!.missionsDone)
    }

    private fun windowOf(window: Int, offForMinutes: Long, comesBack: Boolean) = runBlocking {
        val a = alarm { copy(antiOversleep = anti(window)) }
        val s = ring(a)
        restarted(offForMinutes * 60_000L)
        assertEquals("$offForMinutes min on a $window min window", comesBack, controller.reviveDurableRing())
        assertEquals(comesBack, controller.onSentinelFired() != null)
        if (!comesBack) {
            verify { notifier.showMissed(any(), s.scheduledAt) }
            assertEquals(AlarmOutcome.MISSED, history.get(s.eventId)!!.outcome)
        }
    }

    @Test fun `Easy comes back within 10 minutes`() = windowOf(10, 9, true)

    @Test fun `Easy past 10 minutes is a missed alarm`() = windowOf(10, 11, false)

    @Test fun `Normal comes back within 2 hours`() = windowOf(120, 119, true)

    @Test fun `Normal past 2 hours is a missed alarm`() = windowOf(120, 121, false)

    @Test fun `Hard comes back within 12 hours`() = windowOf(720, 719, true)

    @Test fun `Hard past 12 hours is a missed alarm`() = windowOf(720, 721, false)

    @Test fun `every boot broadcast counts one restart, however many of them arrive`() = runBlocking {
        val a = hard()
        ring(a)
        restarted()
        repeat(3) { controller.reviveDurableRing() }
        assertEquals(1, durable.ring()!!.resumes)
    }

    @Test fun `a restart is noticed from the clock when the system will not say how many boots there were`() = runBlocking {
        runtime.bootCountSource = { -1 }
        val a = hard()
        ring(a)
        processDied()
        clock.restart()
        assertTrue(controller.reviveDurableRing())
        assertEquals(1, durable.ring()!!.resumes)
    }

    @Test fun `a follow-up cannot bring back a ring that may not come back`() = runBlocking {
        val a = alarm { copy(antiOversleep = anti(10).copy(backupEnabled = true, backupCount = 2)) }
        val s = ring(a)
        assertEquals(2, history.get(s.eventId)!!.pendingBackups.size)
        restarted(30 * 60_000L)
        controller.reviveDurableRing()
        assertTrue("nothing left for a reschedule to arm again", history.get(s.eventId)!!.pendingBackups.isEmpty())
        verify { pending.cancel(TriggerKind.BACKUP, a.id, 1) }
    }

    @Test fun `a clock set back across a restart does not lose the alarm`() = runBlocking {
        val a = alarm { copy(antiOversleep = anti(10)) }
        ring(a)
        restarted()
        clock.changeClockBy(-6 * 60 * 60_000L)
        assertTrue(controller.reviveDurableRing())
    }

    @Test fun `system events after a restart arm the return even with no triggers and an unreadable database`() = runBlocking {
        val a = hard()
        ring(a)
        restarted()
        alarms.failReads = IOException("database unreadable")
        history.failure = IOException("database unreadable")
        val scheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { scheduler.rescheduleAll(any()) } throws IOException("database unreadable")
        val handler = SystemEventHandler(notifier, mockk<AlarmAudioEngine>(relaxed = true), scheduler, controller, mockk(relaxed = true), runtime, clock, log)
        handler.handle(Intent.ACTION_BOOT_COMPLETED)
        verify { pending.setAlarmClock(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID, clock.now + RingIds.RESUME_DELAY_MS, any(), any(), any()) }
        assertNull("nothing started from the receiver", store.current)
        assertEquals(1, durable.ring()!!.resumes)
    }

    @Test fun `an app update brings the ring back without using up a restart`() = runBlocking {
        val a = hard()
        ring(a)
        durable.writeRing(durable.ring()!!.copy(versionCode = BuildConfig.VERSION_CODE - 1))
        processDied()
        assertTrue(controller.reviveDurableRing(triggersWiped = true))
        assertEquals(0, durable.ring()!!.resumes)
        assertEquals(BuildConfig.VERSION_CODE, durable.ring()!!.versionCode)
    }

    // ---- a force-stop ----------------------------------------------------------------------------------------------

    @Test fun `a force-stop stays silent, and the next start reports the missed alarm`() = runBlocking {
        val a = hard()
        val s = ring(a)
        processDied()
        assertFalse(controller.reviveDurableRing(triggersWiped = true))
        assertNull(durable.ring())
        verify { notifier.showMissed(any(), s.scheduledAt) }
        assertEquals(AlarmOutcome.MISSED, history.get(s.eventId)!!.outcome)
        assertNull(controller.onSentinelFired())
    }

    // ---- a moved clock ---------------------------------------------------------------------------------------------

    @Test fun `setting the clock forward does not time out a ring`() = runBlocking {
        val a = alarm { copy(antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 10, backupEnabled = false)) }
        ring(a)
        clock.changeClockBy(2 * 60 * 60_000L)
        controller.tick()
        assertNotNull(store.current)
    }

    @Test fun `setting the clock back does not extend a ring timeout`() = runBlocking {
        val a = alarm { copy(antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 10, backupEnabled = false)) }
        ring(a)
        clock.changeClockBy(-2 * 60 * 60_000L)
        clock.now += 10 * 60_000L
        controller.tick()
        assertNull("real minutes passed, whatever the clock says", store.current)
    }

    @Test fun `setting the clock forward does not end a silence and setting it back does not extend one`() = runBlocking {
        ring(alarm())
        controller.silenceTemporarily()
        clock.changeClockBy(2 * 60 * 60_000L)
        controller.tick()
        assertTrue("still silenced", store.current!!.silencedUntil > 0)

        clock.changeClockBy(-4 * 60 * 60_000L)
        clock.now += RingIds.SILENCE_MS
        controller.tick()
        assertEquals("over after real seconds", 0L, store.current!!.silencedUntil)
    }

    @Test fun `a moved clock does not bring on the escalation either`() = runBlocking {
        ring(alarm())
        clock.changeClockBy(2 * 60 * 60_000L)
        controller.tick()
        assertFalse(store.current!!.intensified)
        clock.now += RingIds.INACTIVITY_ESCALATION_MS
        controller.tick()
        assertTrue(store.current!!.intensified)
    }

    // ---- two alarms ------------------------------------------------------------------------------------------------

    @Test fun `two alarms, one queued, process killed, both ring in order`() = runBlocking {
        val first = alarm()
        val second = alarm { copy(label = "second") }
        val s1 = ring(first)
        val queuedRequest = controller.onAlarmFired(second.id, clock.now)!!
        controller.begin(queuedRequest)
        assertEquals(first.id, store.current!!.alarm.id)
        sentinelArmed()

        processDied()
        val back = controller.begin(controller.onSentinelFired()!!)!!
        assertEquals("the one that was on comes back first", first.id, back.alarm.id)
        assertEquals(s1.eventId, back.eventId)
        assertTrue(controller.dismiss(RingTarget.of(back)))

        val next = controller.takeQueued()!!
        assertEquals(second.id, next.alarmId)
        val nextSession = controller.begin(next)!!
        assertEquals(second.id, nextSession.alarm.id)
        assertTrue(controller.dismiss(RingTarget.of(nextSession)))
        assertNull("nobody is left waiting", controller.takeQueued())
        assertEquals(null, runtime.durableQueueJson)
    }

    @Test fun `a queued alarm rings on its own if the first was dealt with before the kill`() = runBlocking {
        val first = alarm()
        val second = alarm { copy(label = "second") }
        val s1 = ring(first)
        controller.begin(controller.onAlarmFired(second.id, clock.now)!!)
        history.update(history.get(s1.eventId)!!.copy(outcome = AlarmOutcome.DISMISSED))
        processDied()
        assertEquals(second.id, controller.onSentinelFired()!!.alarmId)
    }

    @Test fun `a redelivered start of the queued alarm does not ring it twice`() = runBlocking {
        val first = alarm()
        val second = alarm { copy(label = "second") }
        val s1 = ring(first)
        val queuedRequest = controller.onAlarmFired(second.id, clock.now)!!
        controller.begin(queuedRequest)
        processDied()
        // Android hands back the second start first. The first ring was on, so it goes on, and the second waits once.
        val session = controller.begin(queuedRequest)!!
        assertEquals(first.id, session.alarm.id)
        assertEquals(s1.eventId, session.eventId)
        controller.dismiss(RingTarget.of(session))
        val next = controller.begin(controller.takeQueued()!!)!!
        assertEquals(second.id, next.alarm.id)
        controller.dismiss(RingTarget.of(next))
        assertNull(controller.takeQueued())
    }

    @Test fun `a newcomer after a kill does not overwrite the ring that was on`() = runBlocking {
        val first = hard()
        val s1 = ring(first)
        controller.noteMissionDone(1)
        processDied()
        val second = alarm { copy(label = "second") }
        val session = controller.begin(controller.onAlarmFired(second.id, clock.now)!!)!!
        assertEquals(first.id, session.alarm.id)
        assertEquals(1, session.missionsDone)
        assertEquals(s1.eventId, session.eventId)
        assertEquals(second.id, controller.takeQueued()!!.alarmId)
    }

    @Test fun `a saved ring coming back during another alarm's silent glow keeps its progress`() = runBlocking {
        val hard = hard()
        val s = ring(hard)
        controller.noteMissionDone(1)
        processDied()
        val other = alarm { copy(label = "glowing") }
        controller.begin(RingRequest(other.id, -1, clock.now, RingKind.PRIMARY, phase = RingPhase.SUNRISE))
        assertEquals("a glow writes nothing over it", 1, durable.ring()!!.missionsDone)

        val back = controller.begin(controller.onSentinelFired()!!)!!
        assertEquals(hard.id, back.alarm.id)
        assertEquals(s.eventId, back.eventId)
        assertEquals(1, back.missionsDone)
    }

    @Test fun `a queue with nothing on is brought back after a restart`() = runBlocking {
        val first = alarm()
        val second = alarm { copy(label = "second") }
        ring(first)
        controller.begin(controller.onAlarmFired(second.id, clock.now)!!)
        assertTrue(controller.dismiss()) // first dealt with; the process dies before the second begins
        restarted()
        assertTrue(controller.reviveDurableRing())
        assertEquals(second.id, controller.onSentinelFired()!!.alarmId)
    }

    @Test fun `a queued alarm whose time is long gone is reported instead of rung`() = runBlocking {
        val first = alarm()
        val second = alarm { copy(label = "second") }
        ring(first)
        val second0 = controller.onAlarmFired(second.id, clock.now)!!
        controller.begin(second0)
        assertTrue(controller.dismiss())
        restarted(3 * 60 * 60_000L)
        assertNull(controller.onSentinelFired())
        verify { notifier.showMissed(any(), second0.scheduledAt) }
        assertEquals(AlarmOutcome.MISSED, history.get(second0.eventId)!!.outcome)
    }

    @Test fun `a timer finishing after a kill waits behind the alarm that was on`() = runBlocking {
        timers.save(CountdownTimer(id = 5, durationMs = 60_000, state = TimerState.RINGING))
        val hard = hard()
        ring(hard)
        processDied()
        val timerRequest = RingRequest(RingIds.TIMER_ALARM_BASE - 5, -1, clock.now, RingKind.TIMER, timerId = 5)
        assertEquals("a timer waits behind the alarm that was on", hard.id, controller.begin(timerRequest)!!.alarm.id)
        assertEquals(5L, controller.takeQueued()!!.timerId)
    }

    // ---- the database failing at fire time -------------------------------------------------------------------------

    private fun copies() = AlarmSnapshots(runtime, log)

    @Test fun `a strict alarm whose database fails at fire time still rings strictly`() = runBlocking {
        val a = hard()
        copies().sync(alarms.rows.values.toList())
        alarms.failReads = IOException("database unreadable")
        val session = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!
        assertEquals(twoMissions, session.alarm.missions)
        assertEquals(false, session.alarm.antiOversleep.emergencyStopEnabled)
        assertFalse("one tap does not end it", controller.dismiss())
        assertFalse(controller.emergencyStop(proof = RingProof.Hold))
        assertNotNull(store.current)
    }

    @Test fun `a strict alarm lost from the database still rings strictly`() = runBlocking {
        val a = hard()
        copies().sync(listOf(a))
        runtime.setRegisteredTriggers(mapOf(a.id to clock.now))
        alarms.rows.remove(a.id)
        val session = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!
        assertEquals(twoMissions, session.alarm.missions)
    }

    @Test fun `when starting a session fails outright the stand-in is still the strict one`() = runBlocking {
        val a = hard()
        copies().sync(listOf(a))
        val surfaces = object : com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher {
            override fun refreshAll() = Unit
        }
        val exploding = object : com.riyadm.wakenudge.domain.repository.SettingsRepository by MemorySettings() {
            override suspend fun current() = throw IllegalStateException("settings exploded")
        }
        val c = RingController(store, alarms, history, timers, exploding, RecordingScheduler(), mockk(relaxed = true), pending, runtime, clock, notifier, surfaces, log)
        alarms.failReads = IOException("database unreadable")
        assertEquals(twoMissions, c.begin(RingRequest(a.id, -1, clock.now, RingKind.PRIMARY))!!.alarm.missions)
    }

    @Test fun `only an alarm a plain default would soften gets a copy`() {
        val plain = Alarm(id = 1, label = "plain")
        assertFalse(AlarmSnapshots.worthKeeping(plain))
        assertFalse(AlarmSnapshots.worthKeeping(plain.copy(antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 30, editLockMinutes = 10))))
        for (strict in listOf(
            plain.copy(missions = twoMissions),
            plain.copy(snoozeMissions = twoMissions),
            plain.copy(antiOversleep = AntiOversleepSettings(holdToDismissSeconds = 3)),
            plain.copy(antiOversleep = AntiOversleepSettings(emergencyStopEnabled = false)),
            plain.copy(antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 0)),
            plain.copy(antiOversleep = AntiOversleepSettings(resumeWindowMinutes = 120)),
        )) assertTrue("$strict", AlarmSnapshots.worthKeeping(strict))
    }

    @Test fun `copies follow the alarms and a corrupt copy counts as none`() {
        val copies = copies()
        val strict = Alarm(id = 4, missions = twoMissions)
        copies.update(strict)
        assertEquals(strict, copies.get(4))
        copies.update(strict.copy(missions = emptyList()))
        assertNull("no longer needs one", copies.get(4))
        copies.sync(listOf(strict, Alarm(id = 5)))
        assertEquals(strict, copies.get(4))
        assertNull(copies.get(5))
        copies.sync(emptyList())
        assertNull(copies.get(4))
        runtime.setAlarmSnapshotJson(9, "{broken")
        assertNull(copies.get(9))
    }

    private fun scheduler(): AlarmSchedulerImpl {
        val settings = MemorySettings()
        return AlarmSchedulerImpl(
            alarms, timers, mockk(relaxed = true) { coEvery { getAll() } returns emptyList() }, history, settings, NextTriggerCalculator(),
            SchedulingContextProvider(
                settings,
                mockk<GroupRepository>().also { coEvery { it.getGroups() } returns emptyList() },
                mockk<ExceptionRepository>().also { coEvery { it.getDates() } returns emptyList() },
                mockk(relaxed = true), clock,
            ),
            pending, clock, runtime, CountingSurfaces(), log, mockk(relaxed = true), store, notifier,
        )
    }

    @Test fun `the scheduler keeps the copies up to date`() = runBlocking {
        val a = hard()
        val s = scheduler()
        s.rescheduleAll("test")
        assertEquals(a.missions, copies().get(a.id)!!.missions)
        s.schedule(alarms.save(a.copy(missions = emptyList(), antiOversleep = AntiOversleepSettings())))
        assertNull(copies().get(a.id))
        val b = hard()
        s.schedule(b.id)
        assertNotNull(copies().get(b.id))
        s.cancel(b.id)
        assertNull(copies().get(b.id))
    }

    // ---- how long follow-ups stay worth restoring -----------------------------------------------------------------

    private fun snoozedEvent(alarmId: Long, overdueBy: Long, firedAgo: Long = overdueBy + 60_000) = runBlocking {
        history.start(AlarmEvent(alarmId = alarmId, label = "", scheduledAt = clock.now - firedAgo, firedAt = clock.now - firedAgo, snoozeUntil = clock.now - overdueBy))
    }

    @Test fun `a follow-up stays restorable for the alarm's own window when that is longer than an hour`() = runBlocking {
        val a = hard()
        val id = snoozedEvent(a.id, overdueBy = 3 * 60 * 60_000L)
        every { pending.isScheduled(any(), any(), any()) } returns false
        processDied()
        controller.recoverStaleState()
        assertEquals(AlarmOutcome.RINGING, history.get(id)!!.outcome)
        scheduler().rescheduleAll("boot")
        verify { pending.setAlarmClock(TriggerKind.SNOOZE, a.id, any(), any(), id, any()) }
    }

    @Test fun `past the hour an ordinary alarm's follow-up still goes as it always did`() = runBlocking {
        val a = alarm()
        val id = snoozedEvent(a.id, overdueBy = 3 * 60 * 60_000L)
        every { pending.isScheduled(any(), any(), any()) } returns false
        controller.recoverStaleState()
        assertEquals(AlarmOutcome.MISSED, history.get(id)!!.outcome)
        scheduler().rescheduleAll("boot")
        verify(exactly = 0) { pending.setAlarmClock(TriggerKind.SNOOZE, a.id, any(), any(), id, any()) }
    }

    @Test fun `a short window never makes a follow-up go sooner than the hour it always had`() = runBlocking {
        val a = alarm()
        val id = snoozedEvent(a.id, overdueBy = 30 * 60_000L)
        every { pending.isScheduled(any(), any(), any()) } returns false
        controller.recoverStaleState()
        assertEquals(AlarmOutcome.RINGING, history.get(id)!!.outcome)
    }

    // ---- restoring a backup ---------------------------------------------------------------------------------------

    @Test fun `a backup is refused while a ring is saved, and nothing changes`() = runBlocking {
        val rig = BackupRig()
        val json = rig.manager.export(false)
        DurableRingStore(rig.runtime, log).writeRing(DurableRingRecord.of(RingSession(Alarm(id = 1), RingKind.PRIMARY, RingPhase.RINGING, 1, 1, 1), 1, 1))
        assertEquals(ImportFailure.RingInProgress, (rig.manager.import(json) as ImportResult.Failure).reason)
        assertEquals(0, rig.store.replaceCalls)
        rig.runtime.clearDurableRing()
        assertTrue(rig.manager.import(json) is ImportResult.Success)
    }

    // ---- rescuing and the service ----------------------------------------------------------------------------------

    @Test fun `a ring that keeps coming back keeps being rescued, slowing down, while a plain one gives up`() {
        val guard = RingCrashGuard(store, runtime, pending, clock, log)
        fun ringing(a: AntiOversleepSettings) = store.set(RingSession(Alarm(id = 5, antiOversleep = a), RingKind.PRIMARY, RingPhase.RINGING, 11, 42, clock.now))
        ringing(AntiOversleepSettings())
        repeat(CrashRescuePolicy.MAX_RESCUES) { assertTrue(guard.onCrash(RuntimeException())) }
        assertFalse("plain alarms stop after a few", guard.onCrash(RuntimeException()))

        runtime.clearRingCrashes()
        ringing(anti(720))
        repeat(CrashRescuePolicy.MAX_RESCUES) { assertTrue(guard.onCrash(RuntimeException())) }
        clearMocks(pending, answers = false)
        repeat(4) { assertTrue("never gives up", guard.onCrash(RuntimeException())) }
        verify { pending.setAlarmClock(TriggerKind.RING_RETRY, 5, clock.now + 4_000, any(), any(), any()) }
        verify { pending.setAlarmClock(TriggerKind.RING_RETRY, 5, clock.now + 8_000, any(), any(), any()) }
        verify { pending.setAlarmClock(TriggerKind.RING_RETRY, 5, clock.now + 16_000, any(), any(), any()) }
        assertEquals(60_000L, CrashRescuePolicy.delayMs(List(10) { clock.now }, clock.now))
        assertEquals(RingRetry.DELAY_MS, CrashRescuePolicy.delayMs(listOf(clock.now), clock.now))
    }

    @Test fun `the service asks for systemExempted from Android 14, and falls back to media playback`() {
        assertEquals(
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK),
            RingServiceTypes.candidates(34),
        )
        assertEquals(listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK), RingServiceTypes.candidates(33))
    }

    @Test fun `the sentinel is a new trigger kind added last, so nothing already stored changes meaning`() {
        assertEquals(TriggerKind.RING_SENTINEL, TriggerKind.entries.last())
        assertEquals(TriggerKind.RING_SENTINEL, Triggers.kindOf(Triggers.action(TriggerKind.RING_SENTINEL)))
    }

    // ---- how the last process ended -------------------------------------------------------------------------------

    @Test fun `only a stop or a memory kill during a ring is worth noting`() {
        val during = 2_000L
        val exits = listOf(
            ProcessExit(1_000, ApplicationExitInfo.REASON_USER_REQUESTED, "before the ring"),
            ProcessExit(3_000, ApplicationExitInfo.REASON_USER_REQUESTED, "stop"),
            ProcessExit(4_000, ApplicationExitInfo.REASON_LOW_MEMORY, null),
            ProcessExit(5_000, ApplicationExitInfo.REASON_OTHER, "MemoryLimiter:AnonSwap"),
            ProcessExit(6_000, ApplicationExitInfo.REASON_CRASH, "boom"),
        )
        assertEquals(3, ExitReasons.noteworthy(exits, seenUpTo = 0, ringStartedAt = during).size)
        assertEquals("nothing was ringing", 0, ExitReasons.noteworthy(exits, 0, null).size)
        assertEquals("only what has not been seen", 1, ExitReasons.noteworthy(exits, seenUpTo = 4_500, ringStartedAt = during).size)
    }
}

/** Preferences on a disk that has gone: every read and write throws. */
private class ThrowingPrefs : SharedPreferences {
    private fun gone(): Nothing = throw IllegalStateException("storage is gone")
    override fun getAll(): MutableMap<String, *> = gone()
    override fun getString(key: String?, defValue: String?): String? = gone()
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = gone()
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = gone()
    override fun edit(): SharedPreferences.Editor = gone()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}
