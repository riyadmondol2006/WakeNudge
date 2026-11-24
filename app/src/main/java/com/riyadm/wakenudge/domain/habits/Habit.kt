package com.riyadm.wakenudge.domain.habits

import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class Habit(
    val id: Long = 0,
    val name: String = "",
    val icon: String = DEFAULT_ICON,
    val colorIndex: Int = 0,
    val daysMask: Int = EVERY_DAY_MASK,
    val reminderMinutes: Int? = null,
    val createdEpochDay: Long = 0,
    val sortOrder: Int = 0,
) {
    fun isDueOn(date: LocalDate): Boolean =
        date.toEpochDay() >= createdEpochDay && daysMask and bitOf(date.dayOfWeek) != 0

    companion object {
        const val MAX_HABITS = 30
        const val MAX_NAME_LENGTH = 40
        const val MAX_ICON_LENGTH = 16
        const val PALETTE_SIZE = 8
        const val RECENT_EDIT_DAYS = 7
        const val DEFAULT_ICON = "✓"
        const val EVERY_DAY_MASK = 0x7f

        fun bitOf(day: DayOfWeek): Int = 1 shl (day.value - 1)
        fun maskOf(days: Collection<DayOfWeek>): Int = days.fold(0) { mask, day -> mask or bitOf(day) }
    }
}

@Serializable
data class HabitCheckIn(
    val habitId: Long,
    val epochDay: Long,
)

@Serializable
data class HabitBackup(
    val habits: List<Habit> = emptyList(),
    val checkIns: List<HabitCheckIn> = emptyList(),
)

data class HabitData(
    val habits: List<Habit> = emptyList(),
    val checkIns: Set<HabitCheckIn> = emptySet(),
) {
    fun doneDates(habitId: Long): Set<Long> = checkIns.asSequence()
        .filter { it.habitId == habitId }
        .map { it.epochDay }
        .toSet()
}
