package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.engine.protection.RehearsalRecord
import com.riyadm.wakenudge.engine.protection.Rehearsals
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RehearsalAndPreflightTest {
    private val runtime = realRuntime()
    private val pending = mockk<PendingAlarms>(relaxed = true).also { every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true }
    private val rehearsals = Rehearsals(runtime, pending, TestClock(), SilentLog())
    private val qr = MissionConfig(MissionType.QR_BARCODE, params = mapOf(MissionConfig.PARAM_CODE to "abc"))
    private val alarm = Alarm(id = 4, missions = listOf(qr), antiOversleep = AntiOversleepSettings(emergencyStopEnabled = false, wayOutRemoved = true, ringTimeoutMinutes = 0))

    private fun finishRehearsal(of: Alarm) {
        rehearsals.start(of)
        RehearsalRecord(runtime).ended(Rehearsals.copyFor(of), finished = true)
    }

    @Test fun `a rehearsal rings a copy with no snooze, no backup, its way out and a timeout`() {
        val copy = Rehearsals.copyFor(alarm.copy(snooze = SnoozeSettings(enabled = true)))
        assertEquals(RingIds.TEST_ALARM_ID, copy.id)
        assertEquals(alarm.missions, copy.missions)
        assertFalse(copy.snooze.enabled)
        assertFalse(copy.antiOversleep.backupEnabled)
        assertFalse("a trial always keeps its way out", copy.antiOversleep.wayOutRemoved)
        assertEquals(Rehearsals.TIMEOUT_MINUTES, copy.antiOversleep.ringTimeoutMinutes)
    }

    @Test fun `only a finished rehearsal counts, and changing the missions needs a new one`() {
        assertFalse(rehearsals.isRehearsed(alarm))
        rehearsals.start(alarm)
        RehearsalRecord(runtime).ended(Rehearsals.copyFor(alarm), finished = false)
        assertFalse("ended by the way out or a timeout", rehearsals.isRehearsed(alarm))
        assertFalse(rehearsals.anyFinished)

        finishRehearsal(alarm)
        assertTrue(rehearsals.isRehearsed(alarm))
        assertTrue(rehearsals.anyFinished)
        assertFalse("another code", rehearsals.isRehearsed(alarm.copy(missions = listOf(qr.copy(params = mapOf(MissionConfig.PARAM_CODE to "xyz"))))))
        assertFalse("another alarm", rehearsals.isRehearsed(alarm.copy(id = 5)))
    }

    @Test fun `a plain test alarm after an unfinished rehearsal is not counted as one`() {
        rehearsals.start(alarm)
        runtime.rehearsalOf = -1 // what scheduleTestAlarm does
        RehearsalRecord(runtime).ended(Alarm(id = RingIds.TEST_ALARM_ID), finished = true)
        assertFalse(rehearsals.anyFinished)
    }

    @Test fun `an unreadable record means not rehearsed, never a crash`() {
        runtime.rehearsedRaw = "garbage,::,4:,x:y"
        assertFalse(rehearsals.isRehearsed(alarm))
        finishRehearsal(alarm)
        assertTrue(rehearsals.isRehearsed(alarm))
    }

    // ---- preflight on save ---------------------------------------------------------------------------------------

    private fun preflight(a: Alarm, ready: MissionReadiness = MissionReadiness(), activity: Boolean = true, reader: Boolean = false) =
        PreflightPolicy.problems(a, { ready }, activity, reader).map { it.kind to it.mission }

    @Test fun `preflight names each problem with the mission, and nothing when all is well`() {
        assertEquals(emptyList<Any>(), preflight(alarm))
        assertEquals(listOf(PreflightKind.NEEDS_CAMERA to MissionType.QR_BARCODE), preflight(alarm, MissionReadiness(permissionMissing = true)))
        assertEquals(listOf(PreflightKind.NO_HARDWARE to MissionType.QR_BARCODE), preflight(alarm, MissionReadiness(hardwareMissing = true, permissionMissing = true)))
        assertEquals(listOf(PreflightKind.NOT_SET_UP to MissionType.QR_BARCODE), preflight(alarm.copy(missions = listOf(MissionConfig(MissionType.QR_BARCODE)))))
        val nfc = alarm.copy(missions = listOf(MissionConfig(MissionType.NFC, params = mapOf(MissionConfig.PARAM_TAG_ID to "t"))))
        assertEquals(listOf(PreflightKind.NFC_OFF to MissionType.NFC), preflight(nfc, MissionReadiness(nfcOff = true)))
    }

    @Test fun `steps without the activity permission, and motion missions with a screen reader, are worth a word`() {
        val steps = alarm.copy(missions = listOf(MissionConfig(MissionType.STEPS), MissionConfig(MissionType.MATH)))
        assertEquals(listOf(PreflightKind.NEEDS_ACTIVITY to MissionType.STEPS), preflight(steps, activity = false))
        val shake = alarm.copy(missions = listOf(MissionConfig(MissionType.SHAKE), MissionConfig(MissionType.TYPING)))
        assertEquals(listOf(PreflightKind.MOTION_WITH_SCREEN_READER to MissionType.SHAKE), preflight(shake, reader = true))
        assertEquals(emptyList<Any>(), preflight(shake, reader = false))
    }

    @Test fun `preflight and tonight's check judge a mission the same way`() {
        for (ready in listOf(MissionReadiness(), MissionReadiness(permissionMissing = true), MissionReadiness(nfcOff = true), MissionReadiness(hardwareMissing = true))) {
            for (m in listOf(qr, MissionConfig(MissionType.QR_BARCODE), MissionConfig(MissionType.NFC))) {
                val issue = MissionChecks.issue(m, ready)
                val pre = PreflightPolicy.problems(alarm.copy(missions = listOf(m)), { ready }, true, false).map { it.kind }
                assertEquals("$m $ready", issue != null, pre.isNotEmpty())
            }
        }
    }
}
