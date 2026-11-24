package com.riyadm.wakenudge.engine.sleeptrack

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.riyadm.wakenudge.MainActivity
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.sleep.SessionEnd
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Notification channel and ids of sleep tracking, apart from the alarm engine's (which use 1001-1005 and 20000+). */
object SleepTrackIds {
    const val CHANNEL = "sleep_tracking_v1"
    const val NOTIFICATION = 7_001
    const val REQUEST_STOP = 7_101
    const val REQUEST_OPEN = 7_102
    const val REQUEST_CLEANUP = 7_103
}

/**
 * The foreground service that keeps sleep tracking alive with the screen off. Its own class, channel and ids; it never
 * touches the ring service, the alarm stream or the ring controller.
 *
 * Types: `health` on Android 14+ (movement from the accelerometer; Android requires the physical-activity permission for
 * it, which the screen asks for before starting) plus `microphone` only while "Record snoring" is on. On Android 11 to 13
 * only `microphone` applies, and below that none. It is started from the user's tap while the app is in front. If Android
 * refuses it, the screen says so and nothing crashes.
 */
@AndroidEntryPoint
class SleepTrackService : Service(), TrackHost {
    @Inject lateinit var tracker: SleepTracker

    private var withMic = false
    private var stopping = false
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                tracker.stop(SessionEnd.USER)
                if (!tracker.isActive) stopHost()
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            // Restarted by the system without a request: a session that died with its process is closed when next seen.
            else -> {
                stopHost()
                return START_NOT_STICKY
            }
        }
        if (tracker.isActive) return START_NOT_STICKY
        val wantMic = intent.getBooleanExtra(EXTRA_MIC, false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ensureChannel(this)
        startedAt = System.currentTimeMillis()
        withMic = wantMic && promote(mic = true)
        val promoted = withMic || promote(mic = false)
        if (!promoted) {
            tracker.report(TrackNotice.REFUSED)
            stopHost()
            return START_NOT_STICKY
        }
        if (wantMic && !withMic) tracker.report(TrackNotice.MIC_UNAVAILABLE)
        if (!tracker.start(this, withMic)) stopHost()
        return START_NOT_STICKY
    }

    private fun promote(mic: Boolean): Boolean {
        val notification = notification(micOn = mic, micStoppedForAlarm = false, startedAt = startedAt)
        return runCatching { ServiceCompat.startForeground(this, SleepTrackIds.NOTIFICATION, notification, types(mic)) }
            .onFailure { Log.w(TAG, "startForeground refused (mic=$mic)", it) }
            .isSuccess
    }

    override fun dropMicrophone() {
        if (!withMic) return
        withMic = false
        // Keep running as a health service only: the microphone type ends with the microphone.
        runCatching {
            ServiceCompat.startForeground(this, SleepTrackIds.NOTIFICATION, notification(micOn = false, micStoppedForAlarm = false, startedAt = startedAt), types(mic = false))
        }.onFailure { Log.w(TAG, "could not drop the microphone type", it) }
    }

    override fun showStatus(status: TrackStatus.Running) {
        startedAt = status.startedAt
        val n = notification(status.micOn, status.micStoppedForAlarm, status.startedAt)
        runCatching { getSystemService(NotificationManager::class.java)?.notify(SleepTrackIds.NOTIFICATION, n) }
    }

    override fun stopHost() {
        if (stopping) return
        stopping = true
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** Android 15+ time limits do not apply to these types today; if one ever does, the night ends cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        tracker.stop(SessionEnd.STOPPED_BY_SYSTEM)
        stopHost()
    }

    @Deprecated("Android 14 only")
    override fun onTimeout(startId: Int) {
        tracker.stop(SessionEnd.STOPPED_BY_SYSTEM)
        stopHost()
    }

    override fun onDestroy() {
        // Stopped from outside (the system, the task manager): end the night with what was recorded.
        if (!stopping) tracker.stop(SessionEnd.STOPPED_BY_SYSTEM)
        super.onDestroy()
    }

    private fun notification(micOn: Boolean, micStoppedForAlarm: Boolean, startedAt: Long): Notification {
        val open = PendingIntent.getActivity(
            this, SleepTrackIds.REQUEST_OPEN,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_SLEEP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, SleepTrackIds.REQUEST_STOP, Intent(this, SleepTrackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = getString(
            when {
                micOn -> R.string.sleeptrack_notif_mic_on
                micStoppedForAlarm -> R.string.sleeptrack_notif_mic_stopped
                else -> R.string.sleeptrack_notif_movement
            },
        )
        return NotificationCompat.Builder(this, SleepTrackIds.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sleeptrack)
            .setContentTitle(getString(R.string.sleeptrack_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, getString(R.string.sleeptrack_im_up), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "SleepTrackService"
        const val ACTION_START = "com.riyadm.wakenudge.sleeptrack.START"
        const val ACTION_STOP = "com.riyadm.wakenudge.sleeptrack.STOP"
        const val EXTRA_MIC = "mic"

        /** The foreground-service types for this Android version. The constants are plain ints, only used where they exist. */
        @SuppressLint("InlinedApi")
        fun types(mic: Boolean, sdk: Int = Build.VERSION.SDK_INT): Int {
            var t = 0
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            if (mic && sdk >= Build.VERSION_CODES.R) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            return t
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(SleepTrackIds.CHANNEL, context.getString(R.string.sleeptrack_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.sleeptrack_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            runCatching { nm.createNotificationChannel(channel) }
        }

        /**
         * Starts tracking from the user's tap. Returns false when Android refuses to start the service at all (the screen
         * then says so); a refusal of the foreground promotion itself arrives later as [TrackNotice.REFUSED].
         */
        fun start(context: Context, withMic: Boolean): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context, Intent(context, SleepTrackService::class.java).setAction(ACTION_START).putExtra(EXTRA_MIC, withMic),
            )
        }.onFailure { Log.w(TAG, "could not start sleep tracking", it) }.isSuccess

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, SleepTrackService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
