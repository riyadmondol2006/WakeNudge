package com.riyadm.wakenudge.engine

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
 *
 * Sound never depends on anything fragile: not on the ring screen (it can crash or be swiped away), not on the
 * notification (it can be blocked), not on the database. Every step below is guarded on its own, so one failing part
 * (a vibrator, a text-to-speech engine, a notification) cannot take the others, or the sound, with it.
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
    @Inject lateinit var starter: RingStarter
    @Inject lateinit var locks: RingWakeLocks
    @Inject lateinit var rescue: RingCrashGuard

    // A coroutine that fails must not take the process (and so the alarm) down with it: log it and carry on.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, t -> diagnostics.error("Service", "unexpected failure in the ring service; the alarm keeps ringing", t) },
    )
    private var observer: Job? = null
    private var ticker: Job? = null
    private var voiceJob: Job? = null
    private var voice: VoiceAnnouncer? = null
    private var lastResurface = 0L
    private var stoppedOnPurpose = false
    private var ringingStarted = false
    private var lastIntensified = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must reach startForeground() immediately, whatever else happens.
        val promoted = goForeground(notifier.ringPlaceholder())
        val request = intent?.takeIf { it.action == ACTION_BEGIN }?.toRequest()
        if (request == null) {
            locks.releaseHandoff()
            shutdown()
            return START_NOT_STICKY
        }
        stoppedOnPurpose = false
        // Refused (e.g. Android 15 forbids media services from a boot receiver): carry on, but line up a retry from an alarm trigger.
        if (!promoted) rescue.retrySoon(request, "startForeground was refused")
        locks.acquireRing()
        locks.releaseHandoff() // our own lock holds the CPU from here
        scope.launch {
            val session = controller.begin(request)
            if (session == null) {
                diagnostics.warn("Service", "no session for $request")
                // Another ring may already be going on in this service: only an idle service shuts down.
                if (store.current == null) shutdown() else observe()
                return@launch
            }
            guard("ring notification") { goForeground(notifier.ring(session)) }
            observe()
            guard("ring screen") { openRingScreenIfItMayNotAppear(session) }
        }
        // If the system kills the process while the alarm rings, it starts the service again with this same request.
        return START_REDELIVER_INTENT
    }

    /**
     * Android 15+ gives foreground services of some types a time limit and calls this when it is up; the service
     * must stop within seconds. A media-playback service has no limit today, but if one is ever imposed the alarm is
     * handed to a fresh service rather than silenced.
     */
    override fun onTimeout(startId: Int, fgsType: Int) = handOver("foreground service time limit (type $fgsType)")

    override fun onTimeout(startId: Int) = handOver("foreground service time limit")

    private fun handOver(reason: String) {
        val request = store.current?.takeIf { it.phase == RingPhase.RINGING }?.toRequest()
        diagnostics.warn("Service", "$reason; the ring continues in a fresh service")
        shutdown()
        request?.let { starter.start(it) }
    }

    /**
     * Sound does not need the ring screen, but the user does. When the full-screen alarm cannot be expected to appear
     * by itself (notifications off, channel blocked, full-screen permission revoked) the screen is opened directly,
     * where the system allows it, and the display is lit so the heads-up notification is at least seen.
     */
    private fun openRingScreenIfItMayNotAppear(session: RingSession) {
        if (session.phase != RingPhase.RINGING || session.safeMode || !notifier.ringScreenMayNotAppear()) return
        diagnostics.warn("Service", "the full-screen alarm may not appear on its own; opening the ring screen directly")
        locks.wakeScreen()
        runCatching { startActivity(notifier.ringScreenIntent()) }
            .onFailure { diagnostics.warn("Service", "the system did not allow opening the ring screen: ${it.message}") }
    }

    /** Runs [block] so that a failure is logged instead of reaching the rest of the ring. */
    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            diagnostics.error("Service", "$what failed; the alarm keeps ringing", t)
        }
    }

    private fun observe() {
        if (observer?.isActive == true) return
        observer = scope.launch {
            var previous: RingSession? = null
            store.session.collect { s ->
                if (s == null) {
                    // Someone else was waiting (two alarms in the same minute, a timer mid-alarm): they ring next.
                    val next = controller.takeQueued()
                    previous = null
                    if (next != null) {
                        scope.launch {
                            // A queued ring whose alarm has vanished is skipped; the ones behind it still get their turn.
                            var candidate: RingRequest? = next
                            var session: RingSession? = null
                            while (candidate != null && session == null) {
                                session = controller.begin(candidate)
                                if (session == null) candidate = controller.takeQueued()
                            }
                            if (session == null) shutdown() else guard("ring notification") { goForeground(notifier.ring(session)) }
                        }
                    } else {
                        shutdown()
                    }
                    return@collect
                }
                render(previous, s)
                previous = s
            }
        }
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                guard("ring timer") {
                    controller.tick()
                    resurfaceIfAbandoned()
                }
            }
        }
    }

    private fun render(prev: RingSession?, s: RingSession) {
        val now = time.nowMillis()
        // A different alarm replacing the previous one (the conflated "null" in between may never be seen).
        if (prev != null && (prev.alarm.id != s.alarm.id || prev.eventId != s.eventId)) {
            guard("stopping the previous alarm") {
                audio.stop(); vibrator.stop(); voiceJob?.cancel(); voice?.shutdown(); voice = null
            }
            ringingStarted = false
            lastIntensified = false
            guard("ring notification") { notifier.postRing(s) }
        }
        val needsNotificationUpdate = prev == null || prev.phase != s.phase || prev.snoozeCount != s.snoozeCount || prev.kind != s.kind
        if (needsNotificationUpdate) guard("ring notification") { notifier.postRing(s) }

        // Sound first: nothing about the notification or the vibrator may delay or prevent it.
        if (s.phase == RingPhase.RINGING && !ringingStarted) {
            ringingStarted = true
            startRinging(s)
        }
        if (ringingStarted && s.intensified != lastIntensified) {
            lastIntensified = s.intensified
            guard("escalation") {
                audio.setIntensified(s.intensified)
                if (s.alarm.vibration.enabled) {
                    vibrator.stop()
                    vibrator.start(s.alarm.vibration, s.intensified)
                }
            }
        }
        if (ringingStarted) guard("silencing") { audio.setSilenced(s.silencedUntil > now) }
    }

    private fun startRinging(s: RingSession) {
        lastIntensified = s.intensified
        scope.launch {
            guard("audio") { audio.start(s.alarm, settings.current(), s.kind, s.intensified) }
            guard("vibration") { if (s.alarm.vibration.enabled) vibrator.start(s.alarm.vibration, s.intensified) }
            guard("voice announcement") { if (s.alarm.voice.enabled && !s.isTimer) startVoice(s) }
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

    /** Returns whether the service really became a foreground service. */
    private fun goForeground(notification: android.app.Notification): Boolean = runCatching {
        ServiceCompat.startForeground(this, NotificationIds.RING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }.onFailure {
        runtime.lastServiceStartFailure = "${System.currentTimeMillis()}: startForeground ${it.javaClass.simpleName}: ${it.message}"
        diagnostics.error("Service", "startForeground failed", it)
    }.isSuccess

    private fun shutdown() {
        stoppedOnPurpose = true
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
        locks.releaseAll()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        val interrupted = !stoppedOnPurpose
        val session = store.current
        scope.cancel()
        audio.stop()
        vibrator.stop()
        voice?.shutdown()
        locks.releaseAll()
        // Destroyed although the alarm is still on (not by our own shutdown): bring the ring back instead of going quiet,
        // and drop the orphaned session, or every later alarm would queue behind a ring that no longer exists.
        if (interrupted && session != null) {
            rescue.rescue(session, "ring service destroyed while ringing")
            store.set(null)
            runtime.activeAlarmId = -1
            runtime.activeEventId = -1
        }
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

        private const val VOICE_FIRST_DELAY_MS = 2_500L
        private const val RESURFACE_EVERY_MS = 20_000L
    }
}
