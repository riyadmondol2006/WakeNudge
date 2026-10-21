package com.riyadm.wakenudge.health

import android.app.NotificationManager
import android.media.AudioManager
import com.riyadm.wakenudge.domain.model.AudioRoute

/**
 * Everything the health checks look at, as plain values: the Android-facing [HealthChecker] fills it in from system
 * services and [HealthEvaluator] turns it into findings, so the judgement can be unit-tested with any combination.
 * The defaults describe a healthy stock device, which keeps tests down to the one field they are about.
 */
data class HealthSnapshot(
    val sdkInt: Int = 35,
    val manufacturer: String = "Google",
    val nowMillis: Long = 1_800_000_000_000L,

    // Notifications
    val postNotificationsGranted: Boolean = true,
    val notificationsEnabled: Boolean = true,
    /** Importance of the ring channel; null when the channel does not exist. */
    val ringChannelImportance: Int? = NotificationManager.IMPORTANCE_HIGH,
    val fullScreenIntentAllowed: Boolean = true,

    // Scheduling permissions and power management
    val canScheduleExactAlarms: Boolean = true,
    val ignoringBatteryOptimizations: Boolean = true,
    /** `UsageStatsManager.STANDBY_BUCKET_*`; null before Android 9. */
    val standbyBucket: Int? = STANDBY_ACTIVE,
    /** Null before Android 9. */
    val backgroundRestricted: Boolean? = false,

    // Do Not Disturb, volume and routing
    val interruptionFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL,
    /** Whether the current Do Not Disturb policy lets alarm sounds through. */
    val dndAllowsAlarms: Boolean = true,
    val alarmVolume: Int = 5,
    val alarmVolumeMax: Int = 7,
    val volumeFixed: Boolean = false,
    val ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
    val headsetNames: List<String> = emptyList(),
    val audioRoute: AudioRoute = AudioRoute.PHONE_SPEAKER,
    val hearingSafetyCap: Boolean = true,
    val headphoneCapPercent: Int = 60,

    // Storage and clock
    val alarmStorageDeviceProtected: Boolean = true,
    /** Null when the system setting could not be read. */
    val autoTime: Boolean? = true,
    val autoTimeZone: Boolean? = true,

    // Scheduler bookkeeping
    val totalAlarms: Int = 3,
    val enabledAlarms: Int = 3,
    /** Enabled alarms that will really ring at some point (not paused by vacation, a group or an exhausted date). */
    val expectedArmed: Int = 3,
    /** Of those, how many have a live trigger registered with AlarmManager. */
    val armedWithOs: Int = 3,
    /** Of those, how many the app's own bookkeeping lists as registered. */
    val recordedTriggers: Int = 3,
    val lastRescheduleAt: Long = 1_799_999_000_000L,
    val lastRescheduleReason: String = "ui",
    val lastRingAt: Long = 1_799_990_000_000L,
    val lastWatchdogAt: Long = 1_799_995_000_000L,
    val lastServiceStartFailure: String = "",
    val appNextAlarmAt: Long? = 1_800_030_000_000L,
    val systemNextAlarmAt: Long? = 1_800_030_000_000L,

    // Features used by the enabled alarms
    val voiceAlarms: Int = 0,
    val ttsAvailable: Boolean = true,
    val vibrationAlarms: Int = 0,
    val hasVibrator: Boolean = true,
    /** Enabled alarms that leave the volume to the system (level 0). */
    val alarmsUsingSystemVolume: Int = 0,
) {
    companion object {
        // UsageStatsManager.STANDBY_BUCKET_*, spelled out so the evaluator needs no Android class.
        const val STANDBY_EXEMPTED = 5
        const val STANDBY_ACTIVE = 10
        const val STANDBY_WORKING_SET = 20
        const val STANDBY_FREQUENT = 30
        const val STANDBY_RARE = 40
        const val STANDBY_RESTRICTED = 45
        const val STANDBY_NEVER = 50
    }
}
