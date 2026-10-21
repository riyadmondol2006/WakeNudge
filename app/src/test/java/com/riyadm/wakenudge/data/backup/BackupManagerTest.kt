package com.riyadm.wakenudge.data.backup

import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.domain.model.StopwatchState
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {

    private fun ImportResult.success(): ImportResult.Success = this as? ImportResult.Success
        ?: error("expected success but got $this")

    private fun ImportResult.failure(): ImportFailure = (this as? ImportResult.Failure)?.reason
        ?: error("expected failure but got $this")

    private fun ImportResult.Success.has(message: Int) = warnings.any { it.message == message }

    // ---- round trip ---------------------------------------------------------------------------------------------

    @Test fun `exporting then importing on a fresh install reproduces everything exactly`() = runTest {
        val source = BackupRig(Sample.file)
        val json = source.manager.export(includeHistory = true)

        val target = BackupRig()
        val result = target.manager.import(json).success()

        val restored = target.store.contents
        assertEquals(Sample.alarms, restored.alarms)
        assertEquals(listOf(Sample.group), restored.groups)
        assertEquals(Sample.settings, restored.settings)
        assertEquals(Sample.file.worldClocks, restored.worldClocks)
        assertEquals(Sample.file.exceptionDates, restored.exceptionDates)
        assertEquals(Sample.history, restored.history)
        assertEquals(ImportCounts(alarms = 3, groups = 1, worldClocks = 2, exceptionDates = 2, history = 2), result.counts)
    }

    @Test fun `a restore re-arms every alarm and redraws the surfaces`() = runTest {
        val rig = BackupRig()
        rig.manager.import(rig.manager.export(false)).success()
        assertEquals(listOf("restore"), rig.scheduler.reasons)
        assertEquals(1, rig.surfaces.refreshes)
    }

    @Test fun `export is pretty printed json carrying the format header`() = runTest {
        val json = BackupRig(Sample.file).manager.export(includeHistory = false)
        assertTrue("indented", json.lines().size > 20)
        assertTrue(json.contains("\"app\": \"WakeNudge\""))
        assertTrue(json.contains("\"formatVersion\": 1"))
        assertTrue(json.contains("\"createdAt\""))
    }

    @Test fun `history is only exported when asked for and a backup without it keeps the stored history`() = runTest {
        val rig = BackupRig(Sample.file)
        val withoutHistory = rig.manager.export(includeHistory = false)
        assertFalse(withoutHistory.contains("\"history\""))

        val result = rig.manager.import(withoutHistory).success()
        assertNull(result.counts.history)
        assertEquals("existing history survives", Sample.history, rig.store.contents.history)
    }

    @Test fun `a running stopwatch is not part of a backup`() = runTest {
        val running = Sample.file.copy(settings = Sample.settings.copy(stopwatch = StopwatchState(running = true, startedAtMillis = 5, laps = listOf(1))))
        val rig = BackupRig(running)
        val restored = BackupRig().also { it.manager.import(rig.manager.export(false)).success() }
        assertEquals(StopwatchState(), restored.store.contents.settings.stopwatch)
    }

    @Test fun `an empty backup restores to an empty app`() = runTest {
        val rig = BackupRig(Sample.file)
        val empty = BackupRig().manager.export(includeHistory = false)
        val result = rig.manager.import(empty).success()
        assertEquals(ImportCounts(0, 0, 0, 0, null), result.counts)
        assertTrue(rig.store.contents.alarms.isEmpty())
        assertTrue(rig.store.contents.groups.isEmpty())
        assertEquals(AppSettings(), rig.store.contents.settings)
    }

    // ---- refusals -----------------------------------------------------------------------------------------------

    @Test fun `text that is not json is refused and nothing changes`() = runTest {
        val rig = BackupRig(Sample.file)
        for (junk in listOf("", "   ", "not json at all", "{\"app\": \"WakeNudge\", ", "\u0000\u0001\u0002")) {
            assertEquals(ImportFailure.NotJson, rig.manager.import(junk).failure())
        }
        assertEquals(0, rig.store.replaceCalls)
        assertEquals(Sample.file, rig.store.contents)
        assertTrue(rig.scheduler.reasons.isEmpty())
        assertEquals(0, rig.surfaces.refreshes)
    }

    @Test fun `json written by another app is refused`() = runTest {
        val rig = BackupRig(Sample.file)
        assertEquals(ImportFailure.WrongApp("SomethingElse"), rig.manager.import("""{"app":"SomethingElse","formatVersion":1}""").failure())
        assertEquals(ImportFailure.WrongApp(null), rig.manager.import("""{"formatVersion":1,"alarms":[]}""").failure())
        assertEquals(ImportFailure.WrongApp(null), rig.manager.import("[1,2,3]").failure())
        assertEquals(ImportFailure.WrongApp(null), rig.manager.import("42").failure())
        assertEquals(0, rig.store.replaceCalls)
    }

    @Test fun `a backup from a newer format is refused with both versions`() = runTest {
        val rig = BackupRig(Sample.file)
        val failure = rig.manager.import("""{"app":"WakeNudge","formatVersion":2,"alarms":[]}""").failure()
        assertEquals(ImportFailure.NewerFormat(found = 2, supported = 1), failure)
        assertEquals(listOf<Any>(2, 1), failure.args)
        assertEquals(Sample.file, rig.store.contents)
    }

    @Test fun `a missing or nonsensical format version is a malformed file`() = runTest {
        val rig = BackupRig()
        assertTrue(rig.manager.import("""{"app":"WakeNudge"}""").failure() is ImportFailure.Malformed)
        assertTrue(rig.manager.import("""{"app":"WakeNudge","formatVersion":"one"}""").failure() is ImportFailure.Malformed)
        assertTrue(rig.manager.import("""{"app":"WakeNudge","formatVersion":0}""").failure() is ImportFailure.Malformed)
        assertTrue(rig.manager.import("""{"app":"WakeNudge","formatVersion":-3}""").failure() is ImportFailure.Malformed)
    }

    @Test fun `an oversized document is refused without parsing`() = runTest {
        val huge = "x".repeat(17 * 1024 * 1024)
        assertEquals(ImportFailure.TooLarge, BackupRig().manager.import(huge).failure())
    }

    @Test fun `a store that fails reports a failure and does not re-arm anything`() = runTest {
        val rig = BackupRig(Sample.file)
        val json = rig.manager.export(true)
        rig.store.failReplaceWith = IllegalStateException("disk full")
        val failure = rig.manager.import(json).failure()
        assertTrue(failure is ImportFailure.StoreFailed)
        assertEquals(listOf<Any>("disk full"), failure.args)
        assertEquals("previous data is untouched", Sample.file, rig.store.contents)
        assertTrue(rig.scheduler.reasons.isEmpty())
        assertEquals(0, rig.surfaces.refreshes)
    }

    @Test fun `a scheduler failure after the restore is a warning, not a failure`() = runTest {
        val rig = BackupRig()
        val json = BackupRig(Sample.file).manager.export(false)
        rig.scheduler.failWith = IllegalStateException("alarm manager unavailable")
        val result = rig.manager.import(json).success()
        assertTrue(result.has(R.string.backup_warn_reschedule_failed))
        assertEquals(Sample.alarms, rig.store.contents.alarms)
    }

    // ---- sanitising ---------------------------------------------------------------------------------------------

    private fun backupJson(alarms: String = "[]", groups: String = "[]", settings: String = "{}", extra: String = ""): String =
        """{"app":"WakeNudge","formatVersion":1,"settings":$settings,"alarms":$alarms,"groups":$groups$extra}"""

    @Test fun `every numeric field is clamped into its valid range`() = runTest {
        val rig = BackupRig()
        val json = backupJson(
            alarms = """[{"id":1,"hour":99,"minute":-5,"repeatDaysMask":65535,
                "volume":{"level":250,"rampSeconds":-4,"rampStartPercent":900},
                "vibration":{"strength":0},
                "snooze":{"minutes":0,"maxCount":-2,"decreaseStepMinutes":99},
                "wakeCheck":{"delayMinutes":500,"responseSeconds":1,"maxChecks":99,"maxReRings":-1},
                "antiOversleep":{"holdToDismissSeconds":999,"backupDelayMinutes":0,"backupCount":40,"editLockMinutes":-5,"ringTimeoutMinutes":0},
                "sunrise":{"leadMinutes":0},"voice":{"repeatSeconds":1},"upcomingNoticeMinutes":99999}]""",
        )
        val result = rig.manager.import(json).success()
        val a = rig.store.contents.alarms.single()

        assertEquals(23, a.hour)
        assertEquals(0, a.minute)
        assertEquals(Alarm.EVERY_DAY_MASK, a.repeatDaysMask)
        assertEquals(100, a.volume.level)
        assertEquals(0, a.volume.rampSeconds)
        assertEquals(100, a.volume.rampStartPercent)
        assertEquals(1, a.vibration.strength)
        assertEquals(1, a.snooze.minutes)
        assertEquals(0, a.snooze.maxCount)
        assertEquals(30, a.snooze.decreaseStepMinutes)
        assertEquals(30, a.wakeCheck.delayMinutes)
        assertEquals(10, a.wakeCheck.responseSeconds)
        assertEquals(5, a.wakeCheck.maxChecks)
        assertEquals(0, a.wakeCheck.maxReRings)
        assertEquals(30, a.antiOversleep.holdToDismissSeconds)
        assertEquals(1, a.antiOversleep.backupDelayMinutes)
        assertEquals(3, a.antiOversleep.backupCount)
        assertEquals(0, a.antiOversleep.editLockMinutes)
        assertEquals(1, a.antiOversleep.ringTimeoutMinutes)
        assertEquals(1, a.sunrise.leadMinutes)
        assertEquals(5, a.voice.repeatSeconds)
        assertEquals(720, a.upcomingNoticeMinutes)
        assertTrue(result.has(R.plurals.backup_warn_clamped))
    }

    @Test fun `unknown enum values fall back to defaults instead of failing the alarm`() = runTest {
        val rig = BackupRig()
        val json = backupJson(
            alarms = """[{"id":1,"hour":7,"sound":{"mode":"TELEPORT","source":"HOLOGRAM","bundledId":"does-not-exist"},
                "vibration":{"pattern":"EARTHQUAKE"}}]""",
            settings = """{"themeMode":"NEON","timeFormat":"H99","audioRoute":"SATELLITE"}""",
        )
        rig.manager.import(json).success()
        val a = rig.store.contents.alarms.single()
        assertEquals(SoundSettings(), a.sound)
        assertEquals(com.riyadm.wakenudge.domain.model.VibrationPattern.PULSE, a.vibration.pattern)
        assertEquals(AppSettings().themeMode, rig.store.contents.settings.themeMode)
        assertEquals(AppSettings().timeFormat, rig.store.contents.settings.timeFormat)
        assertEquals(AppSettings().audioRoute, rig.store.contents.settings.audioRoute)
    }

    @Test fun `alarms pointing at unknown groups are moved out of groups`() = runTest {
        val rig = BackupRig()
        val json = backupJson(
            alarms = """[{"id":1,"groupId":3},{"id":2,"groupId":999},{"id":3}]""",
            groups = """[{"id":3,"name":"Work"}]""",
        )
        val result = rig.manager.import(json).success()
        assertEquals(listOf<Long?>(3L, null, null), rig.store.contents.alarms.map { it.groupId })
        assertTrue(result.has(R.plurals.backup_warn_unknown_group))
    }

    @Test fun `strings are bounded and a split emoji is not left behind`() = runTest {
        val rig = BackupRig()
        val label = "a".repeat(59) + "\uD83D\uDE00\uD83D\uDE00"
        val json = backupJson(
            alarms = """[{"id":1,"label":"$label"}]""",
            groups = """[{"id":1,"name":"${"g".repeat(500)}"}]""",
        )
        rig.manager.import(json).success()
        assertEquals("a".repeat(59), rig.store.contents.alarms.single().label)
        assertEquals(60, rig.store.contents.groups.single().name.length)
    }

    @Test fun `missions are capped in number and count and unknown types are dropped`() = runTest {
        val rig = BackupRig()
        val missions = (1..8).joinToString(",") { """{"type":"MATH","count":$it}""" }
        val json = backupJson(
            alarms = """[{"id":1,"missions":[$missions]},
                {"id":2,"missions":[{"type":"TELEPATHY","count":1},{"type":"SHAKE","count":999999},{"type":"MATH","count":-4}]}]""",
        )
        val result = rig.manager.import(json).success()
        val (first, second) = rig.store.contents.alarms
        assertEquals(com.riyadm.wakenudge.domain.model.MissionConfig.MAX_MISSIONS, first.missions.size)
        assertEquals(listOf(MissionType.SHAKE, MissionType.MATH), second.missions.map { it.type })
        assertEquals(500, second.missions[0].count)
        assertEquals(1, second.missions[1].count)
        assertTrue(result.has(R.plurals.backup_warn_missions_unknown))
        assertTrue(result.has(R.plurals.backup_warn_missions_dropped))
    }

    @Test fun `oversized mission parameters are dropped rather than truncated`() = runTest {
        val rig = BackupRig()
        val long = "z".repeat(9_000)
        val json = backupJson(
            alarms = """[{"id":1,"missions":[{"type":"QR_BARCODE","params":{"code":"$long","codeLabel":"door","puzzle":"nope"}}]}]""",
        )
        val result = rig.manager.import(json).success()
        val mission = rig.store.contents.alarms.single().missions.single()
        assertEquals(mapOf("codeLabel" to "door"), mission.params)
        assertTrue("setup is gone, so the user is told", result.has(R.string.backup_warn_mission_qr))
    }

    @Test fun `duplicate and invalid ids are renumbered without losing alarms`() = runTest {
        val rig = BackupRig()
        val json = backupJson(alarms = """[{"id":4,"hour":1},{"id":4,"hour":2},{"id":0,"hour":3},{"id":-7,"hour":4}]""")
        val result = rig.manager.import(json).success()
        val alarms = rig.store.contents.alarms
        assertEquals(listOf(1, 2, 3, 4), alarms.map { it.hour })
        assertEquals(4, alarms.map { it.id }.toSet().size)
        assertTrue(alarms.all { it.id > 0 })
        assertEquals(4L, alarms[0].id)
        assertTrue(result.has(R.plurals.backup_warn_renumbered))
    }

    @Test fun `one unreadable alarm is skipped and the rest are restored`() = runTest {
        val rig = BackupRig()
        val json = backupJson(alarms = """[{"id":1,"hour":7},{"id":2,"hour":"early"},"junk",{"id":3,"hour":9}]""")
        val result = rig.manager.import(json).success()
        assertEquals(listOf(7, 9), rig.store.contents.alarms.map { it.hour })
        val warning = result.warnings.first { it.message == R.plurals.backup_warn_alarms_skipped }
        assertEquals(2, warning.count)
    }

    @Test fun `unreadable settings fall back to defaults with a warning`() = runTest {
        val rig = BackupRig(Sample.file)
        val result = rig.manager.import(backupJson(settings = """{"vacation":"yes please"}""")).success()
        assertEquals(AppSettings(), rig.store.contents.settings)
        assertTrue(result.has(R.string.backup_warn_settings_unreadable))
    }

    @Test fun `a one-time alarm with an impossible date is switched off instead of guessed`() = runTest {
        val rig = BackupRig()
        val result = rig.manager.import(backupJson(alarms = """[{"id":1,"oneTimeEpochDay":9999999999,"enabled":true}]""")).success()
        val a = rig.store.contents.alarms.single()
        assertNull(a.oneTimeEpochDay)
        assertFalse(a.enabled)
        assertTrue(result.has(R.plurals.backup_warn_switched_off))
    }

    @Test fun `world clocks with unknown zones and duplicates are skipped`() = runTest {
        val rig = BackupRig()
        val json = backupJson(
            extra = ""","worldClocks":[{"zoneId":"Asia/Tokyo","label":"Tokyo"},{"zoneId":"Mars/Olympus","label":"Mars"},
                {"zoneId":"Asia/Tokyo","label":"Again"}],"exceptionDates":[{"epochDay":20450},{"epochDay":20450},{"epochDay":-5}]""",
        )
        val result = rig.manager.import(json).success()
        assertEquals(listOf("Asia/Tokyo"), rig.store.contents.worldClocks.map { it.zoneId })
        assertEquals(listOf(20_450L), rig.store.contents.exceptionDates.map { it.epochDay })
        assertTrue(result.has(R.plurals.backup_warn_clocks_skipped))
        assertTrue(result.has(R.plurals.backup_warn_dates_skipped))
    }

    @Test fun `history entries still marked as ringing are closed as missed`() = runTest {
        val rig = BackupRig()
        val json = backupJson(extra = ""","history":[{"id":1,"alarmId":1,"label":"x","scheduledAt":10,"firedAt":20,"outcome":"RINGING"}]""")
        rig.manager.import(json).success()
        val event = rig.store.contents.history!!.single()
        assertEquals(AlarmOutcome.MISSED, event.outcome)
        assertEquals(20L, event.endedAt)
    }

    @Test fun `a byte order mark in front of the json is tolerated`() = runTest {
        val rig = BackupRig()
        rig.manager.import("\uFEFF" + BackupRig(Sample.file).manager.export(true)).success()
        assertEquals(Sample.alarms, rig.store.contents.alarms)
    }

    // ---- warnings -----------------------------------------------------------------------------------------------

    @Test fun `custom sound uris from another device produce one warning`() = runTest {
        val rig = BackupRig()
        val json = backupJson(
            alarms = """[{"id":1,"sound":{"source":"CUSTOM","uri":"content://media/external/audio/media/41"}},
                {"id":2,"sound":{"mode":"RANDOM_CUSTOM","playlist":["content://com.android.providers.media.documents/document/audio%3A9"]}},
                {"id":3}]""",
        )
        val result = rig.manager.import(json).success()
        val warning = result.warnings.single { it.message == R.plurals.backup_warn_device_sounds }
        assertEquals(2, warning.count)
    }

    @Test fun `missions that need setup but carry none are called out per alarm`() = runTest {
        val rig = BackupRig()
        val json = backupJson(
            alarms = """[{"id":1,"label":"Gym","missions":[{"type":"NFC"},{"type":"PHOTO","params":{"signature":""}},{"type":"MATH"}]},
                {"id":2,"hour":6,"minute":5,"snoozeMissions":[{"type":"QR_BARCODE"}]},
                {"id":3,"missions":[{"type":"NFC","params":{"tagId":"04:AA"}}]}]""",
        )
        val result = rig.manager.import(json).success()
        val nfc = result.warnings.single { it.message == R.string.backup_warn_mission_nfc }
        val photo = result.warnings.single { it.message == R.string.backup_warn_mission_photo }
        val qr = result.warnings.single { it.message == R.string.backup_warn_mission_qr }
        assertEquals(listOf<Any>("Gym"), nfc.args)
        assertEquals(listOf<Any>("Gym"), photo.args)
        assertEquals("an unlabeled alarm is named by its time", listOf<Any>("06:05"), qr.args)
    }

    @Test fun `a clean backup restores without warnings`() = runTest {
        val rig = BackupRig(Sample.file.copy(alarms = Sample.alarms.take(2).map { it.copy(missions = emptyList(), snoozeMissions = emptyList()) }))
        val result = BackupRig().manager.import(rig.manager.export(true)).success()
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
    }

    // ---- files --------------------------------------------------------------------------------------------------

    @Test fun `the suggested file name carries the local date and time`() {
        val now = ZonedDateTime.of(2026, 10, 21, 7, 5, 59, 0, ZoneId.of("Asia/Dhaka"))
        assertEquals("wakenudge-backup-20261021-0705.json", BackupRig().manager.suggestedFileName(now))
    }

    @Test fun `export stamps the build and the time`() = runTest {
        val json = BackupRig(Sample.file).manager.export(false)
        val parsed = BackupCodec.parse(json) as ParseOutcome.Parsed
        assertEquals(FixedTime().nowMillis(), parsed.file.createdAt)
        assertNotNull(parsed.file.appVersion)
        assertEquals(1, parsed.file.formatVersion)
    }
}
