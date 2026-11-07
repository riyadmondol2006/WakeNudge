package com.riyadm.wakenudge.engine

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
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
class RuntimeState @Inject constructor(@DeviceProtected private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("runtime_state", Context.MODE_PRIVATE)

    /** On their own so that saving the ring, which happens mid-mission, never has to write out every alarm's copy. */
    private val snapshotPrefs: SharedPreferences = context.getSharedPreferences("alarm_snapshots", Context.MODE_PRIVATE)

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

    /**
     * The step counter's total when a "Still awake?" check that needs proof started, per alarm. Not in a backup: it
     * means nothing on another phone, or after a restart.
     */
    fun wakeProofBaseline(alarmId: Long): Float? =
        prefs.getFloat(K_WAKE_PROOF_PREFIX + alarmId, Float.NaN).takeUnless { it.isNaN() }

    fun setWakeProofBaseline(alarmId: Long, total: Float?) = prefs.edit {
        if (total == null) remove(K_WAKE_PROOF_PREFIX + alarmId) else putFloat(K_WAKE_PROOF_PREFIX + alarmId, total)
    }

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

    /**
     * Whether the old global volume-key and keep-in-front settings have been copied onto every alarm. Kept here rather
     * than in the settings so a backup cannot carry it to a phone where the copy has not happened.
     */
    var perAlarmSettingsCopied: Boolean
        get() = prefs.getBoolean(K_PER_ALARM_COPIED, false)
        set(value) = prefs.edit(commit = true) { putBoolean(K_PER_ALARM_COPIED, value) }

    /** JSON of [DurableRingRecord] for the ring that is on, or was when the process died. Use [DurableRingStore]. */
    var durableRingJson: String?
        get() = prefs.getString(K_DURABLE_RING, null)
        set(value) = prefs.edit(commit = true) { if (value == null) remove(K_DURABLE_RING) else putString(K_DURABLE_RING, value) }

    /** JSON of the rings waiting behind the current one. Use [DurableRingStore]. */
    var durableQueueJson: String?
        get() = prefs.getString(K_DURABLE_QUEUE, null)
        set(value) = prefs.edit(commit = true) { if (value == null) remove(K_DURABLE_QUEUE) else putString(K_DURABLE_QUEUE, value) }

    internal fun clearDurableRing() = prefs.edit(commit = true) { remove(K_DURABLE_RING); remove(K_DURABLE_QUEUE) }

    /** Test seam: what [bootCount] reports. */
    @Volatile internal var bootCountSource: (() -> Int)? = null

    /** How many times this phone has started, or -1 when the system will not say. */
    fun bootCount(): Int = bootCountSource?.invoke()
        ?: runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    /** The newest process exit already looked at; see `ExitPostMortem`. */
    var lastExitSeenAt: Long
        get() = prefs.getLong(K_LAST_EXIT_SEEN, 0)
        set(value) = prefs.edit { putLong(K_LAST_EXIT_SEEN, value) }

    /** The ring time the evening check last looked at; a trigger firing again for the same one does nothing. See [TonightCheck]. */
    var tonightCheckedFor: Long
        get() = prefs.getLong(K_TONIGHT_CHECKED, 0)
        set(value) = prefs.edit { putLong(K_TONIGHT_CHECKED, value) }

    /** JSON of what the ledger has found. Use [RingLedger]. */
    var ringLedgerJson: String?
        get() = prefs.getString(K_RING_LEDGER, null)
        set(value) = prefs.edit { if (value == null) remove(K_RING_LEDGER) else putString(K_RING_LEDGER, value) }

    /** JSON of an alarm's saved copy. Use [AlarmSnapshots]. */
    fun alarmSnapshotJson(alarmId: Long): String? = snapshotPrefs.getString(SNAPSHOT_PREFIX + alarmId, null)

    fun setAlarmSnapshotJson(alarmId: Long, json: String?) = snapshotPrefs.edit {
        if (json == null) remove(SNAPSHOT_PREFIX + alarmId) else putString(SNAPSHOT_PREFIX + alarmId, json)
    }

    fun replaceAlarmSnapshots(jsonById: Map<Long, String>) = snapshotPrefs.edit {
        snapshotPrefs.all.keys.filter { it.startsWith(SNAPSHOT_PREFIX) }.forEach { remove(it) }
        jsonById.forEach { (id, json) -> putString(SNAPSHOT_PREFIX + id, json) }
    }

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
        const val K_PER_ALARM_COPIED = "per_alarm_settings_copied"
        const val K_DURABLE_RING = "durable_ring"
        const val K_DURABLE_QUEUE = "durable_queue"
        const val K_LAST_EXIT_SEEN = "last_exit_seen_at"
        const val K_TONIGHT_CHECKED = "tonight_checked_for"
        const val K_RING_LEDGER = "ring_ledger"
        const val SNAPSHOT_PREFIX = "alarm_snapshot_"
        const val K_WAKE_PROOF_PREFIX = "wake_proof_baseline_"
        const val MAX_CRASHES_KEPT = 10
    }
}
