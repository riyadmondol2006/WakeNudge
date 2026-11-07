package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.data.backup.RecordingScheduler
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.engine.policy.RingPolicy
import com.riyadm.wakenudge.engine.policy.RingProof
import com.riyadm.wakenudge.engine.policy.WayOut
import com.riyadm.wakenudge.missions.FallbackChain
import com.riyadm.wakenudge.missions.FallbackReason
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test that protects someone who is stuck: every ring has a way to end. Each shape of alarm is taken through its real
 * exits against the real controller, from every point a restart can leave it at.
 */
class RingTrapTest {
    private val clock = TestClock()
    private val alarms = MemoryAlarms()
    private val history = MemoryHistory()
    private val store = RingSessionStore()
    private var boots = 1
    private val runtime = realRuntime().also { it.bootCountSource = { boots } }
    private val notifier = mockk<Notifier>(relaxed = true)
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
    }
    private val log = SilentLog()
    private val durable = DurableRingStore(runtime, log)
    private fun newController() = RingController(
        store, alarms, history, MemoryTimers(), MemorySettings(), RecordingScheduler(), mockk(relaxed = true), pending, runtime, clock, notifier, CountingSurfaces(), log,
    )
    private var controller = newController()

    private fun anti(window: Int, limit: Int, strict: Boolean, never: Boolean, delay: Int = 0, wayOut: Int = 10, fallback: Int = 3) = AntiOversleepSettings(
        ringTimeoutMinutes = if (never) AntiOversleepSettings.RING_TIMEOUT_NEVER else 10,
        resumeWindowMinutes = window, maxResumes = limit, emergencyStopEnabled = !strict, backupEnabled = false,
        wayOutDelayMinutes = delay, wayOutCount = wayOut, fallbackCount = fallback,
    )

    /** The settings of section 2 that matter to whether a ring can end, one per level. */
    private val shapes: Map<String, AntiOversleepSettings> = mapOf(
        "Easy" to anti(10, 3, strict = false, never = false),
        "Normal" to anti(120, 3, strict = false, never = true),
        "Hard" to anti(720, 5, strict = true, never = true, delay = 3, fallback = 5),
        "No mercy" to anti(720, 10, strict = true, never = true, delay = 10, wayOut = 20, fallback = 8),
    )

    private val missionLists: List<List<MissionConfig>> = MissionType.entries.map { listOf(MissionConfig(it)) } + listOf(
        emptyList(),
        listOf(MissionConfig(MissionType.MATH, Difficulty.HARD), MissionConfig(MissionType.TYPING), MissionConfig(MissionType.MEMORY)),
    )

    private fun alarm(anti: AntiOversleepSettings, missions: List<MissionConfig>) = alarms.add(
        Alarm(label = "x", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK, missions = missions, antiOversleep = anti, snooze = SnoozeSettings(enabled = false)),
    )

    private fun restart() {
        store.set(null)
        controller = newController()
        boots++
        clock.restart(1_000)
    }

    private suspend fun comeBack() {
        controller.reviveDurableRing()
        controller.onSentinelFired()?.let { controller.begin(it) }
    }

    /** Whatever the ring asks for, done the way the ring screen does it. */
    private suspend fun finish(): Boolean {
        val s = store.current!!
        return if (s.hasMissions) {
            for (done in s.missionsDone + 1..s.alarm.missions.size) controller.noteMissionDone(done)
            controller.dismiss(RingTarget.of(store.current!!), RingProof.MissionsComplete)
        } else {
            controller.dismiss(RingTarget.of(s), RingProof.Gesture)
        }
    }

    @Test fun `every level and every mission can be finished after any number of restarts it allows`() = runBlocking {
        for ((level, anti) in shapes) for (missions in missionLists) for (restarts in 0..anti.maxResumes) {
            val a = alarm(anti, missions)
            controller.begin(controller.onAlarmFired(a.id, clock.now)!!)
            repeat(restarts) {
                restart()
                comeBack()
            }
            val s = store.current!!
            assertEquals("$level, ${missions.map { it.type }}, $restarts restarts", restarts, s.resumes)
            assertFalse("a ring back from a restart offers no snooze", restarts > 0 && s.canSnooze)
            assertTrue("$level, ${missions.map { it.type }}, $restarts restarts can be finished", finish())
            assertNull(store.current)
            assertNull("nothing is left to bring it back", durable.ring())
            alarms.rows.remove(a.id)
            clock.now += 1_000
        }
    }

    @Test fun `an alarm that changes its mission each morning can always be finished too, after any restart`() = runBlocking {
        for ((level, anti) in shapes) for (missions in missionLists.filter { it.size >= 2 } + listOf(MissionType.entries.map { MissionConfig(it) }.take(5))) {
            for (restarts in 0..anti.maxResumes) {
                val a = alarm(anti.copy(rotateMissions = true), missions)
                controller.begin(controller.onAlarmFired(a.id, clock.now)!!)
                repeat(restarts) {
                    restart()
                    comeBack()
                }
                assertEquals("$level: one mission a morning", 1, store.current!!.alarm.missions.size)
                assertTrue("$level, rotating ${missions.map { it.type }}, $restarts restarts can be finished", finish())
                assertNull(durable.ring())
                alarms.rows.remove(a.id)
                clock.now += 24 * 3_600_000L
            }
        }
    }

    @Test fun `every limit a restored file can hold ends in a missed alarm, not in a ring that returns for ever`() = runBlocking {
        for (limit in 1..AntiOversleepSettings.MAX_RESUMES_LIMIT) {
            val a = alarm(anti(1_440, limit, strict = true, never = true), listOf(MissionConfig(MissionType.QR_BARCODE)))
            val first = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!
            var comebacks = 0
            while (true) {
                restart()
                comeBack()
                if (store.current == null) break
                comebacks++
                assertTrue("no ring returns more than the limit", comebacks <= limit)
            }
            assertEquals(limit, comebacks)
            assertNull(durable.ring())
            assertEquals(AlarmOutcome.MISSED, history.get(first.eventId)!!.outcome)
            alarms.rows.remove(a.id)
            clock.now += 1_000
        }
    }

    @Test fun `a ring whose window has passed ends at the next restart, however few restarts it has used`() = runBlocking {
        for ((_, anti) in shapes) {
            val a = alarm(anti, listOf(MissionConfig(MissionType.NFC)))
            controller.begin(controller.onAlarmFired(a.id, clock.now)!!)
            restart()
            clock.now += (anti.resumeWindowMinutes + 1) * 60_000L
            comeBack()
            assertNull(store.current)
            assertNull(durable.ring())
            alarms.rows.remove(a.id)
        }
    }

    // ---- missions that cannot be done, the way out, safe mode, Emergency ------------------------------------------

    /**
     * Does what the ring screen does when a mission cannot be done for [reason]: the backup mission stands in. Fails if the
     * backup is empty, endless, or needs anything that could fail in turn.
     */
    private fun backupFor(reason: FallbackReason, ao: AntiOversleepSettings, label: String) {
        val steps = FallbackChain.backup(reason, ao)
        assertTrue("$label: a backup of ${steps.size} steps", steps.size in 1..30)
        for (step in steps) {
            assertTrue("$label: $step needs nothing that can fail", step.type in setOf(MissionType.FOCUS_TAP, MissionType.TYPING, MissionType.MATH) && step.isConfigured)
        }
        controller.noteFallbackUsed()
    }

    @Test fun `every level, every mission and every failure reason still has a way to the end, through restarts`() = runBlocking {
        for ((level, anti) in shapes) for (missions in missionLists.filter { it.isNotEmpty() }) for (reason in FallbackReason.entries) {
            val a = alarm(anti, missions)
            val first = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!
            val label = "$level, ${missions.map { it.type }}, $reason"
            for (index in missions.indices) {
                backupFor(reason, anti, label)
                controller.noteMissionDone(index + 1)
                // A restart halfway costs nothing done.
                if (index == 0 && anti.maxResumes > 0) {
                    restart()
                    comeBack()
                    assertEquals(label, index + 1, store.current!!.missionsDone)
                }
            }
            assertTrue("$label ends", controller.dismiss(RingTarget.of(store.current!!), RingProof.MissionsComplete))
            assertNull(store.current)
            assertNull(durable.ring())
            assertTrue(label, history.get(first.eventId)!!.fallbackUsed)
            alarms.rows.remove(a.id)
            clock.now += 1_000
        }
    }

    /** Takes the way out the alarm has, as the ring screen offers it. Returns whether it ended the ring. */
    private suspend fun takeWayOut(): Boolean {
        val s = store.current!!
        return when (val w = RingPolicy.wayOut(s)) {
            WayOut.None -> false
            is WayOut.Hold -> controller.emergencyStop(RingTarget.of(s), RingProof.Hold)
            is WayOut.Chain -> {
                clock.now = maxOf(clock.now, RingPolicy.wayOutOpensAt(s)!!)
                assertEquals(FallbackChain.wayOut(s.alarm.antiOversleep).size, w.steps)
                for (n in s.wayOutProgress + 1..w.steps) controller.noteWayOutStep(n)
                controller.wayOut(RingTarget.of(store.current!!), RingProof.WayOutComplete)
            }
        }
    }

    @Test fun `at every level an alarm with a mission has a way out that ends it, also after a restart`() = runBlocking {
        for ((level, anti) in shapes) for (missions in missionLists.filter { it.isNotEmpty() }) for (restarts in listOf(0, 1)) {
            val a = alarm(anti, missions)
            val first = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!
            repeat(restarts) {
                restart()
                comeBack()
            }
            assertTrue("$level, ${missions.map { it.type }}: the way out ends it", takeWayOut())
            assertNull(store.current)
            assertEquals(AlarmOutcome.EMERGENCY_STOPPED, history.get(first.eventId)!!.outcome)
            alarms.rows.remove(a.id)
            clock.now += 1_000
        }
    }

    /**
     * With the way out removed (the owner's explicit choice), exactly two exits are left besides doing the mission: the backup
     * mission when a failure is detected, and the cap on restarts. This test says so: if either went, someone could be stuck.
     */
    @Test fun `with the way out removed, the only exits left are the detected-failure backup and the restart cap`() = runBlocking {
        for ((level, base) in shapes) for (missions in missionLists.filter { it.isNotEmpty() }) {
            val anti = base.copy(wayOutRemoved = true)
            val label = "$level without a way out, ${missions.map { it.type }}"

            // No way out: neither a hold nor a chain ends it, however long it rings.
            val a = alarm(anti, missions)
            val ring = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!
            clock.now += 13 * 60 * 60_000L
            assertEquals(label, WayOut.None, RingPolicy.wayOut(store.current!!))
            assertFalse(label, takeWayOut())
            assertFalse(label, controller.emergencyStop(RingTarget.of(ring), RingProof.Hold))
            assertFalse(label, controller.wayOut(RingTarget.of(ring), RingProof.WayOutComplete))
            assertTrue("$label: Emergency is still there", controller.pauseForEmergency(RingTarget.of(ring), RingProof.Gesture))

            // Exit 1: a detected failure gets the backup mission, which ends it.
            for (index in missions.indices) {
                backupFor(FallbackReason.UNSUPPORTED, anti, label)
                controller.noteMissionDone(index + 1)
            }
            assertTrue("$label: exit 1, the backup mission", controller.dismiss(RingTarget.of(store.current!!), RingProof.MissionsComplete))
            alarms.rows.remove(a.id)
            clock.now += 1_000

            // Exit 2: a failure nobody can detect (a lost code): the restart cap ends it, as missed.
            val b = alarm(anti, missions)
            val second = controller.begin(controller.onAlarmFired(b.id, clock.now)!!)!!
            var comebacks = 0
            while (true) {
                restart()
                comeBack()
                if (store.current == null) break
                comebacks++
                assertTrue("$label: never more than the cap", comebacks <= anti.maxResumes)
            }
            assertEquals("$label: exit 2, the restart cap", anti.maxResumes, comebacks)
            assertEquals(AlarmOutcome.MISSED, history.get(second.eventId)!!.outcome)
            assertNull(durable.ring())
            alarms.rows.remove(b.id)
            clock.now += 1_000
        }
    }

    @Test fun `in safe mode every level and every mission ends on the plain screen, and never with one tap if it has a mission`() = runBlocking {
        for ((level, anti) in shapes) for (missions in missionLists) for (removed in listOf(false, true)) {
            runtime.recordRingCrash(clock.now - 2_000)
            runtime.recordRingCrash(clock.now - 1_000)
            val a = alarm(anti.copy(wayOutRemoved = removed), missions)
            val s = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!
            val label = "$level, ${missions.map { it.type }}, way out removed: $removed"
            assertTrue(label, s.safeMode)
            if (s.alarm.dismissesWithOneTap) {
                assertTrue(label, controller.dismiss(RingTarget.of(s)))
            } else {
                assertFalse("$label: no one-tap", controller.dismiss(RingTarget.of(s)))
                val sums = RingPolicy.safeSums(s)
                assertTrue(label, sums in 1..20)
                for (n in 1..sums) controller.noteSafeSum(n)
                assertTrue(label, controller.dismiss(RingTarget.of(s), RingProof.SafeSumsComplete))
            }
            assertNull(label, store.current)
            alarms.rows.remove(a.id)
            clock.now += 11 * 60_000L
        }
    }

    @Test fun `Emergency pauses, however many, never stand in the way of an end`() = runBlocking {
        for ((level, anti) in shapes) for (missions in missionLists) {
            val a = alarm(anti, missions)
            controller.begin(controller.onAlarmFired(a.id, clock.now)!!)
            repeat(6) {
                assertTrue(level, controller.pauseForEmergency(RingTarget.of(store.current!!), RingProof.Gesture))
                clock.now += 60_000
                controller.tick()
            }
            assertTrue("$level, ${missions.map { it.type }} after six pauses", finish())
            assertNull(store.current)
            alarms.rows.remove(a.id)
            clock.now += 1_000
        }
    }
}
