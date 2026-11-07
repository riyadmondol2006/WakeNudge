package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog

/**
 * A copy of each alarm that a plain default would ring more softly than it asks to be rung, so a database that fails at
 * the moment an alarm fires still rings it with its missions and its strictness. A plain alarm needs no copy: the default
 * that stands in for it behaves the same way. Nothing here throws.
 */
class AlarmSnapshots(private val runtime: RuntimeState, private val diagnostics: DiagnosticsLog) {

    fun get(alarmId: Long): Alarm? = try {
        runtime.alarmSnapshotJson(alarmId)?.let { WakeJson.instance.decodeFromString(Alarm.serializer(), it) }
    } catch (t: Throwable) {
        diagnostics.warn("Ring", "the saved copy of alarm $alarmId could not be read: ${t.message}")
        null
    }

    /** The whole set after a full scheduling pass: copies of alarms that are gone or no longer need one go too. */
    fun sync(alarms: List<Alarm>) = guarded {
        runtime.replaceAlarmSnapshots(alarms.filter(::worthKeeping).associate { it.id to encode(it) })
    }

    fun update(alarm: Alarm) = guarded {
        runtime.setAlarmSnapshotJson(alarm.id, if (worthKeeping(alarm)) encode(alarm) else null)
    }

    fun forget(alarmId: Long) = guarded { runtime.setAlarmSnapshotJson(alarmId, null) }

    private fun encode(alarm: Alarm) = WakeJson.instance.encodeToString(Alarm.serializer(), alarm)

    private inline fun guarded(block: () -> Unit) = try {
        block()
    } catch (t: Throwable) {
        diagnostics.warn("Ring", "keeping a copy of an alarm failed: ${t.message}")
    }

    companion object {
        fun worthKeeping(alarm: Alarm): Boolean {
            val anti = alarm.antiOversleep
            return alarm.missions.isNotEmpty() || alarm.snoozeMissions.isNotEmpty() || !alarm.dismissesWithOneTap ||
                !anti.emergencyStopEnabled || anti.keepsComingBack
        }
    }
}
