package com.riyadm.wakenudge.data.habits

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.riyadm.wakenudge.domain.habits.Habit
import com.riyadm.wakenudge.domain.habits.HabitCheckIn

@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val colorIndex: Int,
    val daysMask: Int,
    val reminderMinutes: Int?,
    val createdEpochDay: Long,
    val sortOrder: Int,
) {
    fun toModel() = Habit(id, name, icon, colorIndex, daysMask, reminderMinutes, createdEpochDay, sortOrder)

    companion object {
        fun fromModel(habit: Habit) = with(habit) {
            HabitEntity(id, name, icon, colorIndex, daysMask, reminderMinutes, createdEpochDay, sortOrder)
        }
    }
}

@Entity(
    tableName = "habit_check_ins",
    primaryKeys = ["habitId", "epochDay"],
    foreignKeys = [ForeignKey(
        entity = HabitEntity::class,
        parentColumns = ["id"],
        childColumns = ["habitId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("habitId")],
)
data class HabitCheckInEntity(
    val habitId: Long,
    val epochDay: Long,
) {
    fun toModel() = HabitCheckIn(habitId, epochDay)

    companion object {
        fun fromModel(checkIn: HabitCheckIn) = HabitCheckInEntity(checkIn.habitId, checkIn.epochDay)
    }
}
