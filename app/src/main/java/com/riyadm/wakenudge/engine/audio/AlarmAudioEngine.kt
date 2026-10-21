package com.riyadm.wakenudge.engine.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.AudioRoute
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.engine.RingKind
import com.riyadm.wakenudge.engine.RuntimeState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays an alarm: audio focus, alarm-stream volume (restored afterwards), software volume ramp, optional loudness
 * boost, the escalation layer, and a fallback chain so a broken custom file never results in a silent alarm.
 * Always uses USAGE_ALARM. Everything except the blocking decode runs on the main thread.
 */
@Singleton
class AlarmAudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: RuntimeState,
    private val route: AudioRouteAdvisor,
    private val diagnostics: DiagnosticsLog,
) {
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var main: MediaPlayer? = null
    private var layer: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var focus: AudioFocusRequest? = null
    private var rampJob: Job? = null
    private var fallbackTone: ToneGenerator? = null

    private var rampStartedAt = 0L
    private var config: Config? = null
    private var activeSound: ResolvedSound? = null
    private var intensified = false
    private var ducked = false
    private var silenced = false
    private var headsetRouted = false

    private class Config(val alarm: Alarm, val settings: AppSettings, val kind: RingKind)

    /** Reports hearing-safety caps and fallbacks to the UI/diagnostics. */
    var lastNote: String? = null
        private set

    val isPlaying: Boolean get() = config != null

    suspend fun start(alarm: Alarm, settings: AppSettings, kind: RingKind, startIntensified: Boolean) {
        stop()
        config = Config(alarm, settings, kind)
        intensified = startIntensified || kind == RingKind.BACKUP
        ducked = false
        silenced = false
        lastNote = null

        requestFocus()
        val resolved = SoundResolver.resolve(alarm.sound)
        val ok = playWithFallback(resolved, alarm, settings)
        if (!ok) diagnostics.error("Audio", "No playable sound at all for alarm ${alarm.id}; using emergency beeper")

        applyVolumePlan()
        rampStartedAt = SystemClock.elapsedRealtime()
        startRamp()
        if (intensified) startLayer()
    }

    fun setIntensified(on: Boolean) {
        if (config == null || intensified == on) return
        intensified = on
        applyVolumePlan()
        if (on) startLayer() else stopLayer()
        applyPlayerVolume()
    }

    fun setDucked(on: Boolean) {
        ducked = on
        applyPlayerVolume()
    }

    fun setSilenced(on: Boolean) {
        if (silenced == on) return
        silenced = on
        applyPlayerVolume()
    }

    fun stop() {
        rampJob?.cancel()
        rampJob = null
        stopLayer()
        runCatching { main?.stop() }
        runCatching { main?.release() }
        main = null
        runCatching { enhancer?.release() }
        enhancer = null
        runCatching { fallbackTone?.stopTone(); fallbackTone?.release() }
        fallbackTone = null
        abandonFocus()
        if (config != null) restoreSystemVolume()
        config = null
        activeSound = null
        intensified = false
    }

    /** Puts the alarm stream back if a previous session was killed before it could. Safe to call any time. */
    fun restoreSystemVolumeIfStale() {
        if (config == null) restoreSystemVolume()
    }

    // ---- playback ------------------------------------------------------------------------------------------

    private suspend fun playWithFallback(first: ResolvedSound, alarm: Alarm, settings: AppSettings): Boolean {
        val candidates = buildList {
            add(first)
            if (first.bundledId != SoundSettings.DEFAULT_TONE_ID) add(SoundResolver.fallback())
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { add(ResolvedSound(null, it.toString(), true)) }
        }
        for ((index, candidate) in candidates.withIndex()) {
            val player = withContext(Dispatchers.IO) { prepare(candidate, settings) }
            if (player != null) {
                main = player
                activeSound = candidate
                attachEnhancer(player)
                player.setOnCompletionListener { onTrackCompleted(alarm) }
                player.start()
                if (index > 0) {
                    lastNote = "Fell back to a built-in sound"
                    diagnostics.warn("Audio", "Primary sound failed; using fallback #$index")
                }
                return true
            }
        }
        // Last resort: the platform's own beeper still honours the alarm stream.
        runCatching {
            fallbackTone = ToneGenerator(AudioManager.STREAM_ALARM, 100).also { it.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD) }
        }
        return false
    }

    private fun prepare(sound: ResolvedSound, settings: AppSettings): MediaPlayer? {
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(attributes)
            val bundled = sound.bundledId?.let { BundledTones.byId(it) ?: BundledTones.default }
            val uri: Uri = when {
                bundled != null -> BundledTones.uri(context, bundled)
                sound.uri != null -> Uri.parse(sound.uri)
                else -> return null.also { player.release() }
            }
            player.setDataSource(context, uri)
            player.isLooping = sound.loop
            player.setOnErrorListener { _, what, extra ->
                diagnostics.error("Audio", "MediaPlayer error what=$what extra=$extra")
                scope.launch { recoverFromError() }
                true
            }
            player.prepare()
            routePlayer(player, settings)
            player.setVolume(0f, 0f)
            player
        } catch (t: Throwable) {
            diagnostics.warn("Audio", "Could not prepare ${sound.bundledId ?: sound.uri}: ${t.javaClass.simpleName} ${t.message}")
            runCatching { player.release() }
            null
        }
    }

    private fun routePlayer(player: MediaPlayer, settings: AppSettings) {
        headsetRouted = route.headsetConnected()
        if (settings.audioRoute == AudioRoute.PHONE_SPEAKER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val speaker = route.builtInSpeaker()
            if (speaker != null && player.setPreferredDevice(speaker)) headsetRouted = false
        }
    }

    private fun attachEnhancer(player: MediaPlayer) {
        enhancer = runCatching { LoudnessEnhancer(player.audioSessionId).apply { enabled = false } }.getOrNull()
    }

    private suspend fun recoverFromError() {
        val cfg = config ?: return
        if (activeSound?.bundledId == SoundSettings.DEFAULT_TONE_ID) return
        runCatching { main?.release() }
        main = null
        val ok = playWithFallback(SoundResolver.fallback(), cfg.alarm, cfg.settings)
        if (ok) {
            applyVolumePlan()
            applyPlayerVolume()
        }
    }

    private fun onTrackCompleted(alarm: Alarm) {
        val cfg = config ?: return
        val current = activeSound ?: return
        if (current.loop) return
        val next = SoundResolver.nextFromPlaylist(alarm.sound, current.uri) ?: return
        scope.launch {
            val player = withContext(Dispatchers.IO) { prepare(next, cfg.settings) }
            if (player == null || config == null) {
                player?.release()
                return@launch
            }
            runCatching { enhancer?.release() }
            runCatching { main?.release() }
            main = player
            activeSound = next
            attachEnhancer(player)
            player.setOnCompletionListener { onTrackCompleted(alarm) }
            applyVolumePlan()
            applyPlayerVolume()
            player.start()
        }
    }

    // ---- volume --------------------------------------------------------------------------------------------

    private fun applyVolumePlan() {
        val cfg = config ?: return
        val streamMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val bundledLufs = activeSound?.bundledId?.let { BundledTones.byId(it)?.lufs }
        val plan = VolumePlanner.plan(
            volume = cfg.alarm.volume,
            streamMax = streamMax,
            currentStreamIndex = audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
            headsetRoute = headsetRouted,
            hearingCapEnabled = cfg.settings.hearingSafetyCap,
            capPercent = cfg.settings.headphoneCapPercent,
            escalated = intensified,
            sourceLufs = bundledLufs,
        )
        if (plan.cappedForHearing) {
            lastNote = "Volume limited for headphones (hearing safety)"
        }
        plan.streamIndex?.let { index ->
            val current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            if (runtime.savedAlarmVolume < 0) runtime.savedAlarmVolume = current
            if (current != index.coerceAtMost(streamMax)) {
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, index.coerceAtMost(streamMax), 0) }
                    .onSuccess { diagnostics.info("Audio", "alarm volume $current -> $index of $streamMax (original ${runtime.savedAlarmVolume} saved)") }
                    .onFailure { diagnostics.warn("Audio", "setStreamVolume refused: ${it.message}") }
            }
        }
        runCatching {
            enhancer?.let { e ->
                if (plan.enhancerMillibels > 0) {
                    e.setTargetGain(plan.enhancerMillibels)
                    e.enabled = true
                } else {
                    e.enabled = false
                }
            }
        }
    }

    private fun applyPlayerVolume() {
        val cfg = config ?: return
        val v = cfg.alarm.volume
        val ramp = if (intensified) 1f else VolumePlanner.rampGain(SystemClock.elapsedRealtime() - rampStartedAt, v.rampSeconds, v.rampStartPercent)
        val duck = if (ducked) DUCK_GAIN else 1f
        val gain = if (silenced) 0f else ramp * duck
        runCatching { main?.setVolume(gain, gain) }
        // The escalation layer follows silence/duck but always plays at full level otherwise.
        val layerGain = if (silenced) 0f else duck
        runCatching { layer?.setVolume(layerGain, layerGain) }
    }

    private fun startRamp() {
        rampJob?.cancel()
        rampJob = scope.launch {
            val cfg = config ?: return@launch
            val total = cfg.alarm.volume.rampSeconds * 1000L
            while (isActive) {
                applyPlayerVolume()
                if (intensified || total <= 0 || SystemClock.elapsedRealtime() - rampStartedAt >= total) break
                delay(RAMP_TICK_MS)
            }
            applyPlayerVolume()
        }
    }

    private fun startLayer() {
        if (layer != null) return
        val cfg = config ?: return
        val tone = if (cfg.kind == RingKind.BACKUP) BundledTones.BACKUP else BundledTones.INTENSIFY
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(context, BundledTones.uri(context, tone))
            mp.isLooping = true
            mp.prepare()
            routePlayer(mp, cfg.settings)
            layer = mp
            applyPlayerVolume()
            mp.start()
        } catch (t: Throwable) {
            diagnostics.warn("Audio", "Escalation layer unavailable: ${t.message}")
            runCatching { mp.release() }
            layer = null
        }
    }

    private fun stopLayer() {
        runCatching { layer?.stop() }
        runCatching { layer?.release() }
        layer = null
    }

    private fun restoreSystemVolume() {
        val saved = runtime.savedAlarmVolume
        if (saved < 0) return
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
            .onSuccess { diagnostics.info("Audio", "alarm volume restored to $saved") }
            .onFailure { diagnostics.warn("Audio", "Could not restore alarm volume: ${it.message}") }
        runtime.savedAlarmVolume = -1
    }

    // ---- focus ---------------------------------------------------------------------------------------------

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { /* An alarm keeps ringing whatever other apps do. */ }
            .build()
        focus = request
        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Not fatal: alarms play regardless; Android 17 may deny focus silently to non-exempt callers.
            diagnostics.warn("Audio", "Audio focus not granted (result=$result); playing anyway")
        }
    }

    private fun abandonFocus() {
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
    }

    private companion object {
        const val RAMP_TICK_MS = 120L
        const val DUCK_GAIN = 0.3f
    }
}
