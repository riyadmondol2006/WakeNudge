package com.riyadm.wakenudge.engine.sounds

import androidx.room.*

@Entity(tableName = "last_mix")
data class SavedSleepMix(@PrimaryKey val id: Int = 1, val json: String)

@Dao
interface SleepMixDao {
    @Query("SELECT * FROM last_mix WHERE id = 1")
    suspend fun load(): SavedSleepMix?
    @Upsert suspend fun save(mix: SavedSleepMix)
}

/** Separate file and schema: the alarm database never depends on sleep playback. */
@Database(entities = [SavedSleepMix::class], version = 1, exportSchema = true)
abstract class SleepSoundsDatabase : RoomDatabase() {
    abstract fun mix(): SleepMixDao
}
