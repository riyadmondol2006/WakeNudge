package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.data.backup.RecordingScheduler
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.engine.policy.RingProof
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A ring of an alarm that changes its mission each morning, through the real controller. */
class RotationRingTest {
    private val clock = TestClock()
    private val alarms = MemoryAlarms()
    private val history = MemoryHistory()
    private val store = RingSessionStore()
    private var boots = 1
    private val runtime = realRuntime().also { it.bootCountSource = { boots } }
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
    }
    private var reRings = 0
    private val wakeChecks = mockk<WakeCheckController>(relaxed = true).also { coEvery { it.reRingsFor(any(), any()) } answers { reRings } }
    private val log = SilentLog()
    private fun newController() = RingController(
        store, alarms, history, MemoryTimers(), MemorySettings(), RecordingScheduler(), wakeChecks, pending, runtime, clock, mockk<Notifier>(relaxed = true), CountingSurfaces(), log,
    )
    private var controller = newController()

    private val three = listOf(
        MissionConfig(MissionType.QR_BARCODE, params = mapOf(MissionConfig.PARAM_CODE to "kitchen", MissionConfig.PARAM_CODE_LABEL to "Kitchen")),
        MissionConfig(MissionType.QR_BARCODE, params = mapOf(MissionConfig.PARAM_CODE to "bath", MissionConfig.PARAM_CODE_LABEL to "Bathroom")),
        MissionConfig(MissionType.QR_BARCODE, params = mapOf(MissionConfig.PARAM_CODE to "hall", MissionConfig.PARAM_CODE_LABEL to "Hall")),
    )

    private fun rotating(snooze: Boolean = false) = alarms.add(
        Alarm(
            label = "work", hour = 7, minute = 0, repeatDaysMask = Alarm.EVERY_DAY_MASK, missions = three,
            snooze = SnoozeSettings(enabled = snooze, maxCount = 3),
            antiOversleep = AntiOversleepSettings(rotateMissions = true, ringTimeoutMinutes = 0, resumeWindowMinutes = 720, backupEnabled = false),
        ),
    )

    @Test fun `the ring asks for one of the missions and ends once that one is done, not before`() = runBlocking {
        val a = rotating()
        val s = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!
        assertEquals(1, s.alarm.missions.size)
        assertTrue(s.alarm.missions.single() in three)
        assertFalse(controller.dismiss(RingTarget.of(s), RingProof.MissionsComplete))
        controller.noteMissionDone(2)
        assertFalse("there is no second mission to do", controller.dismiss(RingTarget.of(s), RingProof.MissionsComplete))
        controller.noteMissionDone(1)
        assertTrue(controller.dismiss(RingTarget.of(store.current!!), RingProof.MissionsComplete))
        val event = history.get(s.eventId)!!
        assertEquals(1, event.missionsDone)
        assertEquals(1, event.missionsTotal)
    }

    @Test fun `consecutive mornings ask for different codes`() = runBlocking {
        val a = rotating()
        val picked = (0 until 6).map { day ->
            val s = controller.begin(controller.onAlarmFired(a.id, clock.now + day * 24 * 3_600_000L)!!)!!
            controller.noteMissionDone(1)
            controller.dismiss(RingTarget.of(s), RingProof.MissionsComplete)
            s.alarm.missions.single()
        }
        picked.zipWithNext().forEach { (x, y) -> assertNotEquals(x, y) }
    }

    @Test fun `a snooze coming back asks for the same mission`() = runBlocking {
        val a = rotating(snooze = true)
        val first = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!
        controller.snooze(RingTarget.of(first), RingProof.Gesture)
        assertNull(store.current)
        clock.now += 5 * 60_000L
        val back = controller.begin(controller.onSnoozeExpired(a.id, first.eventId)!!)!!
        assertEquals(first.alarm.missions, back.alarm.missions)
    }

    @Test fun `a restart keeps the same mission, and the progress on it`() = runBlocking {
        val a = rotating()
        val first = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!
        store.set(null)
        controller = newController()
        boots++
        clock.restart()
        assertTrue(controller.reviveDurableRing())
        val back = controller.begin(controller.onSentinelFired()!!)!!
        assertEquals(first.alarm.missions, back.alarm.missions)
        controller.noteMissionDone(1)
        assertTrue(controller.dismiss(RingTarget.of(back), RingProof.MissionsComplete))
    }

    @Test fun `a still-awake re-ring moves on to the next mission`() = runBlocking {
        val a = rotating()
        val first = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!
        controller.noteMissionDone(1)
        controller.dismiss(RingTarget.of(first), RingProof.MissionsComplete)
        reRings = 1
        val again = controller.begin(RingRequest(a.id, first.eventId, first.scheduledAt, RingKind.WAKE_CHECK_RERING))!!
        assertNotEquals(first.alarm.missions, again.alarm.missions)
    }

    @Test fun `without the switch every mission is asked for, in order`() = runBlocking {
        val a = alarms.add(rotating().copy(id = 0, antiOversleep = AntiOversleepSettings(rotateMissions = false)))
        val s = controller.begin(controller.onAlarmFired(a.id, clock.now)!!)!!
        assertEquals(three, s.alarm.missions)
    }
}
