package com.riyadm.wakenudge.data.backup

import android.content.Context
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.AudioRoute
import com.riyadm.wakenudge.domain.model.BedtimeSettings
import com.riyadm.wakenudge.domain.model.CalendarExceptions
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.ExceptionDate
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.model.SoundMode
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.domain.model.SoundSource
import com.riyadm.wakenudge.domain.model.ThemeMode
import com.riyadm.wakenudge.domain.model.TimeFormat
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.model.VibrationPattern
import com.riyadm.wakenudge.domain.model.VibrationSettings
import com.riyadm.wakenudge.domain.model.VoiceSettings
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.domain.model.WakeCheckSettings
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.domain.model.WorldClockCity
import com.riyadm.wakenudge.domain.repository.DiagnosticRecord
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.NextAlarm
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import io.mockk.mockk
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory stand-in for the Room-backed store, with the same replace-everything-or-nothing contract. */
class FakeBackupStore(var contents: BackupFile = BackupFile()) : BackupStore {
    var failReplaceWith: Throwable? = null
    var replaceCalls = 0

    override suspend fun read(includeHistory: Boolean): BackupFile =
        contents.copy(history = if (includeHistory) contents.history.orEmpty() else null)

    override suspend fun replace(file: BackupFile) {
        replaceCalls++
        failReplaceWith?.let { throw it }
        contents = file.copy(history = file.history ?: contents.history)
    }
}

class RecordingScheduler : AlarmScheduler {
    val reasons = mutableListOf<String>()
    var failWith: Throwable? = null

    override suspend fun rescheduleAll(reason: String) {
        reasons += reason
        failWith?.let { throw it }
    }

    override suspend fun schedule(alarmId: Long, afterMillis: Long) = Unit
    override suspend fun cancel(alarmId: Long) = Unit
    override suspend fun nextAlarm(): NextAlarm? = null
    override suspend fun nextTriggerOf(alarmId: Long): Long? = null
    override suspend fun refreshBedtime() = Unit
    override suspend fun scheduleTestAlarm(delaySeconds: Int): Long = 0
}

class CountingSurfaces : SurfaceRefresher {
    var refreshes = 0
    override fun refreshAll() {
        refreshes++
    }
}

class SilentLog : DiagnosticsLog {
    val entries = mutableListOf<String>()
    override fun info(tag: String, message: String) {
        entries += "I $tag $message"
    }

    override fun warn(tag: String, message: String) {
        entries += "W $tag $message"
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        entries += "E $tag $message"
    }

    override fun observeRecent(limit: Int): Flow<List<DiagnosticRecord>> = flowOf(emptyList())
    override suspend fun recent(limit: Int): List<DiagnosticRecord> = emptyList()
    override suspend fun clear() = Unit
}

class FixedTime(private val millis: Long = Instant.parse("2026-10-21T05:30:00Z").toEpochMilli()) : TimeSource {
    override fun nowMillis(): Long = millis
    override fun zone(): ZoneId = ZoneId.of("UTC")
}

class BackupRig(initial: BackupFile = BackupFile()) {
    val store = FakeBackupStore(initial)
    val scheduler = RecordingScheduler()
    val surfaces = CountingSurfaces()
    val log = SilentLog()
    val manager = BackupManager(mockk<Context>(relaxed = true), store, scheduler, surfaces, FixedTime(), log)
}

/** A backup with every kind of content, all of it already valid so a restore must reproduce it exactly. */
object Sample {
    val group = AlarmGroup(id = 3, name = "Work", enabled = true, accent = 2, sortOrder = 1)

    val alarms = listOf(
        Alarm(
            id = 1, label = "Wake up", hour = 6, minute = 45, repeatDaysMask = Alarm.WEEKDAYS_MASK, groupId = 3,
            sound = SoundSettings(mode = SoundMode.RANDOM_BUNDLED, bundledId = "bell", randomPool = setOf("bell", "chime")),
            volume = VolumeSettings(level = 70, rampSeconds = 45, rampStartPercent = 10, extraLoud = true),
            vibration = VibrationSettings(pattern = VibrationPattern.CUSTOM, strength = 80, customTimings = listOf(0, 300, 200, 300)),
            snooze = SnoozeSettings(minutes = 7, maxCount = 2, decreasing = true, decreaseStepMinutes = 2),
            voice = VoiceSettings(enabled = true, repeatSeconds = 30),
            wakeCheck = WakeCheckSettings(enabled = true, delayMinutes = 4, responseSeconds = 45, maxChecks = 2, maxReRings = 2),
            antiOversleep = AntiOversleepSettings(holdToDismissSeconds = 3, backupCount = 2, backupDelayMinutes = 8, editLockMinutes = 15),
            missions = listOf(
                MissionConfig(MissionType.MATH, Difficulty.HARD, count = 4),
                MissionConfig(MissionType.QR_BARCODE, params = mapOf(MissionConfig.PARAM_CODE to "ABC-123")),
            ),
            snoozeMissions = listOf(MissionConfig(MissionType.TYPING, Difficulty.EASY, count = 1)),
            skipEpochDay = 20_400, upcomingNoticeMinutes = 30, createdAt = 1_700_000_000_000, updatedAt = 1_700_000_100_000,
        ),
        Alarm(id = 2, label = "Flight", hour = 4, minute = 15, oneTimeEpochDay = 20_500, deleteAfterUse = true, ignoreVacation = true),
        Alarm(id = 5, hour = 23, minute = 59, enabled = false, repeatDaysMask = Alarm.maskOf(listOf(DayOfWeek.SUNDAY))),
    )

    val settings = AppSettings(
        onboardingDone = true,
        themeMode = ThemeMode.AMOLED,
        dynamicColor = false,
        timeFormat = TimeFormat.H24,
        vacation = VacationMode(enabled = true, resumeEpochDay = 20_600),
        calendarExceptions = CalendarExceptions(enabled = false, calendarIds = setOf(4L, 9L)),
        bedtime = BedtimeSettings(enabled = true, hour = 22, minute = 15, reminderLeadMinutes = 20),
        audioRoute = AudioRoute.SYSTEM_DEFAULT,
        hearingSafetyCap = true,
        headphoneCapPercent = 55,
        upcomingNoticeMinutes = 15,
        emergencyStopHoldSeconds = 4,
    )

    val history = listOf(
        AlarmEvent(
            id = 11, alarmId = 1, label = "Wake up", scheduledAt = 1_700_000_000_000, firedAt = 1_700_000_001_000,
            endedAt = 1_700_000_090_000, outcome = AlarmOutcome.DISMISSED, snoozeCount = 1, missionsDone = 2, missionsTotal = 2,
            wakeCheck = WakeCheckStatus.PASSED,
        ),
        AlarmEvent(
            id = 12, alarmId = 2, label = "Flight", scheduledAt = 1_700_100_000_000, firedAt = 1_700_100_002_000,
            endedAt = 1_700_100_200_000, outcome = AlarmOutcome.MISSED, backupFired = true,
        ),
    )

    val file = BackupFile(
        settings = settings,
        alarms = alarms,
        groups = listOf(group),
        worldClocks = listOf(WorldClockCity("Asia/Tokyo", "Tokyo", 0), WorldClockCity("Europe/Berlin", "Berlin", 1)),
        exceptionDates = listOf(ExceptionDate(20_450, "Holiday"), ExceptionDate(20_451)),
        history = history,
    )
}
