package com.riyadm.wakenudge.health

import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import androidx.annotation.StringRes
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.AudioRoute
import kotlin.math.abs

/** What the evaluator needs from Android to word its findings; faked in tests. */
interface HealthResources {
    fun text(@StringRes id: Int, vararg args: Any): String

    /** A moment in the user's locale and zone, e.g. "21 Oct 2026, 07:30". */
    fun dateTime(millis: Long): String

    fun intentFor(kind: HealthFixKind): Intent
}

/** Phone makers whose power managers are known to stop apps that Android itself would leave alone. */
enum class Oem(private val markers: List<String>, @StringRes val advice: Int) {
    XIAOMI(listOf("xiaomi", "redmi", "poco"), R.string.health_oem_xiaomi),
    HUAWEI(listOf("huawei", "honor"), R.string.health_oem_huawei),
    ONEPLUS(listOf("oneplus"), R.string.health_oem_oneplus),
    OPPO(listOf("oppo", "realme"), R.string.health_oem_oppo),
    VIVO(listOf("vivo", "iqoo"), R.string.health_oem_vivo),
    SAMSUNG(listOf("samsung"), R.string.health_oem_samsung),
    ASUS(listOf("asus"), R.string.health_oem_asus),
    MEIZU(listOf("meizu"), R.string.health_oem_meizu),
    HMD(listOf("hmd", "nokia"), R.string.health_oem_hmd),
    ;

    companion object {
        fun of(manufacturer: String): Oem? {
            val name = manufacturer.trim().lowercase()
            return entries.firstOrNull { oem -> oem.markers.any { name.contains(it) } }
        }
    }
}

/**
 * Turns a [HealthSnapshot] into findings. Pure, so every combination of device state can be unit-tested. Order is by
 * how directly a finding decides whether the next alarm rings; the UI may re-sort with [HealthReport.bySeverity].
 */
class HealthEvaluator(private val res: HealthResources) {

    fun evaluate(s: HealthSnapshot): List<HealthItem> = buildList {
        add(armedAlarms(s))
        add(systemNextAlarm(s))
        add(notifications(s))
        add(ringChannel(s))
        add(fullScreen(s))
        add(exactAlarms(s))
        add(battery(s))
        standby(s)?.let(::add)
        background(s)?.let(::add)
        add(doNotDisturb(s))
        add(volume(s))
        add(ringer(s))
        add(audioOutput(s))
        add(dateAndTime(s))
        add(directBoot(s))
        add(reschedule(s))
        add(lastRing(s))
        add(watchdog(s))
        add(serviceFailure(s))
        schedulingProblem(s)?.let(::add)
        audioProblem(s)?.let(::add)
        ringCrash(s)?.let(::add)
        batterySaver(s)?.let(::add)
        tts(s)?.let(::add)
        vibrator(s)?.let(::add)
        oemHint(s)?.let(::add)
    }

    // ---- scheduling truth ---------------------------------------------------------------------------------------

    private fun armedAlarms(s: HealthSnapshot): HealthItem = when {
        s.enabledAlarms == 0 -> item("armed_alarms", Severity.OK, R.string.health_title_armed, R.string.health_detail_armed_none)
        s.armedWithOs < s.expectedArmed -> item(
            "armed_alarms", Severity.ERROR, R.string.health_title_armed,
            R.string.health_detail_armed_missing, s.armedWithOs, s.expectedArmed,
        )
        s.recordedTriggers < s.expectedArmed -> item(
            "armed_alarms", Severity.WARNING, R.string.health_title_armed,
            R.string.health_detail_armed_bookkeeping, s.recordedTriggers, s.expectedArmed,
        )
        s.enabledAlarms > s.expectedArmed -> item(
            "armed_alarms", Severity.OK, R.string.health_title_armed,
            R.string.health_detail_armed_ok_paused, s.expectedArmed, s.enabledAlarms - s.expectedArmed,
        )
        else -> item("armed_alarms", Severity.OK, R.string.health_title_armed, R.string.health_detail_armed_ok, s.expectedArmed)
    }

    /**
     * `AlarmManager.getNextAlarmClock` is the system-wide next alarm of *every* app. An earlier one is therefore not a
     * fault; a missing one, or one later than ours, means the OS would not wake the phone for our alarm.
     */
    private fun systemNextAlarm(s: HealthSnapshot): HealthItem {
        val id = "system_next_alarm"
        val title = R.string.health_title_system_next
        val app = s.appNextAlarmAt ?: return item(id, Severity.OK, title, R.string.health_detail_system_none)
        val system = s.systemNextAlarmAt
            ?: return item(id, Severity.ERROR, title, R.string.health_detail_system_missing, res.dateTime(app))
        return when {
            abs(system - app) <= ONE_MINUTE_MS -> item(id, Severity.OK, title, R.string.health_detail_system_ok, res.dateTime(app))
            system < app -> item(id, Severity.INFO, title, R.string.health_detail_system_earlier, res.dateTime(system), res.dateTime(app))
            else -> item(id, Severity.ERROR, title, R.string.health_detail_system_mismatch, res.dateTime(app), res.dateTime(system))
        }
    }

    // ---- notifications and permissions --------------------------------------------------------------------------

    private fun notifications(s: HealthSnapshot): HealthItem {
        val title = R.string.health_title_notifications
        return when {
            s.sdkInt >= 33 && !s.postNotificationsGranted -> item(
                "notifications", Severity.ERROR, title, R.string.health_detail_notif_permission,
                fix = HealthFixKind.APP_NOTIFICATIONS to R.string.health_fix_notifications,
            )
            !s.notificationsEnabled -> item(
                "notifications", Severity.ERROR, title, R.string.health_detail_notif_disabled,
                fix = HealthFixKind.APP_NOTIFICATIONS to R.string.health_fix_notifications,
            )
            else -> item("notifications", Severity.OK, title, R.string.health_detail_allowed)
        }
    }

    private fun ringChannel(s: HealthSnapshot): HealthItem {
        val title = R.string.health_title_ring_channel
        val fix = HealthFixKind.RING_CHANNEL to R.string.health_fix_channel
        return when (val importance = s.ringChannelImportance) {
            null -> item("ring_channel", Severity.WARNING, title, R.string.health_detail_channel_missing)
            NotificationManager.IMPORTANCE_NONE -> item("ring_channel", Severity.ERROR, title, R.string.health_detail_channel_blocked, fix = fix)
            else -> when {
                importance < NotificationManager.IMPORTANCE_HIGH -> item("ring_channel", Severity.WARNING, title, R.string.health_detail_channel_lowered, fix = fix)
                // The last-resort alarm (when the ring service cannot start) is that channel's own sound: blocked, it
                // would be silent exactly when everything else has already failed.
                s.fallbackChannelImportance == NotificationManager.IMPORTANCE_NONE -> item(
                    "ring_channel", Severity.WARNING, title, R.string.health_detail_fallback_channel_blocked,
                    fix = HealthFixKind.APP_NOTIFICATIONS to R.string.health_fix_notifications,
                )
                else -> item("ring_channel", Severity.OK, title, R.string.health_detail_channel_ok)
            }
        }
    }

    private fun fullScreen(s: HealthSnapshot): HealthItem {
        val title = R.string.health_title_full_screen
        return when {
            s.sdkInt < 34 -> item("full_screen", Severity.OK, title, R.string.health_detail_full_screen_auto)
            !s.fullScreenIntentAllowed -> item(
                "full_screen", Severity.WARNING, title, R.string.health_detail_full_screen_off,
                fix = HealthFixKind.FULL_SCREEN_INTENT to R.string.health_fix_full_screen,
            )
            else -> item("full_screen", Severity.OK, title, R.string.health_detail_allowed)
        }
    }

    /**
     * On Android 12 and 12L the alarm clock itself needs the exact-alarm permission ("Alarms & reminders"), which the
     * user can switch off; without it alarms are armed inexactly and may ring up to an hour late. From Android 13 the
     * app holds USE_EXACT_ALARM, which cannot be revoked, and only timers and reminders would be affected.
     */
    private fun exactAlarms(s: HealthSnapshot): HealthItem {
        val title = R.string.health_title_exact_alarms
        if (s.canScheduleExactAlarms) return item("exact_alarms", Severity.OK, title, R.string.health_detail_allowed)
        if (s.sdkInt in 31..32) {
            return item("exact_alarms", Severity.ERROR, title, R.string.health_detail_exact_off_alarms, fix = HealthFixKind.EXACT_ALARMS to R.string.health_fix_exact_alarms)
        }
        return item("exact_alarms", Severity.WARNING, title, R.string.health_detail_exact_off, fix = HealthFixKind.APP_DETAILS to R.string.health_fix_app_details)
    }

    // ---- power management ---------------------------------------------------------------------------------------

    private fun battery(s: HealthSnapshot): HealthItem {
        val title = R.string.health_title_battery
        if (s.ignoringBatteryOptimizations) return item("battery_optimization", Severity.OK, title, R.string.health_detail_battery_exempt)
        val aggressive = Oem.of(s.manufacturer) != null
        return item(
            "battery_optimization",
            if (aggressive) Severity.WARNING else Severity.INFO,
            title,
            if (aggressive) R.string.health_detail_battery_oem else R.string.health_detail_battery_optimized,
            fix = HealthFixKind.BATTERY_OPTIMIZATION to R.string.health_fix_battery,
        )
    }

    private fun standby(s: HealthSnapshot): HealthItem? {
        val bucket = s.standbyBucket ?: return null
        val title = R.string.health_title_standby
        val fix = HealthFixKind.BATTERY_OPTIMIZATION to R.string.health_fix_battery
        return when {
            bucket >= HealthSnapshot.STANDBY_RESTRICTED -> item("standby_bucket", Severity.WARNING, title, R.string.health_detail_standby_restricted, fix = fix)
            bucket >= HealthSnapshot.STANDBY_RARE -> item("standby_bucket", Severity.WARNING, title, R.string.health_detail_standby_rare, fix = fix)
            else -> item("standby_bucket", Severity.OK, title, R.string.health_detail_standby_ok)
        }
    }

    private fun background(s: HealthSnapshot): HealthItem? {
        val restricted = s.backgroundRestricted ?: return null
        val title = R.string.health_title_background
        return if (restricted) {
            item(
                "background_restricted", Severity.ERROR, title, R.string.health_detail_background_restricted,
                fix = HealthFixKind.APP_DETAILS to R.string.health_fix_app_details,
            )
        } else {
            item("background_restricted", Severity.OK, title, R.string.health_detail_background_ok)
        }
    }

    // ---- sound --------------------------------------------------------------------------------------------------

    private fun doNotDisturb(s: HealthSnapshot): HealthItem {
        val title = R.string.health_title_dnd
        val fix = HealthFixKind.DND_SETTINGS to R.string.health_fix_dnd
        return when (s.interruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> item("dnd", Severity.ERROR, title, R.string.health_detail_dnd_total_silence, fix = fix)
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> when {
                !s.dndAllowsAlarms -> item("dnd", Severity.ERROR, title, R.string.health_detail_dnd_priority_blocks, fix = fix)
                // The default policy allows alarms, but the schedule or mode that is on has its own and Android muted
                // the alarm stream: no app can undo that, so the alarm would be silent.
                s.alarmStreamMuted -> item("dnd", Severity.ERROR, title, R.string.health_detail_dnd_mode_blocks, fix = fix)
                else -> item("dnd", Severity.OK, title, R.string.health_detail_dnd_priority_ok)
            }
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> item("dnd", Severity.OK, title, R.string.health_detail_dnd_alarms_only)
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> item("dnd", Severity.INFO, title, R.string.health_detail_dnd_unknown)
            else -> item("dnd", Severity.OK, title, R.string.health_detail_dnd_off)
        }
    }

    /**
     * The engine raises a muted or low alarm stream when an alarm rings (see `VolumePlanner`), so a low slider is a
     * risk rather than a certainty: it matters if Android refuses the change, and for alarms left on system volume.
     */
    private fun volume(s: HealthSnapshot): HealthItem {
        val title = R.string.health_title_volume
        val fix = HealthFixKind.SOUND to R.string.health_fix_sound
        if (s.volumeFixed || s.alarmVolumeMax <= 0) return item("alarm_volume", Severity.OK, title, R.string.health_detail_volume_fixed)
        val percent = s.alarmVolume * 100 / s.alarmVolumeMax
        return when {
            s.alarmVolume <= 0 -> item("alarm_volume", Severity.WARNING, title, R.string.health_detail_volume_muted, fix = fix)
            percent < LOW_VOLUME_PERCENT -> item(
                "alarm_volume",
                if (s.alarmsUsingSystemVolume > 0) Severity.WARNING else Severity.INFO,
                title, R.string.health_detail_volume_low, percent, fix = fix,
            )
            else -> item("alarm_volume", Severity.OK, title, R.string.health_detail_volume_ok, percent)
        }
    }

    private fun ringer(s: HealthSnapshot): HealthItem {
        val title = R.string.health_title_ringer
        return if (s.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            item("ringer_mode", Severity.OK, title, R.string.health_detail_ringer_normal)
        } else {
            item("ringer_mode", Severity.INFO, title, R.string.health_detail_ringer_silent)
        }
    }

    private fun audioOutput(s: HealthSnapshot): HealthItem {
        val title = R.string.health_title_audio_output
        if (s.headsetNames.isEmpty()) return item("audio_output", Severity.OK, title, R.string.health_detail_audio_none)
        val names = s.headsetNames.joinToString(", ")
        return when {
            s.audioRoute == AudioRoute.PHONE_SPEAKER ->
                item("audio_output", Severity.INFO, title, R.string.health_detail_audio_speaker, names)
            s.hearingSafetyCap ->
                item("audio_output", Severity.INFO, title, R.string.health_detail_audio_headset_capped, names, s.headphoneCapPercent)
            else -> item("audio_output", Severity.INFO, title, R.string.health_detail_audio_headset_loud, names)
        }
    }

    // ---- clock and storage --------------------------------------------------------------------------------------

    private fun dateAndTime(s: HealthSnapshot): HealthItem {
        val title = R.string.health_title_time
        return if (s.autoTime == false || s.autoTimeZone == false) {
            item(
                "date_time", Severity.WARNING, title, R.string.health_detail_time_manual,
                fix = HealthFixKind.DATE_TIME to R.string.health_fix_date_time,
            )
        } else {
            item("date_time", Severity.OK, title, R.string.health_detail_time_auto)
        }
    }

    private fun directBoot(s: HealthSnapshot): HealthItem = if (s.alarmStorageDeviceProtected) {
        item("direct_boot", Severity.INFO, R.string.health_title_direct_boot, R.string.health_detail_direct_boot_ok)
    } else {
        item("direct_boot", Severity.WARNING, R.string.health_title_direct_boot, R.string.health_detail_direct_boot_bad)
    }

    // ---- scheduler history --------------------------------------------------------------------------------------

    private fun reschedule(s: HealthSnapshot): HealthItem {
        val title = R.string.health_title_reschedule
        if (s.lastRescheduleAt <= 0) {
            return if (s.enabledAlarms > 0) {
                item("scheduler", Severity.WARNING, title, R.string.health_detail_reschedule_never)
            } else {
                item("scheduler", Severity.INFO, title, R.string.health_detail_reschedule_idle)
            }
        }
        return item(
            "scheduler", Severity.OK, title, R.string.health_detail_reschedule_last,
            res.dateTime(s.lastRescheduleAt), age(s.nowMillis, s.lastRescheduleAt), s.lastRescheduleReason.ifBlank { "?" },
        )
    }

    private fun lastRing(s: HealthSnapshot): HealthItem = if (s.lastRingAt <= 0) {
        item("last_ring", Severity.INFO, R.string.health_title_last_ring, R.string.health_detail_ring_never)
    } else {
        item("last_ring", Severity.INFO, R.string.health_title_last_ring, R.string.health_detail_ring_last, res.dateTime(s.lastRingAt), age(s.nowMillis, s.lastRingAt))
    }

    /** The watchdog re-registers everything every ~6 hours; a long silence means the OS is not running our background work. */
    private fun watchdog(s: HealthSnapshot): HealthItem {
        val title = R.string.health_title_watchdog
        if (s.lastWatchdogAt <= 0) {
            val longWait = s.lastRescheduleAt > 0 && s.nowMillis - s.lastRescheduleAt > WATCHDOG_STALE_MS / 2
            return if (longWait && s.enabledAlarms > 0) {
                item("watchdog", Severity.WARNING, title, R.string.health_detail_watchdog_stale_never)
            } else {
                item("watchdog", Severity.INFO, title, R.string.health_detail_watchdog_pending)
            }
        }
        val since = s.nowMillis - s.lastWatchdogAt
        return if (since > WATCHDOG_STALE_MS && s.enabledAlarms > 0) {
            item("watchdog", Severity.WARNING, title, R.string.health_detail_watchdog_stale, age(s.nowMillis, s.lastWatchdogAt))
        } else {
            item("watchdog", Severity.OK, title, R.string.health_detail_watchdog_ok, age(s.nowMillis, s.lastWatchdogAt))
        }
    }

    private fun serviceFailure(s: HealthSnapshot): HealthItem = if (s.lastServiceStartFailure.isBlank()) {
        item("service_start", Severity.OK, R.string.health_title_service_failure, R.string.health_detail_service_ok)
    } else {
        item(
            "service_start", Severity.WARNING, R.string.health_title_service_failure,
            R.string.health_detail_service_failed, s.lastServiceStartFailure.take(MAX_FAILURE_TEXT),
            fix = HealthFixKind.APP_DETAILS to R.string.health_fix_app_details,
        )
    }

    // ---- things the engine noticed while running -----------------------------------------------------------------

    /** Only recent problems are shown: a fixed one should not nag for ever. Returns the text after the timestamp. */
    private fun recentProblem(record: String, now: Long): Pair<Long, String>? {
        if (record.isBlank()) return null
        val at = record.substringBefore('|').toLongOrNull() ?: return null
        if (now - at > HealthSnapshot.PROBLEM_MEMORY_MS) return null
        return at to record.substringAfter('|').take(MAX_FAILURE_TEXT)
    }

    private fun schedulingProblem(s: HealthSnapshot): HealthItem? {
        val (at, text) = recentProblem(s.schedulingProblem, s.nowMillis) ?: return null
        return item(
            "scheduling_problem", Severity.WARNING, R.string.health_title_scheduling_problem,
            R.string.health_detail_scheduling_problem, res.dateTime(at), text,
        )
    }

    private fun audioProblem(s: HealthSnapshot): HealthItem? {
        val (at, text) = recentProblem(s.audioProblem, s.nowMillis) ?: return null
        return item(
            "audio_problem", Severity.WARNING, R.string.health_title_audio_problem,
            R.string.health_detail_audio_problem, res.dateTime(at), text,
        )
    }

    private fun ringCrash(s: HealthSnapshot): HealthItem? {
        if (s.lastRingCrashAt <= 0 || s.nowMillis - s.lastRingCrashAt > HealthSnapshot.PROBLEM_MEMORY_MS) return null
        return item(
            "ring_crash", Severity.WARNING, R.string.health_title_ring_crash,
            R.string.health_detail_ring_crash, res.dateTime(s.lastRingCrashAt),
        )
    }

    private fun batterySaver(s: HealthSnapshot): HealthItem? {
        if (!s.batterySaver) return null
        return item("battery_saver", Severity.INFO, R.string.health_title_battery_saver, R.string.health_detail_battery_saver)
    }

    // ---- features that only matter when used ---------------------------------------------------------------------

    private fun tts(s: HealthSnapshot): HealthItem? {
        if (s.voiceAlarms <= 0) return null
        val title = R.string.health_title_tts
        return if (s.ttsAvailable) {
            item("tts", Severity.OK, title, R.string.health_detail_tts_ok)
        } else {
            item(
                "tts", Severity.WARNING, title, R.string.health_detail_tts_missing,
                fix = HealthFixKind.TTS_SETTINGS to R.string.health_fix_tts,
            )
        }
    }

    private fun vibrator(s: HealthSnapshot): HealthItem? {
        if (s.vibrationAlarms <= 0) return null
        val title = R.string.health_title_vibrator
        return if (s.hasVibrator) {
            item("vibrator", Severity.OK, title, R.string.health_detail_vibrator_ok)
        } else {
            item("vibrator", Severity.INFO, title, R.string.health_detail_vibrator_missing)
        }
    }

    private fun oemHint(s: HealthSnapshot): HealthItem? {
        val oem = Oem.of(s.manufacturer) ?: return null
        return item(
            "oem_hint", Severity.INFO, R.string.health_title_oem, oem.advice,
            fix = HealthFixKind.APP_DETAILS to R.string.health_fix_app_details,
        )
    }

    // ---- plumbing -----------------------------------------------------------------------------------------------

    private fun age(now: Long, then: Long): String = res.text(R.string.health_ago, TimeText.duration((now - then).coerceAtLeast(0)))

    private fun item(
        id: String,
        severity: Severity,
        @StringRes title: Int,
        @StringRes detail: Int,
        vararg args: Any,
        fix: Pair<HealthFixKind, Int>? = null,
    ) = HealthItem(
        id = id,
        severity = severity,
        title = title,
        detail = res.text(detail, *args),
        fix = fix?.let { (kind, label) -> HealthFix(label, res.intentFor(kind), kind) },
    )

    private companion object {
        const val ONE_MINUTE_MS = 60_000L
        const val LOW_VOLUME_PERCENT = 20
        const val WATCHDOG_STALE_MS = 24 * 60 * 60_000L
        const val MAX_FAILURE_TEXT = 200
    }
}
