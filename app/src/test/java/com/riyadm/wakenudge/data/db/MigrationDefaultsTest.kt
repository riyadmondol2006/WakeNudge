package com.riyadm.wakenudge.data.db

import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.WakeCheckSettings
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration spells its defaults out as SQL and the model has them in Kotlin. They have to agree, or an updated alarm
 * and a new one would behave differently because of a typo. Nothing here needs a device; the real migration of a real
 * database is in `DatabaseMigrationTest`.
 */
class MigrationDefaultsTest {
    private val addColumn = Regex("""ALTER TABLE (\w+) ADD COLUMN (\w+) (INTEGER|TEXT) NOT NULL DEFAULT (.+)""")

    /** table.column to the SQL default. */
    private val sqlDefaults: Map<String, String> = Migrations.V3_TO_V4_STATEMENTS.mapNotNull { addColumn.matchEntire(it) }
        .associate { "${it.groupValues[1]}.${it.groupValues[2]}" to it.groupValues[4] }

    private fun sqlOf(value: Any?): String = when (value) {
        is Boolean -> if (value) "1" else "0"
        is Enum<*> -> "'${value.name}'"
        else -> value.toString()
    }

    private fun assertDefaults(table: String, prefix: String, instance: Any, columnsNew: Set<String>) {
        val fields = instance.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        var seen = 0
        for (field in fields) {
            val key = "$table.$prefix${field.name}"
            if ("$prefix${field.name}" !in columnsNew) continue
            seen++
            field.isAccessible = true
            assertEquals(key, sqlOf(field.get(instance)), sqlDefaults[key])
        }
        assertTrue("nothing compared for $table", seen > 0)
    }

    private fun columns(version: Int, table: String): Set<String> {
        val schema = Json.parseToJsonElement(File("schemas/com.riyadm.wakenudge.data.db.WakeNudgeDatabase/$version.json").readText())
        val entity = schema.jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
            .map { it.jsonObject }.single { it.getValue("tableName").jsonPrimitive.content == table }
        return entity.getValue("fields").jsonArray.map { it.jsonObject.getValue("columnName").jsonPrimitive.content }.toSet()
    }

    @Test fun `every new alarm column defaults in SQL to what a new Kotlin alarm has`() {
        val added = columns(4, "alarms") - columns(3, "alarms")
        assertDefaults("alarms", "ao_", AntiOversleepSettings(), added)
        assertDefaults("alarms", "wc_", WakeCheckSettings(), added)
    }

    @Test fun `every new history column defaults in SQL to what a new Kotlin event has`() {
        val added = columns(4, "alarm_events") - columns(3, "alarm_events")
        assertDefaults("alarm_events", "", AlarmEvent(alarmId = 1, label = "", scheduledAt = 0, firedAt = 0), added)
    }

    @Test fun `the migration adds exactly the columns the schema gained`() {
        val added = sqlDefaults.keys
        val expected = (columns(4, "alarms") - columns(3, "alarms")).map { "alarms.$it" } +
            (columns(4, "alarm_events") - columns(3, "alarm_events")).map { "alarm_events.$it" }
        assertEquals(expected.toSet(), added)
    }

    @Test fun `only a strict alarm has anything changed by the migration`() {
        val writes = Migrations.V3_TO_V4_STATEMENTS.filter { !it.startsWith("ALTER TABLE") }
        assertEquals(listOf("UPDATE alarms SET ao_wayOutDelayMinutes = 3 WHERE ao_emergencyStopEnabled = 0"), writes)
    }
}
