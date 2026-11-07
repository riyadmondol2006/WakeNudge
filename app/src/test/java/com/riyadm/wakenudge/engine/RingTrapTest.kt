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
import com.riyadm.wakenudge.engine.policy.RingProof
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

    private fun anti(window: Int, limit: Int, strict: Boolean, never: Boolean) = AntiOversleepSettings(
        ringTimeoutMinutes = if (never) AntiOversleepSettings.RING_TIMEOUT_NEVER else 10,
        resumeWindowMinutes = window, maxResumes = limit, emergencyStopEnabled = !strict, backupEnabled = false,
    )

    /** The settings of section 2 that matter to whether a ring can end, one per level. */
    private val shapes: Map<String, AntiOversleepSettings> = mapOf(
        "Easy" to anti(10, 3, strict = false, never = false),
        "Normal" to anti(120, 3, strict = false, never = true),
        "Hard" to anti(720, 5, strict = true, never = true),
        "No mercy" to anti(720, 10, strict = true, never = true),
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
}
