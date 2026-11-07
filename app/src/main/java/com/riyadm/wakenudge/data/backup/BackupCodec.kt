package com.riyadm.wakenudge.data.backup

import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.ExceptionDate
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.WorldClockCity
import com.riyadm.wakenudge.domain.model.withGlobalsOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Result of reading a backup document: the decoded (not yet sanitised) file, or the reason it was refused. */
sealed interface ParseOutcome {
    data class Parsed(val file: BackupFile, val warnings: List<ImportWarning>) : ParseOutcome
    data class Rejected(val failure: ImportFailure) : ParseOutcome
}

/**
 * JSON in and out of [BackupFile]. Reading is deliberately forgiving about *content* (one unreadable alarm does not
 * sink the file; unknown enum values fall back to defaults via [WakeJson]) and strict about *identity* (not JSON,
 * not WakeNudge, or from a newer format is refused outright).
 */
object BackupCodec {

    private val pretty = Json(from = WakeJson.instance) { prettyPrint = true }
    private val knownMissionTypes = MissionType.entries.mapTo(hashSetOf()) { it.name }

    /** Same as the database migration: an alarm from before the way out existed gets one this far into the ring. */
    private const val STRICT_WAY_OUT_DELAY_MINUTES = 3

    fun encode(file: BackupFile): String = pretty.encodeToString(BackupFile.serializer(), file)

    fun parse(text: String): ParseOutcome {
        val trimmed = text.removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) return ParseOutcome.Rejected(ImportFailure.NotJson)
        val root = try {
            WakeJson.instance.parseToJsonElement(trimmed)
        } catch (_: kotlinx.serialization.SerializationException) {
            return ParseOutcome.Rejected(ImportFailure.NotJson)
        } catch (_: IllegalArgumentException) {
            return ParseOutcome.Rejected(ImportFailure.NotJson)
        }
        if (root !is JsonObject) return ParseOutcome.Rejected(ImportFailure.WrongApp(found = null))

        val app = (root["app"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (app != BackupFormat.APP_ID) return ParseOutcome.Rejected(ImportFailure.WrongApp(app))

        val version = (root["formatVersion"] as? JsonPrimitive)?.intOrNull
            ?: return ParseOutcome.Rejected(ImportFailure.Malformed("formatVersion"))
        if (version > BackupFormat.VERSION) return ParseOutcome.Rejected(ImportFailure.NewerFormat(version, BackupFormat.VERSION))
        if (version < 1) return ParseOutcome.Rejected(ImportFailure.Malformed("formatVersion"))

        val warnings = mutableListOf<ImportWarning>()
        val settings = decodeOrNull(AppSettings.serializer(), root["settings"]) ?: AppSettings().also {
            if (root["settings"] != null) warnings += ImportWarning(R.string.backup_warn_settings_unreadable)
        }
        var droppedMissions = 0
        val alarms = lenientList(root, "alarms", Alarm.serializer(), R.plurals.backup_warn_alarms_skipped, warnings) { element ->
            withPerAlarmSettings(dropUnknownMissions(element) { droppedMissions++ }, settings)
        }
        val groups = lenientList(root, "groups", AlarmGroup.serializer(), R.plurals.backup_warn_groups_skipped, warnings)
        val clocks = lenientList(root, "worldClocks", WorldClockCity.serializer(), R.plurals.backup_warn_clocks_skipped, warnings)
        val dates = lenientList(root, "exceptionDates", ExceptionDate.serializer(), R.plurals.backup_warn_dates_skipped, warnings)
        val history = (root["history"] as? JsonArray)?.let {
            lenientList(root, "history", AlarmEvent.serializer(), R.plurals.backup_warn_history_skipped, warnings)
        }
        if (droppedMissions > 0) warnings += ImportWarning(R.plurals.backup_warn_missions_unknown, count = droppedMissions)

        val file = BackupFile(
            formatVersion = version,
            app = BackupFormat.APP_ID,
            appVersion = (root["appVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
            createdAt = (root["createdAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            settings = withDefaultsMapped(root["settings"], settings),
            alarms = alarms,
            groups = groups,
            worldClocks = clocks,
            exceptionDates = dates,
            history = history,
        )
        return ParseOutcome.Parsed(file, warnings)
    }

    private fun <T> decodeOrNull(serializer: KSerializer<T>, element: JsonElement?): T? {
        if (element == null) return null
        return try {
            WakeJson.instance.decodeFromJsonElement(serializer, element)
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Decodes each array element on its own; unreadable ones are counted in one warning instead of failing the file. */
    private fun <T> lenientList(
        root: JsonObject,
        key: String,
        serializer: KSerializer<T>,
        skippedMessage: Int,
        warnings: MutableList<ImportWarning>,
        prepare: (JsonElement) -> JsonElement = { it },
    ): List<T> {
        val array = root[key] as? JsonArray ?: return emptyList()
        var skipped = 0
        val decoded = array.mapNotNull { element ->
            decodeOrNull(serializer, prepare(element)).also { if (it == null) skipped++ }
        }
        if (skipped > 0) warnings += ImportWarning(skippedMessage, count = skipped)
        return decoded
    }

    /**
     * A file from before the settings moved onto each alarm: its global volume-key and keep-in-front choices become the
     * alarm's own, and a strict alarm gets its way out, just as the database migration and first start do. A file that
     * already has the per-alarm values is left alone, so a backup of this version restores to exactly what was exported.
     */
    private fun withPerAlarmSettings(alarm: JsonElement, settings: AppSettings): JsonElement {
        if (alarm !is JsonObject) return alarm
        val anti = alarm["antiOversleep"]
        if (anti != null && anti !is JsonObject) return alarm
        return JsonObject(alarm + ("antiOversleep" to mapAntiOversleep(anti as JsonObject?, settings)))
    }

    private fun withDefaultsMapped(raw: JsonElement?, settings: AppSettings): AppSettings {
        val rawDefaults = ((raw as? JsonObject)?.get("defaults") as? JsonObject) ?: return settings.withGlobalsCopiedToDefaults()
        val rawAnti = rawDefaults["antiOversleep"]
        if (rawAnti != null && rawAnti !is JsonObject) return settings.withGlobalsCopiedToDefaults()
        val mapped = decodeOrNull(AntiOversleepSettings.serializer(), mapAntiOversleep(rawAnti as JsonObject?, settings)) ?: return settings
        return settings.copy(defaults = settings.defaults.copy(antiOversleep = mapped))
    }

    private fun AppSettings.withGlobalsCopiedToDefaults(): AppSettings =
        copy(defaults = defaults.copy(antiOversleep = defaults.antiOversleep.withGlobalsOf(this)))

    private fun mapAntiOversleep(anti: JsonObject?, settings: AppSettings): JsonObject {
        val source = anti ?: JsonObject(emptyMap())
        if ("volumeKeyAction" in source) return source
        val mapped = source.toMutableMap()
        mapped["volumeKeyAction"] = JsonPrimitive(settings.volumeKeyAction.name)
        mapped["returnToAlarm"] = JsonPrimitive(settings.keepRingScreenFront)
        val strict = (source["emergencyStopEnabled"] as? JsonPrimitive)?.booleanOrNull == false
        if (strict && "wayOutDelayMinutes" !in source) mapped["wayOutDelayMinutes"] = JsonPrimitive(STRICT_WAY_OUT_DELAY_MINUTES)
        return JsonObject(mapped)
    }

    /** Missions of a type this build has never heard of (from a newer app) cannot run; drop them instead of the alarm. */
    private fun dropUnknownMissions(alarm: JsonElement, onDropped: () -> Unit): JsonElement {
        if (alarm !is JsonObject) return alarm
        var changed = false
        val rewritten = alarm.mapValues { (key, value) ->
            if ((key == "missions" || key == "snoozeMissions") && value is JsonArray) {
                val known = value.filter { mission ->
                    val type = ((mission as? JsonObject)?.get("type") as? JsonPrimitive)?.content
                    (type in knownMissionTypes).also { if (!it) onDropped() }
                }
                if (known.size != value.size) changed = true
                JsonArray(known)
            } else {
                value
            }
        }
        return if (changed) JsonObject(rewritten) else alarm
    }
}
