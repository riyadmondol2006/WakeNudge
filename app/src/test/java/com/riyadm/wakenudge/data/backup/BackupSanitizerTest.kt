package com.riyadm.wakenudge.data.backup

import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmDefaults
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.SoundMode
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.domain.model.SoundSource
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.model.VibrationPattern
import com.riyadm.wakenudge.domain.model.VibrationSettings
import com.riyadm.wakenudge.domain.model.VolumeKeyAction
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.domain.model.WakeCheckSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSanitizerTest {

    private fun sanitize(file: BackupFile) = BackupSanitizer.sanitize(file)

    @Test fun `valid data passes through untouched and without warnings`() {
        val result = sanitize(Sample.file)
        assertEquals(Sample.file, result.file)
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
    }

    @Test fun `sanitising twice changes nothing more`() {
        val messy = BackupFile(
            alarms = listOf(
                Alarm(id = 1, hour = 40, minute = 90, repeatDaysMask = 999, groupId = 12),
                Alarm(id = 1, label = "x".repeat(200)),
            ),
            groups = listOf(AlarmGroup(id = 0, name = "y".repeat(100), accent = -3)),
        )
        val once = sanitize(messy).file
        assertEquals(once, sanitize(once).file)
    }

    @Test fun `extra loud strength from a backup is clamped and old backups keep full strength`() {
        val file = BackupFile(
            alarms = listOf(
                Alarm(id = 1, volume = VolumeSettings(extraLoud = true, extraLoudPercent = 9_999)),
                Alarm(id = 2, volume = VolumeSettings(extraLoud = true, extraLoudPercent = -40)),
                Alarm(id = 3, volume = VolumeSettings(extraLoud = true)),
            ),
        )
        assertEquals(listOf(100, 1, 100), sanitize(file).file.alarms.map { it.volume.extraLoudStrength })
    }

    @Test fun `a custom vibration pattern with no usable timings falls back to pulse`() {
        val file = BackupFile(
            alarms = listOf(
                Alarm(id = 1, vibration = VibrationSettings(pattern = VibrationPattern.CUSTOM, customTimings = emptyList())),
                Alarm(id = 2, vibration = VibrationSettings(pattern = VibrationPattern.CUSTOM, customTimings = listOf(0, 0))),
                Alarm(id = 3, vibration = VibrationSettings(pattern = VibrationPattern.CUSTOM, customTimings = listOf(0, 250, 100, 250))),
            ),
        )
        assertEquals(
            listOf(VibrationPattern.PULSE, VibrationPattern.PULSE, VibrationPattern.CUSTOM),
            sanitize(file).file.alarms.map { it.vibration.pattern },
        )
    }

    @Test fun `custom timings are bounded in length and duration`() {
        val timings = List(200) { 99_999L }
        val cleaned = sanitize(BackupFile(alarms = listOf(Alarm(id = 1, vibration = VibrationSettings(pattern = VibrationPattern.CUSTOM, customTimings = timings))))).file
        val result = cleaned.alarms.single().vibration.customTimings
        assertEquals(64, result.size)
        assertTrue(result.all { it == 10_000L })
    }

    @Test fun `a sound without a file falls back to a bundled tone and an empty playlist to single mode`() {
        val file = BackupFile(
            alarms = listOf(
                Alarm(id = 1, sound = SoundSettings(source = SoundSource.CUSTOM, uri = null)),
                Alarm(id = 2, sound = SoundSettings(mode = SoundMode.RANDOM_CUSTOM, playlist = emptyList())),
                Alarm(id = 3, sound = SoundSettings(source = SoundSource.SYSTEM, uri = "x".repeat(5_000))),
            ),
        )
        val sounds = sanitize(file).file.alarms.map { it.sound }
        assertEquals(SoundSource.BUNDLED, sounds[0].source)
        assertEquals(SoundMode.SINGLE, sounds[1].mode)
        assertEquals(SoundSource.BUNDLED, sounds[2].source)
        assertEquals(null, sounds[2].uri)
    }

    @Test fun `the number of alarms is capped`() {
        val many = List(BackupSanitizer.MAX_ALARMS + 25) { Alarm(id = it + 1L) }
        val result = sanitize(BackupFile(alarms = many))
        assertEquals(BackupSanitizer.MAX_ALARMS, result.file.alarms.size)
        assertTrue(result.warnings.any { it.message == com.riyadm.wakenudge.R.plurals.backup_warn_too_many_alarms })
    }

    @Test fun `a snooze or backup still pending in the file is not brought to this phone`() {
        // Made a few minutes after a snoozed ring on the old phone: on the new one it would re-arm and ring out of nowhere.
        val pending = AlarmEvent(
            id = 1, alarmId = 1, label = "", scheduledAt = 1_000, firedAt = 1_000,
            outcome = AlarmOutcome.RINGING, snoozeUntil = 9_000_000_000_000, pendingBackups = listOf(9_000_000_000_000),
        )
        val kept = sanitize(BackupFile(history = listOf(pending))).file.history!!.single()
        assertEquals(0L, kept.snoozeUntil)
        assertEquals(emptyList<Long>(), kept.pendingBackups)
        assertEquals(AlarmOutcome.MISSED, kept.outcome)
    }

    @Test fun `a vacation with an unreadable end date is switched off instead of pausing alarms for ever`() {
        val broken = Sample.settings.copy(vacation = VacationMode(enabled = true, resumeEpochDay = Long.MAX_VALUE))
        val result = sanitize(Sample.file.copy(settings = broken))
        assertEquals(VacationMode(), result.file.settings.vacation)
        assertTrue(result.warnings.any { it.message == com.riyadm.wakenudge.R.string.backup_warn_vacation })
        // A readable end date, or a vacation with none at all, is kept as it is.
        val fine = Sample.settings.copy(vacation = VacationMode(enabled = true, resumeEpochDay = 20_500))
        assertEquals(fine.vacation, sanitize(Sample.file.copy(settings = fine)).file.settings.vacation)
        val open = Sample.settings.copy(vacation = VacationMode(enabled = true, resumeEpochDay = null))
        assertEquals(open.vacation, sanitize(Sample.file.copy(settings = open)).file.settings.vacation)
    }

    @Test fun `an oversized history keeps the newest entries`() {
        val events = List(BackupSanitizer.MAX_HISTORY + 10) { AlarmEvent(id = it + 1L, alarmId = 1, label = "", scheduledAt = it.toLong(), firedAt = it.toLong()) }
        val kept = sanitize(BackupFile(history = events)).file.history!!
        assertEquals(BackupSanitizer.MAX_HISTORY, kept.size)
        assertEquals(events.last().firedAt, kept.first().firedAt)
        assertTrue(kept.none { it.firedAt < 10 })
    }

    @Test fun `bedtime and general settings are clamped`() {
        val settings = Sample.settings.copy(
            headphoneCapPercent = 3,
            emergencyStopHoldSeconds = 99,
            bedtime = Sample.settings.bedtime.copy(hour = 30, minute = 99, daysMask = 1 shl 12, sleepGoalMinutes = 5),
        )
        val cleaned = sanitize(BackupFile(settings = settings)).file.settings
        assertEquals(10, cleaned.headphoneCapPercent)
        assertEquals(10, cleaned.emergencyStopHoldSeconds)
        assertEquals(23, cleaned.bedtime.hour)
        assertEquals(59, cleaned.bedtime.minute)
        assertEquals(0, cleaned.bedtime.daysMask)
        assertEquals(120, cleaned.bedtime.sleepGoalMinutes)
    }

    @Test fun `update-check state in a file is not taken over and does not warn`() {
        val dictated = Sample.settings.copy(updateChecksEnabled = false, lastUpdateCheckAt = 1_700_000_000_000, skippedUpdateVersion = "99.0.0")
        val result = sanitize(BackupFile(settings = dictated))
        assertEquals(true, result.file.settings.updateChecksEnabled)
        assertEquals(0L, result.file.settings.lastUpdateCheckAt)
        assertEquals("", result.file.settings.skippedUpdateVersion)
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
        assertEquals(result.file, sanitize(result.file).file)
    }

    @Test fun `the per-alarm ring settings are clamped to what the engine supports`() {
        val low = AntiOversleepSettings(volumeFloorPercent = -5, muteBudget = -1, muteSeconds = 1, wayOutDelayMinutes = -1, wayOutCount = 0, fallbackCount = 0, resumeWindowMinutes = -9)
        val high = AntiOversleepSettings(volumeFloorPercent = 900, muteBudget = 99, muteSeconds = 999, wayOutDelayMinutes = 99, wayOutCount = 99, fallbackCount = 99, resumeWindowMinutes = 99_999)
        val (a, b) = sanitize(BackupFile(alarms = listOf(Alarm(id = 1, antiOversleep = low), Alarm(id = 2, antiOversleep = high)))).file.alarms.map { it.antiOversleep }
        assertEquals(AntiOversleepSettings(volumeFloorPercent = 0, muteBudget = 0, muteSeconds = 5, wayOutDelayMinutes = 0, wayOutCount = 3, fallbackCount = 1, resumeWindowMinutes = 0), a)
        assertEquals(AntiOversleepSettings(volumeFloorPercent = 100, muteBudget = 5, muteSeconds = 60, wayOutDelayMinutes = 30, wayOutCount = 30, fallbackCount = 20, resumeWindowMinutes = 1_440), b)
    }

    @Test fun `the defaults for new alarms are clamped the same way`() {
        val settings = Sample.settings.copy(defaults = AlarmDefaults(antiOversleep = AntiOversleepSettings(wayOutCount = 0, muteSeconds = 0)))
        val cleaned = sanitize(BackupFile(settings = settings)).file.settings.defaults.antiOversleep
        assertEquals(3, cleaned.wayOutCount)
        assertEquals(5, cleaned.muteSeconds)
    }

    @Test fun `a strict alarm is never restored without a way out it can finish`() {
        val r = kotlin.random.Random(41)
        repeat(500) {
            val anti = AntiOversleepSettings(emergencyStopEnabled = false, wayOutCount = r.nextInt(-1000, 1000), wayOutDelayMinutes = r.nextInt(-1000, 1000))
            val cleaned = sanitize(BackupFile(alarms = listOf(Alarm(id = 1, antiOversleep = anti)))).file.alarms.single().antiOversleep
            assertTrue("way out of ${cleaned.wayOutCount} sums", cleaned.wayOutCount in 3..30)
            assertTrue("way out after ${cleaned.wayOutDelayMinutes} min", cleaned.wayOutDelayMinutes in 0..30)
        }
    }

    @Test fun `a ring settings value already in range restores unchanged and without a warning`() {
        val anti = AntiOversleepSettings(
            volumeFloorPercent = 50, volumeKeyAction = VolumeKeyAction.NOTHING, returnToAlarm = false, forceReturn = true, muteBudget = 1, muteSeconds = 15,
            wayOutDelayMinutes = 10, wayOutCount = 20, fallbackCount = 8, calmOnlyOnProgress = true, resumeWindowMinutes = 720, lockOverrideMission = true,
            powerGuard = true, fullBrightness = true, forceSpeaker = true, torchWhenIgnored = true,
        )
        val file = BackupFile(alarms = listOf(Alarm(id = 1, antiOversleep = anti, wakeCheck = WakeCheckSettings(requireProof = true))))
        val result = sanitize(file)
        assertEquals(file.alarms, result.file.alarms)
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
    }
}
