package com.riyadm.wakenudge.data.sleep

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.riyadm.wakenudge.domain.sleep.Epoch
import kotlinx.coroutines.flow.Flow

/** One tracked night (or nap). Times are wall-clock epoch millis. */
@Entity(tableName = "sleep_session", indices = [Index("endedAt")])
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    /** Null while tracking runs (or until a session left open by a dead process is closed). */
    val endedAt: Long? = null,
    /** A [com.riyadm.wakenudge.domain.sleep.SessionEnd] name. */
    val endReason: String? = null,
    /** "Record snoring" was on and the microphone really started. */
    val micUsed: Boolean = false,
    /** When the microphone stopped early because an alarm rang; null if it ran to the end or never ran. */
    val micStoppedAt: Long? = null,
)

/** The per-epoch summary; raw samples are never stored. */
@Entity(
    tableName = "sleep_epoch",
    primaryKeys = ["sessionId", "idx"],
    foreignKeys = [ForeignKey(entity = SleepSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class SleepEpochEntity(
    val sessionId: Long,
    val idx: Int,
    val samples: Int,
    val meanDelta: Float,
    val maxDelta: Float,
    val moveSamples: Int,
    val soundDb: Float?,
    val soundPeakDb: Float?,
    val snores: Int,
) {
    fun toEpoch() = Epoch(idx, samples, meanDelta, maxDelta, moveSamples, soundDb, soundPeakDb, snores)

    companion object {
        fun of(sessionId: Long, e: Epoch) =
            SleepEpochEntity(sessionId, e.index, e.samples, e.meanDelta, e.maxDelta, e.moveSamples, e.soundDb, e.soundPeakDb, e.snores)
    }
}

/** A short compressed clip of snoring kept in the app's private no-backup storage. */
@Entity(
    tableName = "snore_clip",
    foreignKeys = [ForeignKey(entity = SleepSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("recordedAt")],
)
data class SnoreClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** When the clip starts, wall clock. Also what the seven-day deletion counts from. */
    val recordedAt: Long,
    val durationMs: Long,
    val peakDb: Float,
    val aboveFloorDb: Float,
    /** File name inside the clip folder; never a path. */
    val fileName: String,
    /** 32 levels 0..100, comma separated: the little waveform drawn next to the clip. */
    val envelope: String,
)

@Dao
interface SleepDao {
    @Insert suspend fun insertSession(session: SleepSessionEntity): Long
    @Update suspend fun updateSession(session: SleepSessionEntity)
    @Query("SELECT * FROM sleep_session WHERE id = :id") suspend fun session(id: Long): SleepSessionEntity?
    @Query("SELECT * FROM sleep_session WHERE endedAt IS NULL") suspend fun openSessions(): List<SleepSessionEntity>
    @Query("SELECT * FROM sleep_session WHERE endedAt IS NOT NULL AND endedAt >= :from AND endedAt < :to ORDER BY endedAt")
    fun observeFinishedBetween(from: Long, to: Long): Flow<List<SleepSessionEntity>>
    @Query("SELECT * FROM sleep_session WHERE endedAt IS NOT NULL ORDER BY endedAt DESC LIMIT 1")
    fun observeLastFinished(): Flow<SleepSessionEntity?>
    @Query("SELECT COUNT(*) FROM sleep_session") fun observeCount(): Flow<Int>
    @Query("SELECT * FROM sleep_session WHERE endedAt IS NOT NULL AND endedAt < :before") suspend fun finishedBefore(before: Long): List<SleepSessionEntity>
    @Query("DELETE FROM sleep_session WHERE id = :id") suspend fun deleteSession(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEpochs(epochs: List<SleepEpochEntity>)
    @Query("SELECT * FROM sleep_epoch WHERE sessionId = :sessionId ORDER BY idx") suspend fun epochs(sessionId: Long): List<SleepEpochEntity>
    @Query("SELECT MAX(idx) FROM sleep_epoch WHERE sessionId = :sessionId") suspend fun lastEpochIndex(sessionId: Long): Int?

    @Insert suspend fun insertClip(clip: SnoreClipEntity): Long
    @Query("DELETE FROM snore_clip WHERE id = :id") suspend fun deleteClip(id: Long)
    @Query("SELECT * FROM snore_clip WHERE id = :id") suspend fun clip(id: Long): SnoreClipEntity?
    @Query("SELECT * FROM snore_clip WHERE sessionId = :sessionId ORDER BY recordedAt") suspend fun clipsOf(sessionId: Long): List<SnoreClipEntity>
    @Query("SELECT * FROM snore_clip WHERE sessionId = :sessionId ORDER BY recordedAt") fun observeClipsOf(sessionId: Long): Flow<List<SnoreClipEntity>>
    @Query("SELECT * FROM snore_clip ORDER BY recordedAt") suspend fun allClips(): List<SnoreClipEntity>
    @Query("SELECT * FROM snore_clip ORDER BY recordedAt") fun observeAllClips(): Flow<List<SnoreClipEntity>>
    @Query("DELETE FROM snore_clip") suspend fun deleteAllClips()
}

/**
 * Sleep tracking's own database, `sleep.db`, separate from the alarm database so that neither can break the other. It
 * lives in credential-encrypted storage (unlike the alarms, which must ring before the first unlock): nights and
 * recordings stay encrypted until the phone is unlocked. Not part of any backup.
 */
@Database(entities = [SleepSessionEntity::class, SleepEpochEntity::class, SnoreClipEntity::class], version = 1, exportSchema = true)
abstract class SleepDatabase : RoomDatabase() {
    abstract fun sleepDao(): SleepDao

    companion object {
        const val NAME = "sleep.db"
    }
}
