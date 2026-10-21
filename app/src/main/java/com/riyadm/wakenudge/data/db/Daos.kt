package com.riyadm.wakenudge.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute, id")
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms ORDER BY hour, minute, id")
    suspend fun getAll(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun get(id: Long): AlarmEntity?

    @Query("SELECT * FROM alarms WHERE id = :id")
    fun observe(id: Long): Flow<AlarmEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AlarmEntity): Long

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE alarms SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)

    @Query("UPDATE alarms SET skipEpochDay = :epochDay, updatedAt = :now WHERE id = :id")
    suspend fun setSkipDay(id: Long, epochDay: Long?, now: Long)

    @Query("UPDATE alarms SET groupId = NULL WHERE groupId = :groupId")
    suspend fun detachGroup(groupId: Long)

    @Query("DELETE FROM alarms")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<AlarmEntity>)

    @Transaction
    suspend fun replaceAll(entities: List<AlarmEntity>) {
        deleteAll()
        insertAll(entities)
    }
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM alarm_groups ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM alarm_groups ORDER BY sortOrder, id")
    suspend fun getAll(): List<GroupEntity>

    @Query("SELECT * FROM alarm_groups WHERE id = :id")
    suspend fun get(id: Long): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GroupEntity): Long

    @Query("DELETE FROM alarm_groups WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE alarm_groups SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM alarm_groups")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<GroupEntity>)

    @Transaction
    suspend fun replaceAll(entities: List<GroupEntity>) {
        deleteAll()
        insertAll(entities)
    }
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(entity: EventEntity): Long

    @Update
    suspend fun update(entity: EventEntity)

    @Query("SELECT * FROM alarm_events WHERE id = :id")
    suspend fun get(id: Long): EventEntity?

    @Query("SELECT * FROM alarm_events WHERE alarmId = :alarmId AND scheduledAt = :scheduledAt ORDER BY id DESC LIMIT 1")
    suspend fun findOccurrence(alarmId: Long, scheduledAt: Long): EventEntity?

    @Query("SELECT * FROM alarm_events ORDER BY firedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM alarm_events WHERE firedAt >= :since ORDER BY firedAt DESC")
    fun observeSince(since: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM alarm_events ORDER BY firedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EventEntity>

    @Query("SELECT * FROM alarm_events WHERE outcome = 'RINGING'")
    suspend fun openEvents(): List<EventEntity>

    @Query("DELETE FROM alarm_events WHERE firedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM alarm_events")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<EventEntity>)
}

@Dao
interface TimerDao {
    @Query("SELECT * FROM timers ORDER BY createdAt, id")
    fun observeAll(): Flow<List<TimerEntity>>

    @Query("SELECT * FROM timers ORDER BY createdAt, id")
    suspend fun getAll(): List<TimerEntity>

    @Query("SELECT * FROM timers WHERE id = :id")
    suspend fun get(id: Long): TimerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TimerEntity): Long

    @Query("DELETE FROM timers WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface WorldClockDao {
    @Query("SELECT * FROM world_clocks ORDER BY sortOrder, label")
    fun observeAll(): Flow<List<WorldClockEntity>>

    @Query("SELECT * FROM world_clocks ORDER BY sortOrder, label")
    suspend fun getAll(): List<WorldClockEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorldClockEntity)

    @Delete
    suspend fun delete(entity: WorldClockEntity)

    @Query("DELETE FROM world_clocks")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<WorldClockEntity>)

    @Transaction
    suspend fun replaceAll(entities: List<WorldClockEntity>) {
        deleteAll()
        insertAll(entities)
    }
}

@Dao
interface ExceptionDao {
    @Query("SELECT * FROM exception_dates ORDER BY epochDay")
    fun observeAll(): Flow<List<ExceptionEntity>>

    @Query("SELECT * FROM exception_dates ORDER BY epochDay")
    suspend fun getAll(): List<ExceptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExceptionEntity)

    @Query("DELETE FROM exception_dates WHERE epochDay = :epochDay")
    suspend fun delete(epochDay: Long)

    @Query("DELETE FROM exception_dates WHERE epochDay < :epochDay")
    suspend fun deleteBefore(epochDay: Long)

    @Query("DELETE FROM exception_dates")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ExceptionEntity>)

    @Transaction
    suspend fun replaceAll(entities: List<ExceptionEntity>) {
        deleteAll()
        insertAll(entities)
    }
}

@Dao
interface WakeCheckDao {
    @Query("SELECT * FROM wake_checks")
    suspend fun getAll(): List<WakeCheckEntity>

    @Query("SELECT * FROM wake_checks")
    fun observeAll(): Flow<List<WakeCheckEntity>>

    @Query("SELECT * FROM wake_checks WHERE alarmId = :alarmId")
    suspend fun get(alarmId: Long): WakeCheckEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WakeCheckEntity)

    @Query("DELETE FROM wake_checks WHERE alarmId = :alarmId")
    suspend fun delete(alarmId: Long)

    @Query("DELETE FROM wake_checks")
    suspend fun deleteAll()
}

@Dao
interface DiagnosticDao {
    @Insert
    suspend fun insert(entity: DiagnosticEntity)

    @Query("SELECT * FROM diagnostics ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DiagnosticEntity>>

    @Query("SELECT * FROM diagnostics ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DiagnosticEntity>

    @Query("DELETE FROM diagnostics WHERE id NOT IN (SELECT id FROM diagnostics ORDER BY timestamp DESC, id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM diagnostics")
    suspend fun clear()
}
