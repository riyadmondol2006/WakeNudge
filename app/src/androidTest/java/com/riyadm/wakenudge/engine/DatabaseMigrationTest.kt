package com.riyadm.wakenudge.engine

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.wakenudge.data.db.Migrations
import com.riyadm.wakenudge.data.db.WakeNudgeDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** An update must never cost anyone their alarms: every schema step is migrated and checked against the exported schema. */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), WakeNudgeDatabase::class.java)

    /** Inserts one row filling every NOT NULL column with a neutral value, then sets [overrides]. */
    private fun SupportSQLiteDatabase.insertAlarm(overrides: Map<String, Any>) {
        val columns = mutableListOf<Pair<String, String>>()
        query("PRAGMA table_info(`alarms`)").use { c ->
            while (c.moveToNext()) columns += c.getString(c.getColumnIndexOrThrow("name")) to c.getString(c.getColumnIndexOrThrow("type"))
        }
        val values = columns.map { (name, type) ->
            val v = overrides[name] ?: if (type.equals("TEXT", true)) "" else 0
            if (v is String) "'${v.replace("'", "''")}'" else v.toString()
        }
        execSQL("INSERT INTO `alarms` (${columns.joinToString { "`${it.first}`" }}) VALUES (${values.joinToString()})")
    }

    @Test fun version1To2KeepsEveryAlarmAndGivesExtraLoudFullStrength() {
        helper.createDatabase(TEST_DB, 1).apply {
            insertAlarm(mapOf("id" to 1, "label" to "Gym", "hour" to 6, "minute" to 30, "vol_extraLoud" to 1, "vol_level" to 80))
            insertAlarm(mapOf("id" to 2, "label" to "Quiet", "hour" to 8, "minute" to 0, "vol_extraLoud" to 0, "vol_level" to 50))
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, *Migrations.ALL)

        db.query("SELECT id, label, hour, minute, vol_level, vol_extraLoud, vol_extraLoudPercent FROM alarms ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("Gym", c.getString(1))
            assertEquals(6, c.getInt(2)); assertEquals(30, c.getInt(3)); assertEquals(80, c.getInt(4))
            assertEquals("Extra Loud stays on", 1, c.getInt(5))
            assertEquals("and keeps the strength it always had", 100, c.getInt(6))
            c.moveToNext()
            assertEquals("Quiet", c.getString(1))
            assertEquals(0, c.getInt(5))
            assertEquals(100, c.getInt(6))
        }
        db.close()
    }

    @Test fun version2To3KeepsTheEmergencyStopOnForExistingAlarms() {
        helper.createDatabase(TEST_DB, 2).apply {
            insertAlarm(mapOf("id" to 7, "label" to "Old", "hour" to 6, "minute" to 0, "ao_backupEnabled" to 1))
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *Migrations.ALL)
        db.query("SELECT label, ao_emergencyStopEnabled, ao_backupEnabled FROM alarms").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("Old", c.getString(0))
            assertEquals("an existing alarm keeps the Emergency stop it always had", 1, c.getInt(1))
            assertEquals(1, c.getInt(2))
        }
        db.close()
    }

    @Test fun anInstallFromVersion1ReachesVersion3WithEverythingIntact() {
        helper.createDatabase(TEST_DB, 1).apply {
            insertAlarm(mapOf("id" to 3, "label" to "First install", "hour" to 5, "minute" to 15, "vol_extraLoud" to 1))
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *Migrations.ALL)
        db.query("SELECT label, vol_extraLoud, vol_extraLoudPercent, ao_emergencyStopEnabled FROM alarms").use { c ->
            c.moveToFirst()
            assertEquals("First install", c.getString(0))
            assertEquals(1, c.getInt(1)); assertEquals(100, c.getInt(2)); assertEquals(1, c.getInt(3))
        }
        db.close()
    }

    private fun SupportSQLiteDatabase.insertEvent(id: Int, alarmId: Int, label: String) {
        execSQL(
            "INSERT INTO `alarm_events` (`id`, `alarmId`, `label`, `scheduledAt`, `firedAt`, `endedAt`, `outcome`, `snoozeCount`, `missionsDone`, " +
                "`missionsTotal`, `backupFired`, `wakeCheck`, `wakeCheckReRings`, `snoozeUntil`, `pendingBackups`) " +
                "VALUES ($id, $alarmId, '$label', 1000, 1000, 2000, 'DISMISSED', 2, 1, 1, 1, 'PASSED', 0, 0, '')",
        )
    }

    @Test fun version3To4KeepsEverythingDefaultsMatchTodayAndAStrictAlarmGetsItsWayOut() {
        helper.createDatabase(TEST_DB, 3).apply {
            insertAlarm(mapOf("id" to 1, "label" to "Plain", "hour" to 7, "minute" to 30, "ao_emergencyStopEnabled" to 1, "ao_ringTimeoutMinutes" to 10))
            insertAlarm(mapOf("id" to 2, "label" to "Strict", "hour" to 6, "minute" to 0, "ao_emergencyStopEnabled" to 0, "ao_ringTimeoutMinutes" to 0))
            insertEvent(id = 9, alarmId = 1, label = "Plain")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *Migrations.ALL)

        db.query(
            "SELECT label, hour, minute, ao_emergencyStopEnabled, ao_ringTimeoutMinutes, ao_volumeFloorPercent, ao_volumeKeyAction, ao_returnToAlarm, " +
                "ao_forceReturn, ao_muteBudget, ao_muteSeconds, ao_wayOutDelayMinutes, ao_wayOutCount, ao_fallbackCount, ao_calmOnlyOnProgress, " +
                "ao_resumeWindowMinutes, ao_lockOverrideMission, ao_powerGuard, ao_fullBrightness, ao_forceSpeaker, ao_torchWhenIgnored, wc_requireProof " +
                "FROM alarms ORDER BY id",
        ).use { c ->
            assertEquals("every alarm is kept", 2, c.count)
            c.moveToFirst()
            assertEquals("Plain", c.getString(0))
            assertEquals(7, c.getInt(1)); assertEquals(30, c.getInt(2)); assertEquals(1, c.getInt(3)); assertEquals(10, c.getInt(4))
            assertEquals(0, c.getInt(5))
            assertEquals("SNOOZE", c.getString(6))
            assertEquals(1, c.getInt(7))
            assertEquals(0, c.getInt(8))
            assertEquals(3, c.getInt(9)); assertEquals(30, c.getInt(10))
            assertEquals("an alarm with the Emergency stop on has no delay", 0, c.getInt(11))
            assertEquals(10, c.getInt(12)); assertEquals(3, c.getInt(13)); assertEquals(0, c.getInt(14)); assertEquals(10, c.getInt(15))
            for (flag in 16..21) assertEquals("column $flag", 0, c.getInt(flag))
            c.moveToNext()
            assertEquals("Strict", c.getString(0))
            assertEquals(0, c.getInt(3))
            assertEquals("the ring timeout stays Never", 0, c.getInt(4))
            assertEquals("a strict alarm gets its way out after 3 minutes", 3, c.getInt(11))
        }
        db.query("SELECT label, outcome, snoozeCount, wakeCheck, fallbackUsed, pauses, resumes, pullBacks, mutesUsed FROM alarm_events").use { c ->
            assertEquals("history is kept", 1, c.count)
            c.moveToFirst()
            assertEquals("Plain", c.getString(0))
            assertEquals("DISMISSED", c.getString(1))
            assertEquals(2, c.getInt(2))
            assertEquals("PASSED", c.getString(3))
            for (counter in 4..8) assertEquals("column $counter", 0, c.getInt(counter))
        }
        db.close()
    }

    @Test fun anInstallFromVersion1ReachesVersion4() {
        helper.createDatabase(TEST_DB, 1).apply {
            insertAlarm(mapOf("id" to 3, "label" to "First install", "hour" to 5, "minute" to 15))
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *Migrations.ALL)
        db.query("SELECT label, ao_emergencyStopEnabled, ao_wayOutDelayMinutes FROM alarms").use { c ->
            c.moveToFirst()
            assertEquals("First install", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertEquals(0, c.getInt(2))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
