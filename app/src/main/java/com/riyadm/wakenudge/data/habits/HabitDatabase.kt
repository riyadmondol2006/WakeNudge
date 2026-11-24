package com.riyadm.wakenudge.data.habits

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [HabitEntity::class, HabitCheckInEntity::class], version = 1, exportSchema = true)
abstract class HabitDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao

    companion object {
        const val NAME = "habits.db"
    }
}
