package com.riyadm.wakenudge.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.Serializable

@Serializable
data class Alarm(
    val id: Long = 0,
    val label: String = "",
    val hour: Int = 7,
    val minute: Int = 0,
    val enabled: Boolean = true,
    /** Bit i set = repeats on the weekday with ISO value i+1 (Monday = bit 0). 0 means one-time. */
    val repeatDaysMask: Int = 0,
    /** Epoch day for a one-time alarm pinned to a specific date; null = the next time the clock hits [hour]:[minute]. */
    val oneTimeEpochDay: Long? = null,
    val groupId: Long? = null,
    val sound: SoundSettings = SoundSettings(),
    val volume: VolumeSettings = VolumeSettings(),
    val vibration: VibrationSettings = VibrationSettings(),
    val snooze: SnoozeSettings = SnoozeSettings(),
    val voice: VoiceSettings = VoiceSettings(),
    val sunrise: SunriseSettings = SunriseSettings(),
    val wakeCheck: WakeCheckSettings = WakeCheckSettings(),
    val antiOversleep: AntiOversleepSettings = AntiOversleepSettings(),
    val missions: List<MissionConfig> = emptyList(),
    /** Missions required to snooze ("Solve-to-Snooze"); empty means snoozing is a plain tap. */
    val snoozeMissions: List<MissionConfig> = emptyList(),
    /** Epoch day of the single occurrence that "skip next" suppressed. */
    val skipEpochDay: Long? = null,
    val respectExceptions: Boolean = true,
    val ignoreVacation: Boolean = false,
    val upcomingNoticeMinutes: Int = 0,
    val deleteAfterUse: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** Clamped, so a corrupt row can never make scheduling or the UI throw. */
    val time: LocalTime get() = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    val isRepeating: Boolean get() = repeatDaysMask != 0
    val oneTimeDate: LocalDate? get() = oneTimeEpochDay?.let(LocalDate::ofEpochDay)

    val repeatDays: Set<DayOfWeek>
        get() = DayOfWeek.entries.filterTo(linkedSetOf()) { repeatDaysMask and it.bit != 0 }

    fun repeatsOn(day: DayOfWeek): Boolean = repeatDaysMask and day.bit != 0

    companion object {
        val DayOfWeek.bit: Int get() = 1 shl (value - 1)

        fun maskOf(days: Collection<DayOfWeek>): Int = days.fold(0) { acc, d -> acc or d.bit }

        val WEEKDAYS_MASK = maskOf(listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
        val WEEKEND_MASK = maskOf(listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        val EVERY_DAY_MASK = WEEKDAYS_MASK or WEEKEND_MASK
    }
}

@Serializable
data class AlarmGroup(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    /** Index into the UI's expressive accent palette. */
    val accent: Int = 0,
    val sortOrder: Int = 0,
)
