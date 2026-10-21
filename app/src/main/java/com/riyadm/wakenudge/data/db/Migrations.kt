package com.riyadm.wakenudge.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema change needs a migration here: an alarm clock must never lose the alarms people set because the app
 * was updated, so the database is never rebuilt destructively.
 */
object Migrations {
    /** Extra Loud became a strength slider; alarms that already had it on keep today's full strength (100). */
    val V1_TO_V2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `alarms` ADD COLUMN `vol_extraLoudPercent` INTEGER NOT NULL DEFAULT 100")
        }
    }

    val ALL: Array<Migration> = arrayOf(V1_TO_V2)
}
