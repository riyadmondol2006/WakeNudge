package com.riyadm.wakenudge.engine

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.riyadm.wakenudge.di.DeviceProtected
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small, synchronous, crash-safe key/value state the engine needs before (or without) the database:
 * the system volume to restore, the alarm that is currently ringing, scheduler bookkeeping for diagnostics.
 * It lives in device-protected storage so it is readable during Direct Boot.
 */
@Singleton
class RuntimeState @Inject constructor(@DeviceProtected context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("runtime_state", Context.MODE_PRIVATE)

    /** Alarm-stream volume index that was active before we raised it, or -1 when we changed nothing. */
    var savedAlarmVolume: Int
        get() = prefs.getInt(K_SAVED_VOLUME, -1)
        set(value) = prefs.edit(commit = true) { putInt(K_SAVED_VOLUME, value) }

    /** Alarm id of the ring session that is (or was, if the process died) active; -1 when idle. */
    var activeAlarmId: Long
        get() = prefs.getLong(K_ACTIVE_ALARM, -1)
        set(value) = prefs.edit(commit = true) { putLong(K_ACTIVE_ALARM, value) }

    var activeEventId: Long
        get() = prefs.getLong(K_ACTIVE_EVENT, -1)
        set(value) = prefs.edit(commit = true) { putLong(K_ACTIVE_EVENT, value) }

    var lastRescheduleAt: Long
        get() = prefs.getLong(K_LAST_RESCHEDULE, 0)
        set(value) = prefs.edit { putLong(K_LAST_RESCHEDULE, value) }

    var lastRescheduleReason: String
        get() = prefs.getString(K_LAST_RESCHEDULE_REASON, "") ?: ""
        set(value) = prefs.edit { putString(K_LAST_RESCHEDULE_REASON, value) }

    var lastFireAt: Long
        get() = prefs.getLong(K_LAST_FIRE, 0)
        set(value) = prefs.edit { putLong(K_LAST_FIRE, value) }

    var lastServiceStartFailure: String
        get() = prefs.getString(K_LAST_FGS_FAILURE, "") ?: ""
        set(value) = prefs.edit { putString(K_LAST_FGS_FAILURE, value) }

    var lastWatchdogAt: Long
        get() = prefs.getLong(K_LAST_WATCHDOG, 0)
        set(value) = prefs.edit { putLong(K_LAST_WATCHDOG, value) }

    /** Times (epoch millis) at which the app died while an alarm was ringing; only recent ones matter. */
    fun ringCrashTimes(): List<Long> =
        prefs.getString(K_RING_CRASHES, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    /** Written with `commit` because the process is about to die and an asynchronous write would be lost. */
    fun recordRingCrash(at: Long) {
        val kept = (ringCrashTimes() + at).takeLast(MAX_CRASHES_KEPT)
        prefs.edit(commit = true) { putString(K_RING_CRASHES, kept.joinToString(",")) }
    }

    internal fun clearRingCrashes() = prefs.edit(commit = true) { remove(K_RING_CRASHES) }

    /** The last time playback needed a fallback (broken file, dead player, emergency beeper): "epoch|what happened". */
    var lastAudioProblem: String
        get() = prefs.getString(K_AUDIO_PROBLEM, "") ?: ""
        set(value) = prefs.edit { putString(K_AUDIO_PROBLEM, value) }

    /** Why the scheduler could not arm everything on its last pass; blank when it could. */
    var lastSchedulingProblem: String
        get() = prefs.getString(K_SCHEDULING_PROBLEM, "") ?: ""
        set(value) = prefs.edit { putString(K_SCHEDULING_PROBLEM, value) }

    /** JSON of the throw-away alarm used by the "test alarm" feature. */
    var testAlarmJson: String?
        get() = prefs.getString(K_TEST_ALARM, null)
        set(value) = prefs.edit { if (value == null) remove(K_TEST_ALARM) else putString(K_TEST_ALARM, value) }

    /** Trigger time we last registered per alarm (alarmId -> epoch millis); used to detect alarms missed while off. */
    fun registeredTriggers(): Map<Long, Long> =
        prefs.getString(K_TRIGGERS, "").orEmpty().split(';').mapNotNull {
            val parts = it.split(':')
            if (parts.size == 2) parts[0].toLongOrNull()?.let { id -> parts[1].toLongOrNull()?.let { at -> id to at } } else null
        }.toMap()

    fun setRegisteredTriggers(map: Map<Long, Long>) = prefs.edit {
        putString(K_TRIGGERS, map.entries.joinToString(";") { "${it.key}:${it.value}" })
    }

    private companion object {
        const val K_SAVED_VOLUME = "saved_alarm_volume"
        const val K_ACTIVE_ALARM = "active_alarm_id"
        const val K_ACTIVE_EVENT = "active_event_id"
        const val K_LAST_RESCHEDULE = "last_reschedule_at"
        const val K_LAST_RESCHEDULE_REASON = "last_reschedule_reason"
        const val K_LAST_FIRE = "last_fire_at"
        const val K_LAST_FGS_FAILURE = "last_fgs_failure"
        const val K_LAST_WATCHDOG = "last_watchdog_at"
        const val K_TEST_ALARM = "test_alarm_json"
        const val K_TRIGGERS = "registered_triggers"
        const val K_RING_CRASHES = "ring_crash_times"
        const val K_AUDIO_PROBLEM = "last_audio_problem"
        const val K_SCHEDULING_PROBLEM = "last_scheduling_problem"
        const val MAX_CRASHES_KEPT = 10
    }
}
