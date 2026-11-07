package com.riyadm.wakenudge.domain.model

import java.time.DayOfWeek
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

@Serializable
enum class TimeFormat { SYSTEM, H12, H24 }

@Serializable
enum class WeekStart { SYSTEM, MONDAY, SATURDAY, SUNDAY }

@Serializable
enum class VolumeKeyAction { VOLUME, SNOOZE, DISMISS, SILENCE_TEMPORARILY, NOTHING }

@Serializable
enum class AudioRoute { PHONE_SPEAKER, SYSTEM_DEFAULT }

@Serializable
data class VacationMode(
    val enabled: Boolean = false,
    /** Alarms resume on this day (inclusive). Null means "until I switch it off". */
    val resumeEpochDay: Long? = null,
) {
    /** Whether alarms are still paused on [epochDay]; false once the resume day has arrived. */
    fun activeOn(epochDay: Long): Boolean = enabled && (resumeEpochDay == null || epochDay < resumeEpochDay)
}

@Serializable
data class CalendarExceptions(
    val enabled: Boolean = false,
    /** CalendarContract calendar ids whose all-day events count as holidays. */
    val calendarIds: Set<Long> = emptySet(),
)

@Serializable
data class BedtimeSettings(
    val enabled: Boolean = false,
    val hour: Int = 22,
    val minute: Int = 30,
    val daysMask: Int = Alarm.EVERY_DAY_MASK,
    val reminderLeadMinutes: Int = 30,
    val sleepGoalMinutes: Int = 8 * 60,
    val fallAsleepMinutes: Int = 15,
)

/**
 * Default for every new alarm; copied into the alarm at creation time. [level] decides almost everything; the other
 * fields stay for the details no level owns and so that an older version of the app can still read the settings.
 */
@Serializable
data class AlarmDefaults(
    /** New installs start with a different bundled tone each morning. */
    val sound: SoundSettings = SoundSettings(mode = SoundMode.RANDOM_BUNDLED),
    val volume: VolumeSettings = VolumeSettings(),
    val vibration: VibrationSettings = VibrationSettings(),
    val snooze: SnoozeSettings = SnoozeSettings(),
    val antiOversleep: AntiOversleepSettings = AntiOversleepSettings(),
    /** How hard a new alarm is. Never [Hardness.CUSTOM]. */
    val level: Hardness = Hardness.NORMAL,
) {
    /** A new alarm made from these defaults at [level] (or [levelOverride]). */
    fun newAlarm(base: Alarm = Alarm(), levelOverride: Hardness? = null): Alarm = HardnessPresets.apply(
        levelOverride ?: level.takeIf { it != Hardness.CUSTOM } ?: Hardness.NORMAL,
        base.copy(sound = sound, volume = volume, vibration = vibration, snooze = snooze, antiOversleep = antiOversleep),
    )
}

/** This alarm's copy of the choices that used to be global, as they were set in [settings]. */
fun AntiOversleepSettings.withGlobalsOf(settings: AppSettings): AntiOversleepSettings =
    copy(volumeKeyAction = settings.volumeKeyAction, returnToAlarm = settings.keepRingScreenFront)

@Serializable
data class StopwatchState(
    val running: Boolean = false,
    /** Wall-clock millis when the current run started (valid while [running]). */
    val startedAtMillis: Long = 0,
    /** Elapsed time accumulated before the current run. */
    val accumulatedMs: Long = 0,
    val laps: List<Long> = emptyList(),
) {
    fun elapsedAt(nowMillis: Long): Long =
        if (running) accumulatedMs + (nowMillis - startedAtMillis).coerceAtLeast(0) else accumulatedMs
}

@Serializable
data class AppSettings(
    val onboardingDone: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val haptics: Boolean = true,
    val timeFormat: TimeFormat = TimeFormat.SYSTEM,
    val weekStart: WeekStart = WeekStart.SYSTEM,
    val showSeconds: Boolean = false,
    val vacation: VacationMode = VacationMode(),
    val calendarExceptions: CalendarExceptions = CalendarExceptions(),
    val bedtime: BedtimeSettings = BedtimeSettings(),
    val defaults: AlarmDefaults = AlarmDefaults(),
    val volumeKeyAction: VolumeKeyAction = VolumeKeyAction.SNOOZE,
    val audioRoute: AudioRoute = AudioRoute.PHONE_SPEAKER,
    /** Cap the alarm volume when the output is a headset/earbuds, to protect hearing. */
    val hearingSafetyCap: Boolean = true,
    /** Highest the alarm may go on headphones when the cap is on, as a percentage of the stream. */
    val headphoneCapPercent: Int = 60,
    val upcomingNoticeMinutes: Int = 0,
    val timerSound: SoundSettings = SoundSettings(bundledId = "chime"),
    val timerVibrate: Boolean = true,
    val stopwatch: StopwatchState = StopwatchState(),
    /** Whether to bring the screen back to the alarm UI if the user wanders away while it rings. */
    val keepRingScreenFront: Boolean = true,
    val emergencyStopHoldSeconds: Int = 3,
    /** Warn some hours before an alarm when the battery is low and not charging, so the phone lasts until it rings. */
    val lowBatteryWarning: Boolean = true,
    /** Look for a newer release on GitHub when the app opens, at most once an hour. */
    val updateChecksEnabled: Boolean = true,
    /** Epoch millis of the last check GitHub answered; 0 when there has not been one. */
    val lastUpdateCheckAt: Long = 0,
    /** The version (MAJOR.MINOR.PATCH) the user chose "Skip this version" for; empty when none. */
    val skippedUpdateVersion: String = "",
    /** The alarm list's one-time "choose how hard each alarm is" card was dismissed (or never needed: a new install). */
    val levelsCardSeen: Boolean = false,
    /** No mercy was picked once after its warning; later picks don't ask again. */
    val noMercyConfirmed: Boolean = false,
    /**
     * "I can't do timed or physical missions": the picker leaves out missions that are timed or need the phone moved,
     * every mission uses its screen-reader form, chains are sums and typing, and the still-awake check asks for sums.
     */
    val noTimedMissions: Boolean = false,
) {
    /**
     * The three update fields describe this installation, not the user's alarm setup, so a backup neither carries
     * them nor changes them: a restore must not quietly switch update checks on or off, or forget a skipped version.
     */
    fun withUpdateStateOf(installed: AppSettings): AppSettings = copy(
        updateChecksEnabled = installed.updateChecksEnabled,
        lastUpdateCheckAt = installed.lastUpdateCheckAt,
        skippedUpdateVersion = installed.skippedUpdateVersion,
    )

    /** This settings object as a backup records it: update state back at its defaults. */
    fun withoutUpdateState(): AppSettings = withUpdateStateOf(AppSettings())


    fun firstDayOfWeek(systemFirstDay: DayOfWeek): DayOfWeek = when (weekStart) {
        WeekStart.SYSTEM -> systemFirstDay
        WeekStart.MONDAY -> DayOfWeek.MONDAY
        WeekStart.SATURDAY -> DayOfWeek.SATURDAY
        WeekStart.SUNDAY -> DayOfWeek.SUNDAY
    }
}
