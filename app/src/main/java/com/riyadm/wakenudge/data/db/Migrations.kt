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

    /** The Emergency stop became a per-alarm option; every existing alarm keeps it on. */
    val V2_TO_V3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `alarms` ADD COLUMN `ao_emergencyStopEnabled` INTEGER NOT NULL DEFAULT 1")
        }
    }

    /**
     * The per-alarm settings of the hard-alarm work, and what history records about a ring. All of them default to what
     * alarms do today; the one exception is that a strict alarm always gets a way out, three minutes into the ring.
     */
    internal val V3_TO_V4_STATEMENTS: List<String> = listOf(
        "ALTER TABLE alarms ADD COLUMN ao_volumeFloorPercent INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE alarms ADD COLUMN ao_volumeKeyAction TEXT NOT NULL DEFAULT 'SNOOZE'",
        "ALTER TABLE alarms ADD COLUMN ao_returnToAlarm INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE alarms ADD COLUMN ao_forceReturn INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE alarms ADD COLUMN ao_muteBudget INTEGER NOT NULL DEFAULT 3",
        "ALTER TABLE alarms ADD COLUMN ao_muteSeconds INTEGER NOT NULL DEFAULT 30",
        "ALTER TABLE alarms ADD COLUMN ao_wayOutDelayMinutes INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE alarms ADD COLUMN ao_wayOutCount INTEGER NOT NULL DEFAULT 10",
        "ALTER TABLE alarms ADD COLUMN ao_fallbackCount INTEGER NOT NULL DEFAULT 3",
        "ALTER TABLE alarms ADD COLUMN ao_calmOnlyOnProgress INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE alarms ADD COLUMN ao_resumeWindowMinutes INTEGER NOT NULL DEFAULT 10",
        "ALTER TABLE alarms ADD COLUMN ao_lockOverrideMission INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE alarms ADD COLUMN ao_powerGuard INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE alarms ADD COLUMN ao_fullBrightness INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE alarms ADD COLUMN ao_forceSpeaker INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE alarms ADD COLUMN ao_torchWhenIgnored INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE alarms ADD COLUMN wc_requireProof INTEGER NOT NULL DEFAULT 0",
        "UPDATE alarms SET ao_wayOutDelayMinutes = 3 WHERE ao_emergencyStopEnabled = 0",
        "ALTER TABLE alarm_events ADD COLUMN fallbackUsed INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE alarm_events ADD COLUMN pauses INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE alarm_events ADD COLUMN resumes INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE alarm_events ADD COLUMN pullBacks INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE alarm_events ADD COLUMN mutesUsed INTEGER NOT NULL DEFAULT 0",
    )

    val V3_TO_V4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V3_TO_V4_STATEMENTS.forEach(db::execSQL)
        }
    }

    val ALL: Array<Migration> = arrayOf(V1_TO_V2, V2_TO_V3, V3_TO_V4)
}
