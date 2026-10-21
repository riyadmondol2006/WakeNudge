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

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
