package com.riyadm.wakenudge.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        AlarmEntity::class,
        GroupEntity::class,
        EventEntity::class,
        TimerEntity::class,
        WorldClockEntity::class,
        ExceptionEntity::class,
        WakeCheckEntity::class,
        DiagnosticEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class WakeNudgeDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun groupDao(): GroupDao
    abstract fun eventDao(): EventDao
    abstract fun timerDao(): TimerDao
    abstract fun worldClockDao(): WorldClockDao
    abstract fun exceptionDao(): ExceptionDao
    abstract fun wakeCheckDao(): WakeCheckDao
    abstract fun diagnosticDao(): DiagnosticDao

    companion object {
        const val NAME = "wakenudge.db"
    }
}
