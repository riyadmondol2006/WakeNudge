package com.riyadm.wakenudge.domain.habits

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface HabitRepository {
    fun observe(): Flow<HabitData>
    suspend fun data(): HabitData
    suspend fun habit(id: Long): Habit?
    suspend fun save(habit: Habit): Long?
    suspend fun delete(id: Long)
    suspend fun move(id: Long, upwards: Boolean)
    suspend fun setDone(id: Long, date: LocalDate, done: Boolean, today: LocalDate): Boolean
    suspend fun replace(backup: HabitBackup)
}
