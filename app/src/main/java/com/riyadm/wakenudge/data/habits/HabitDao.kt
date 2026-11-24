package com.riyadm.wakenudge.data.habits

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY sortOrder, id")
    fun observeHabits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habit_check_ins")
    fun observeCheckIns(): Flow<List<HabitCheckInEntity>>

    @Query("SELECT * FROM habits ORDER BY sortOrder, id")
    suspend fun habits(): List<HabitEntity>

    @Query("SELECT * FROM habit_check_ins")
    suspend fun checkIns(): List<HabitCheckInEntity>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun habit(id: Long): HabitEntity?

    @Query("SELECT COUNT(*) FROM habits")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM habits")
    suspend fun nextSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(habit: HabitEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(habits: List<HabitEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckIn(checkIn: HabitCheckInEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckIns(checkIns: List<HabitCheckInEntity>)

    @Query("DELETE FROM habit_check_ins WHERE habitId = :habitId AND epochDay = :epochDay")
    suspend fun deleteCheckIn(habitId: Long, epochDay: Long)

    @Query("DELETE FROM habits WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM habit_check_ins")
    suspend fun clearCheckIns()

    @Query("DELETE FROM habits")
    suspend fun clearHabits()

    @Transaction
    suspend fun replaceAll(habits: List<HabitEntity>, checkIns: List<HabitCheckInEntity>) {
        clearCheckIns()
        clearHabits()
        upsertAll(habits)
        upsertCheckIns(checkIns)
    }
}
