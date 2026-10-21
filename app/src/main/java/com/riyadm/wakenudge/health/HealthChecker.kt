package com.riyadm.wakenudge.health

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.format.DateUtils
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.di.DeviceProtected
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.engine.PendingAlarms
import com.riyadm.wakenudge.engine.RuntimeState
import com.riyadm.wakenudge.engine.SchedulingContextProvider
import com.riyadm.wakenudge.engine.TriggerKind
import com.riyadm.wakenudge.engine.audio.AudioRouteAdvisor
import com.riyadm.wakenudge.engine.notify.Channels
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Android-backed wording and intents for [HealthEvaluator]. */
class AndroidHealthResources(private val context: Context) : HealthResources {
    override fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    override fun dateTime(millis: Long): String = DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
    )

    override fun intentFor(kind: HealthFixKind): Intent = HealthIntents.forKind(kind, context.packageName)
}

/**
 * Asks Android (and the scheduler's own records) whether the next alarm will really ring, and says so in plain words.
 * Collection ([snapshot]) is kept apart from judgement ([HealthEvaluator]); every system call is guarded because
 * vendor builds throw in places AOSP does not, and one failing query must not hide the other checks.
 */
@Singleton
class HealthChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    @DeviceProtected private val storageContext: Context,
    private val pending: PendingAlarms,
    private val runtime: RuntimeState,
    private val alarms: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val calculator: NextTriggerCalculator,
    private val schedulingContexts: SchedulingContextProvider,
    private val settings: SettingsRepository,
    private val audioRoutes: AudioRouteAdvisor,
    private val time: TimeSource,
) {
    private val evaluator = HealthEvaluator(AndroidHealthResources(context))

    suspend fun check(): HealthReport = HealthReport(evaluator.evaluate(snapshot()))

    /** Public so the support report can evaluate a redacted copy of exactly what [check] saw. */
    suspend fun snapshot(): HealthSnapshot = withContext(Dispatchers.Default) {
        val now = time.now()
        val all = alarms.getAlarms()
        val enabled = all.filter { it.enabled }
        val schedulingContext = schedulingContexts.load()
        val willRing = enabled.filter { calculator.next(it, now, schedulingContext.forAlarm(it)) != null }
        val recorded = runtime.registeredTriggers().keys
        val appSettings = settings.current()

        val notifications = context.getSystemService(NotificationManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val sdk = Build.VERSION.SDK_INT

        HealthSnapshot(
            sdkInt = sdk,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            nowMillis = time.nowMillis(),
            postNotificationsGranted = notificationPermissionGranted(),
            notificationsEnabled = safe(true) { NotificationManagerCompat.from(context).areNotificationsEnabled() },
            ringChannelImportance = safe(null) { notifications.getNotificationChannel(Channels.RING)?.importance },
            fullScreenIntentAllowed = safe(true) { fullScreenIntentAllowed(notifications) },
            canScheduleExactAlarms = safe(true) { pending.canScheduleExact() },
            ignoringBatteryOptimizations = safe(true) {
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            },
            standbyBucket = safe(null) { standbyBucket() },
            backgroundRestricted = safe(null) { backgroundRestricted() },
            interruptionFilter = safe(NotificationManager.INTERRUPTION_FILTER_UNKNOWN) { notifications.currentInterruptionFilter },
            dndAllowsAlarms = safe(true) { dndAllowsAlarms(notifications) },
            alarmVolume = safe(1) { audio.getStreamVolume(AudioManager.STREAM_ALARM) },
            alarmVolumeMax = safe(7) { audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) },
            volumeFixed = safe(false) { audio.isVolumeFixed },
            ringerMode = safe(AudioManager.RINGER_MODE_NORMAL) { audio.ringerMode },
            headsetNames = safe(emptyList()) { audioRoutes.connectedHeadsetNames() },
            audioRoute = appSettings.audioRoute,
            hearingSafetyCap = appSettings.hearingSafetyCap,
            headphoneCapPercent = appSettings.headphoneCapPercent,
            alarmStorageDeviceProtected = storageContext.isDeviceProtectedStorage,
            autoTime = globalFlag(Settings.Global.AUTO_TIME),
            autoTimeZone = globalFlag(Settings.Global.AUTO_TIME_ZONE),
            totalAlarms = all.size,
            enabledAlarms = enabled.size,
            expectedArmed = willRing.size,
            armedWithOs = willRing.count { safe(false) { pending.isScheduled(TriggerKind.ALARM, it.id) } },
            recordedTriggers = willRing.count { it.id in recorded },
            lastRescheduleAt = runtime.lastRescheduleAt,
            lastRescheduleReason = runtime.lastRescheduleReason,
            lastRingAt = runtime.lastFireAt,
            lastWatchdogAt = runtime.lastWatchdogAt,
            lastServiceStartFailure = runtime.lastServiceStartFailure,
            appNextAlarmAt = scheduler.nextAlarm()?.at?.toInstant()?.toEpochMilli(),
            systemNextAlarmAt = safe(null) { pending.systemNextAlarmClock()?.triggerTime },
            voiceAlarms = enabled.count { it.voice.enabled },
            ttsAvailable = safe(true) { ttsEngineInstalled() },
            vibrationAlarms = enabled.count { it.vibration.enabled },
            hasVibrator = safe(true) { context.getSystemService(Vibrator::class.java).hasVibrator() },
            alarmsUsingSystemVolume = enabled.count { it.volume.level == 0 && !it.volume.useMaxSystemVolume },
        )
    }

    /** Runtime-granted only from Android 13; earlier versions grant it at install. */
    private fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            safe(false) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            }

    /** Revocable from Android 14; before that the manifest permission is all there is. */
    private fun fullScreenIntentAllowed(notifications: NotificationManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) notifications.canUseFullScreenIntent() else true

    private fun standbyBucket(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) context.getSystemService(UsageStatsManager::class.java).appStandbyBucket else null

    private fun backgroundRestricted(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) context.getSystemService(ActivityManager::class.java).isBackgroundRestricted else null

    /** "Priority only" lets alarms through unless the user unticked Alarms (a choice that exists from Android 9). */
    private fun dndAllowsAlarms(notifications: NotificationManager): Boolean = when (notifications.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_NONE -> false
        NotificationManager.INTERRUPTION_FILTER_PRIORITY ->
            Build.VERSION.SDK_INT < 28 ||
                notifications.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
        else -> true
    }

    private fun ttsEngineInstalled(): Boolean {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val services = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentServices(intent, 0)
        }
        return services.isNotEmpty()
    }

    /** A 0/1 global setting as a boolean; null when it cannot be read. */
    private fun globalFlag(name: String): Boolean? =
        safe(null) { Settings.Global.getInt(context.contentResolver, name, -1).takeIf { it >= 0 }?.let { it != 0 } }

    private inline fun <T> safe(fallback: T, block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        fallback
    }
}
