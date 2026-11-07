package com.riyadm.wakenudge.engine

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
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
import com.riyadm.wakenudge.engine.policy.ResurfaceAction
import com.riyadm.wakenudge.engine.policy.ResurfaceInput
import com.riyadm.wakenudge.engine.policy.ResurfacePolicy
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
 * Which foreground-service types the ring service asks for, best first. `systemExempted` is open to exact-alarm apps and,
 * unlike `mediaPlayback`, may start right after a restart. Neither has a time limit today. If the system refuses one the
 * next is tried in the same call.
 */
object RingServiceTypes {
    fun candidates(sdk: Int): List<Int> = buildList {
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
}

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
    @Inject lateinit var locks: RingWakeLocks
    @Inject lateinit var rescue: RingCrashGuard
    @Inject lateinit var calls: CallWatcher

    // A coroutine that fails must not take the process (and so the alarm) down with it: log it and carry on.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, t -> diagnostics.error("Service", "unexpected failure in the ring service; the alarm keeps ringing", t) },
    )
    private var observer: Job? = null
    private var ticker: Job? = null
    private var voiceJob: Job? = null
    private var voice: VoiceAnnouncer? = null
    private val resurface = ResurfacePolicy()
    private var screenReceiver: BroadcastReceiver? = null
    private var stoppedOnPurpose = false
    private var ringingStarted = false
    private var lastIntensified = false

    /** Whether the ring is quiet for an Emergency pause or a call: no sound and no vibration, but it is still on. */
    private var quiet = false

    /** Foreground types the system refused this service; one that said no is not asked again on every notification. */
    private val refusedTypes = mutableSetOf<Int>()

    /** Starts whose ring has not been looked up yet. Main thread only. While one is in flight the service must not stop. */
    private var startsInFlight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must reach startForeground() immediately, whatever else happens.
        val safeMode = runCatching { CrashRescuePolicy.safeMode(runtime.ringCrashTimes(), time.nowMillis()) }.getOrDefault(false)
        val promoted = goForeground(notifier.ringPlaceholder(safeMode))
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
        // A redelivered start may be for a ring dealt with before the process died; any start of an alarm's ring may
        // also have waited behind a dismissal of that same ring (a backup firing as the user dismissed). Both are
        // checked against the history first.
        val redelivered = flags and START_FLAG_REDELIVERY != 0
        val check = redelivered || request.eventId > 0
        startsInFlight++
        scope.launch {
            val session = try {
                if (check && !controller.stillUnhandled(request)) {
                    diagnostics.info("Service", "ring of alarm ${request.alarmId} was already handled; not ringing it again")
                    null
                } else {
                    controller.begin(request)
                }
            } finally {
                startsInFlight--
            }
            if (session == null) {
                diagnostics.warn("Service", "no session for $request")
                // Another ring may already be going on in this service: only an idle service shuts down.
                if (store.current == null) {
                    shutdown()
                } else {
                    // Queued behind the ring on screen: the placeholder posted above must not replace its notification.
                    store.current?.let { current -> guard("ring notification") { goForeground(notifier.ring(current)) } }
                    observe()
                }
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
     * must stop within seconds. Neither type used here has a limit today. If one is ever imposed the ring moves to the
     * other type in this same service, so the sound never stops. With nowhere to move to the service stops as it must, and
     * [onDestroy] rings it again from its saved state: a moment of silence beats the system killing the whole process.
     */
    override fun onTimeout(startId: Int, fgsType: Int) = handOver("foreground service time limit (type $fgsType)", fgsType)

    override fun onTimeout(startId: Int) = handOver("foreground service time limit", foregroundType)

    private fun handOver(reason: String, timedOutType: Int) {
        refusedTypes += timedOutType
        val session = store.current
        if (session == null) {
            shutdown()
            return
        }
        val moved = runCatching { goForeground(notifier.ring(session)) }.getOrDefault(false)
        diagnostics.warn("Service", "$reason; ${if (moved) "the ring continues under another service type" else "stopping, and ringing again from the saved state"}")
        if (!moved) stopSelf()
    }

    /**
     * Sound does not need the ring screen, but the user does. When the full-screen alarm cannot be expected to appear
     * by itself (notifications off, channel blocked, full-screen permission revoked) the screen is opened directly,
     * where the system allows it, and the display is lit so the heads-up notification is at least seen.
     */
    private fun openRingScreenIfItMayNotAppear(session: RingSession) {
        if (session.phase != RingPhase.RINGING || !notifier.ringScreenMayNotAppear()) return
        diagnostics.warn("Service", "the full-screen alarm may not appear on its own; opening the ring screen directly")
        locks.wakeScreen()
        runCatching { startActivity(notifier.ringScreenIntent(session.safeMode)) }
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
        calls.start { store.current?.takeIf { ringingStarted }?.let { applyQuiet(it, time.elapsedMillis()) } }
        watchScreen()
        observer = scope.launch {
            var previous: RingSession? = null
            store.session.collect { s ->
                if (s == null) {
                    // Someone else was waiting (two alarms in the same minute, a timer mid-alarm): they ring next.
                    val next = controller.takeQueued()
                    previous = null
                    if (next != null) {
                        // The ring that just ended takes its sound, vibration and voice with it: the next one starts its
                        // own (its tone, its volume, its label) instead of inheriting them.
                        stopSounds()
                        scope.launch {
                            // A queued ring whose alarm has vanished is skipped; the ones behind it still get their turn.
                            var candidate: RingRequest? = next
                            var session: RingSession? = null
                            while (candidate != null && session == null) {
                                session = controller.begin(candidate)
                                if (session == null) candidate = controller.takeQueued()
                            }
                            if (session == null) {
                                shutdown()
                            } else {
                                // The ring screen closed with the previous ring: alert again so this one is seen at once.
                                guard("ring notification") { goForeground(notifier.ring(session, alertAgain = true)) }
                                guard("ring screen") { openRingScreenIfItMayNotAppear(session) }
                            }
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
            var quarter = 0
            while (isActive) {
                delay(1_000L / RESURFACE_CHECKS_PER_SECOND)
                // The ring screen is looked for four times a second, so a screen that was left is seen within 250 ms and
                // its 1 s pull-back is not stretched by up to another second of waiting for the next tick.
                guard("ring screen") {
                    resurfaceIfAbandoned()
                    checkPullBack()
                }
                if (++quarter % RESURFACE_CHECKS_PER_SECOND != 0) continue
                guard("ring timer") {
                    controller.tick()
                    store.current?.takeIf { ringingStarted }?.let { applyQuiet(it, time.elapsedMillis()) }
                }
                guard("wake lock") { locks.onRingTick() }
            }
        }
    }

    private fun render(prev: RingSession?, s: RingSession) {
        val now = time.elapsedMillis()
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
                if (s.alarm.vibration.enabled && !quiet) {
                    vibrator.stop()
                    vibrator.start(s.alarm.vibration, s.intensified)
                }
            }
        }
        if (ringingStarted) applyQuiet(s, now)
    }

    /**
     * Sound off for a moment (a key, a mute), for an Emergency pause, or for a call; vibration stops with the last two and
     * comes back with the alarm. Looked at on every change of the session and once a second, since a call has no session
     * change of its own.
     */
    private fun applyQuiet(s: RingSession, now: Long) {
        val paused = (s.pausedAt(now) && s.pauseSilences) || runCatching { calls.keepsQuiet(s.startedAt * 31 + s.alarm.id) }.getOrDefault(false)
        guard("silencing") { audio.setSilenced(paused || s.silencedUntil > now) }
        if (paused == quiet) return
        quiet = paused
        guard("vibration") {
            if (paused) vibrator.stop() else if (s.alarm.vibration.enabled) vibrator.start(s.alarm.vibration, s.intensified)
        }
        diagnostics.info("Service", if (paused) "the alarm is quiet for an Emergency pause or a call" else "the alarm sounds again after a pause or a call")
    }

    private fun startRinging(s: RingSession) {
        lastIntensified = s.intensified
        scope.launch {
            guard("audio") { audio.start(s.soundAlarm, settings.current(), s.kind, s.intensified) }
            guard("vibration") { if (s.alarm.vibration.enabled && !quiet) vibrator.start(s.alarm.vibration, s.intensified) }
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

    /**
     * If the user left the ring screen while the alarm rings, bring it back, as [ResurfacePolicy] decides: started again
     * when the alarm asks for that and "Display over other apps" allows it, otherwise a heads-up; the screen lit again after
     * it was switched off. It does not matter how the user left (Home, Recents, a swiped card, split screen, another app),
     * or whether the screen was ever shown (a ring back from a kill or a restart).
     */
    private fun resurfaceIfAbandoned() {
        val s = store.current ?: return
        val now = time.elapsedMillis()
        val ao = s.alarm.antiOversleep
        val locked = runCatching { getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }.getOrDefault(false)
        val overlays = canDrawOverlays()
        val input = ResurfaceInput(
            now = now,
            ringing = s.phase == RingPhase.RINGING,
            returnToAlarm = ao.returnToAlarm,
            forceReturn = ao.forceReturn && !s.isTimer,
            uiInFront = store.isUiInFront,
            screenOn = runCatching { getSystemService(PowerManager::class.java)?.isInteractive != false }.getOrDefault(true),
            keyguardLocked = locked,
            canDrawOverlays = overlays,
            emergencyPaused = s.pausedAt(now),
            callActive = runCatching { calls.callActive() }.getOrDefault(false),
            systemDialogExpected = now < store.systemDialogExpectedUntil,
        )
        when (resurface.decide(input)) {
            ResurfaceAction.NONE -> Unit
            ResurfaceAction.PULL_BACK -> if (!pullBack(s)) notifier.postRing(s, alertAgain = true, fullScreen = false)
            ResurfaceAction.ALERT -> notifier.postRing(s, alertAgain = true, fullScreen = false)
            ResurfaceAction.WAKE -> {
                diagnostics.info("Service", "the screen is off while the alarm rings; lighting it again")
                locks.wakeScreen()
                notifier.postRing(s, alertAgain = true)
                if (overlays && !locked && ao.forceReturn && !s.isTimer) pullBack(s)
            }
        }
    }

    /**
     * Starts the ring screen from the background, which Android allows with "Display over other apps". Whether that really
     * brought it to the front is only known once the screen says so ([checkPullBack]); until then nothing claims it did.
     */
    private fun pullBack(s: RingSession): Boolean {
        val started = runCatching { startActivity(notifier.ringScreenIntent(s.safeMode)) }
            .onFailure { diagnostics.warn("Service", "the ring screen could not be started again: ${it.message}") }
            .isSuccess
        if (started) {
            if (pullBackAskedAt < 0) pullBackAskedAt = time.elapsedMillis()
            pullBackUnconfirmedNoted = false
        }
        return started
    }

    /**
     * After a pull-back, four times a second: once the ring screen is really in front it counts, and the diagnostics say how
     * long it took. Still not in front a few seconds later (Android may keep it behind the Overview), the diagnostics say so
     * once, and the policy keeps trying on its backoff because the screen is still away.
     */
    private fun checkPullBack() {
        val asked = pullBackAskedAt.takeIf { it >= 0 } ?: return
        val now = time.elapsedMillis()
        if (store.current == null) {
            pullBackAskedAt = -1
            return
        }
        if (store.isUiInFront) {
            pullBackAskedAt = -1
            controller.notePullBack()
            diagnostics.info("Service", "the ring screen is back in front ${now - asked} ms after it was started again (${resurface.pullBackStreak} in a row)")
        } else if (now - asked >= PULL_BACK_CONFIRM_MS && !pullBackUnconfirmedNoted) {
            pullBackUnconfirmedNoted = true
            diagnostics.warn("Service", "the ring screen was started again but is still not in front after ${now - asked} ms (${store.uiFrontState}); trying again")
        }
    }

    private var pullBackAskedAt = -1L
    private var pullBackUnconfirmedNoted = false

    private fun canDrawOverlays(): Boolean = runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)

    /**
     * Follows the power button: a screen switched off mid-ring is lit again, and fast repeated presses (SOS) are left alone.
     * Also hears the system announce the Overview, which is how a ring screen left behind it is told apart (RingScreenFront).
     */
    private fun watchScreen() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> resurface.onScreenOff(time.elapsedMillis())
                    // The Overview opened (Recents key or the swipe-up gesture); see RingScreenFront.
                    @Suppress("DEPRECATION")
                    Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> if (intent.getStringExtra(EXTRA_REASON) == REASON_RECENTS) store.noteRecentsOpened(time.elapsedMillis())
                }
            }
        }
        @Suppress("DEPRECATION")
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS) }
        if (runCatching { ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }.isSuccess) {
            screenReceiver = receiver
        }
    }

    private fun unwatchScreen() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    /** Returns whether the service really became a foreground service, under the best type the system allows it. */
    private fun goForeground(notification: android.app.Notification): Boolean {
        for (type in RingServiceTypes.candidates(Build.VERSION.SDK_INT)) {
            if (type in refusedTypes) continue
            val started = runCatching { ServiceCompat.startForeground(this, NotificationIds.RING, notification, type) }
                .onFailure {
                    refusedTypes += type
                    runtime.lastServiceStartFailure = "${System.currentTimeMillis()}: startForeground type $type ${it.javaClass.simpleName}: ${it.message}"
                    diagnostics.error("Service", "startForeground type $type was refused", it)
                }.isSuccess
            if (started) {
                foregroundType = type
                return true
            }
        }
        return false
    }

    private fun stopSounds() {
        guard("stopping the sound") {
            voiceJob?.cancel()
            voice?.shutdown()
            voice = null
            audio.stop()
            vibrator.stop()
        }
        ringingStarted = false
        lastIntensified = false
        quiet = false
    }

    private fun shutdown() {
        // A ring that just ended can coincide with a new one arriving (by default the ring timeout and the backup fall
        // due in the same second). Stopping the service then would take the new ring down with it, unrescued.
        if (startsInFlight > 0) {
            stopSounds()
            observer?.cancel()
            ticker?.cancel()
            observer = null
            ticker = null
            diagnostics.info("Service", "a ring ended while another was starting; staying up for it")
            return
        }
        stoppedOnPurpose = true
        stopSounds()
        calls.stop()
        unwatchScreen()
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
        foregroundType = 0
        scope.cancel()
        runCatching { calls.stop() }
        runCatching { unwatchScreen() }
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
            attempt = getIntExtra(EXTRA_ATTEMPT, 0),
        )
    }

    companion object {
        private const val PULL_BACK_CONFIRM_MS = 3_000L
        private const val RESURFACE_CHECKS_PER_SECOND = 4
        private const val EXTRA_REASON = "reason"
        private const val REASON_RECENTS = "recentapps"
        const val ACTION_BEGIN = "com.riyadm.wakenudge.action.RING_BEGIN"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SNOOZE_COUNT = "snooze_count"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_TIMER_ID = "timer_id"
        const val EXTRA_ATTEMPT = "attempt"

        /** The service type in use, or 0; what `dumpsys` would show, for tests. */
        @Volatile internal var foregroundType = 0
            private set

        private const val VOICE_FIRST_DELAY_MS = 2_500L
    }
}
