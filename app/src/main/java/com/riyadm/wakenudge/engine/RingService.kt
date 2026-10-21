package com.riyadm.wakenudge.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.engine.audio.AlarmAudioEngine
import com.riyadm.wakenudge.engine.audio.AlarmVibrator
import com.riyadm.wakenudge.engine.audio.VoiceAnnouncer
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.engine.notify.NotificationIds
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the sound, vibration and wake lock while an alarm is active. It is a thin effect
 * layer: decisions are made by [RingController], this class only reacts to [RingSessionStore].
 */
@AndroidEntryPoint
class RingService : Service() {
    @Inject lateinit var controller: RingController
    @Inject lateinit var store: RingSessionStore
    @Inject lateinit var audio: AlarmAudioEngine
    @Inject lateinit var vibrator: AlarmVibrator
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var diagnostics: DiagnosticsLog
    @Inject lateinit var time: TimeSource
    @Inject lateinit var runtime: RuntimeState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var ticker: Job? = null
    private var voiceJob: Job? = null
    private var voice: VoiceAnnouncer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastResurface = 0L
    private var ringingStarted = false
    private var lastIntensified = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must reach startForeground() immediately, whatever else happens.
        goForeground(notifier.ringPlaceholder())
        val request = intent?.takeIf { it.action == ACTION_BEGIN }?.toRequest()
        if (request == null) {
            shutdown()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        scope.launch {
            val session = controller.begin(request)
            if (session == null) {
                diagnostics.warn("Service", "no session for $request")
                shutdown()
                return@launch
            }
            goForeground(notifier.ring(session))
            observe()
        }
        return START_NOT_STICKY
    }

    private fun observe() {
        if (observer?.isActive == true) return
        observer = scope.launch {
            var previous: RingSession? = null
            store.session.collect { s ->
                if (s == null) {
                    shutdown()
                    return@collect
                }
                render(previous, s)
                previous = s
            }
        }
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                controller.tick()
                resurfaceIfAbandoned()
            }
        }
    }

    private fun render(prev: RingSession?, s: RingSession) {
        val now = time.nowMillis()
        val needsNotificationUpdate = prev == null || prev.phase != s.phase || prev.snoozeCount != s.snoozeCount || prev.kind != s.kind
        if (needsNotificationUpdate) notifier.postRing(s)

        if (s.phase == RingPhase.RINGING && !ringingStarted) {
            ringingStarted = true
            startRinging(s)
        }
        if (ringingStarted && s.intensified != lastIntensified) {
            lastIntensified = s.intensified
            audio.setIntensified(s.intensified)
            if (s.alarm.vibration.enabled) {
                vibrator.stop()
                vibrator.start(s.alarm.vibration, s.intensified)
            }
        }
        if (ringingStarted) audio.setSilenced(s.silencedUntil > now)
    }

    private fun startRinging(s: RingSession) {
        lastIntensified = s.intensified
        scope.launch {
            audio.start(s.alarm, settings.current(), s.kind, s.intensified)
            if (s.alarm.vibration.enabled) vibrator.start(s.alarm.vibration, s.intensified)
            if (s.alarm.voice.enabled && !s.isTimer) startVoice(s)
        }
    }

    private fun startVoice(s: RingSession) {
        voice = VoiceAnnouncer(
            context = this,
            onSpeaking = { speaking -> audio.setDucked(speaking) },
            onProblem = { diagnostics.warn("Voice", it) },
        )
        voiceJob = scope.launch {
            val appSettings = settings.current()
            val is24 = TimeText.is24Hour(this@RingService, appSettings.timeFormat)
            delay(VOICE_FIRST_DELAY_MS)
            while (isActive) {
                voice?.speak(announcementFor(s, is24))
                delay(s.alarm.voice.repeatSeconds.coerceAtLeast(10) * 1_000L)
            }
        }
    }

    private fun announcementFor(s: RingSession, is24: Boolean): String {
        val v = s.alarm.voice
        val parts = mutableListOf<String>()
        if (v.announceTime) {
            val now = time.now().toLocalTime()
            parts += getString(R.string.tts_announce_time, TimeText.spoken(now, is24))
        }
        if (v.announceLabel && s.alarm.label.isNotBlank()) parts += getString(R.string.tts_announce_label, s.alarm.label)
        return parts.joinToString(" ").ifBlank { getString(R.string.tts_announce_default) }
    }

    /** If the user wandered off while the alarm rings, put the ring screen back in front. */
    private fun resurfaceIfAbandoned() {
        val s = store.current ?: return
        if (s.phase != RingPhase.RINGING || store.isUiVisible.value) return
        val now = time.nowMillis()
        if (now - lastResurface < RESURFACE_EVERY_MS || now - s.startedAt < RESURFACE_EVERY_MS) return
        scope.launch {
            if (settings.current().keepRingScreenFront) {
                lastResurface = now
                notifier.postRing(s, alertAgain = true)
            }
        }
    }

    private fun goForeground(notification: android.app.Notification) {
        runCatching {
            ServiceCompat.startForeground(this, NotificationIds.RING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }.onFailure {
            runtime.lastServiceStartFailure = "${System.currentTimeMillis()}: startForeground ${it.javaClass.simpleName}: ${it.message}"
            diagnostics.error("Service", "startForeground failed", it)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeNudge:ring").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MAX_MS)
        }
    }

    private fun shutdown() {
        voiceJob?.cancel()
        voice?.shutdown()
        voice = null
        audio.stop()
        vibrator.stop()
        ringingStarted = false
        lastIntensified = false
        observer?.cancel()
        ticker?.cancel()
        observer = null
        ticker = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        audio.stop()
        vibrator.stop()
        voice?.shutdown()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun Intent.toRequest(): RingRequest? {
        val kind = getStringExtra(EXTRA_KIND)?.let { runCatching { RingKind.valueOf(it) }.getOrNull() } ?: return null
        val phase = getStringExtra(EXTRA_PHASE)?.let { runCatching { RingPhase.valueOf(it) }.getOrNull() } ?: RingPhase.RINGING
        return RingRequest(
            alarmId = getLongExtra(EXTRA_ALARM_ID, 0),
            eventId = getLongExtra(EXTRA_EVENT_ID, -1),
            scheduledAt = getLongExtra(EXTRA_SCHEDULED_AT, time.nowMillis()),
            kind = kind,
            snoozeCount = getIntExtra(EXTRA_SNOOZE_COUNT, 0),
            phase = phase,
            timerId = getLongExtra(EXTRA_TIMER_ID, 0),
        )
    }

    companion object {
        const val ACTION_BEGIN = "com.riyadm.wakenudge.action.RING_BEGIN"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SNOOZE_COUNT = "snooze_count"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_TIMER_ID = "timer_id"

        private const val WAKE_LOCK_MAX_MS = 40 * 60_000L
        private const val VOICE_FIRST_DELAY_MS = 2_500L
        private const val RESURFACE_EVERY_MS = 20_000L
    }
}
