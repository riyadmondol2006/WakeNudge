package com.riyadm.wakenudge.engine.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays an alarm: audio focus, alarm-stream volume (restored afterwards), software volume ramp, optional loudness
 * boost, the escalation layer, and a fallback chain so a broken custom file never results in a silent alarm.
 * Always uses USAGE_ALARM. Everything except the blocking decode runs on the main thread.
 *
 * Once started, the engine looks after itself: a heartbeat resumes a player that something paused, a dead or erroring
 * player is rebuilt from the bundled tone and, if even that fails, replaced by an emergency beeper. Other apps taking
 * audio focus, headphones being plugged or pulled and a sound that is slow to open never leave the alarm silent.
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
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var main: MediaPlayer? = null
    private var layer: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var focus: AudioFocusRequest? = null
    private var rampJob: Job? = null
    private var watchJob: Job? = null
    private var beeperJob: Job? = null
    private var fallbackTone: ToneGenerator? = null
    private var deviceCallback: AudioDeviceCallback? = null

    private var rampStartedAt = 0L
    private var config: Config? = null
    private var activeSound: ResolvedSound? = null
    private var intensified = false
    private var ducked = false
    private var silenced = false
    private var headsetRouted = false
    private var headsetConnectedNow = false

    /** Bumped by every [start] and [stop]; work that finishes after its ring ended sees a stale value and backs off. */
    private var generation = 0
    private var switching = false
    private var duckedAt = 0L

    /**
     * Longest the alarm stays lowered for an announcement. A text-to-speech engine that dies mid-sentence never reports
     * that it finished, and the alarm would otherwise stay at a third of its volume for the rest of the ring.
     */
    @Volatile internal var maxDuckMs = 15_000L
    private var layerFailures = 0
    private var lastBeeperRetry = 0L
    private val recoveries = RecoveryBudget()

    /** The alarm-stream index each route had before the engine touched it (true = headset), for an exact restore. */
    private val originalIndexByRoute = HashMap<Boolean, Int>()

    private class Config(val alarm: Alarm, val settings: AppSettings, val kind: RingKind)

    private enum class PlayResult { PLAYING, FAILED, ABORTED }

    /** Reports hearing-safety caps and fallbacks to the UI/diagnostics. */
    var lastNote: String? = null
        private set

    val isPlaying: Boolean get() = config != null

    /** Whether something is really making noise right now: a playing sound or the emergency beeper. */
    val isAudible: Boolean
        get() = runCatching { main?.isPlaying == true }.getOrDefault(false) || beeperJob?.isActive == true

    /** Test seam: runs on the loader thread before a sound is opened, so a test can make loading slow or fail. */
    @Volatile internal var prepareHook: ((ResolvedSound) -> Unit)? = null

    /** Test seam: makes the "random" sound choices repeatable. */
    @Volatile internal var random: Random = Random.Default

    internal val isDucked: Boolean get() = ducked

    /** Which sound is playing (bundled id or uri), for tests and diagnostics. */
    internal val activeSoundId: String? get() = activeSound?.let { it.bundledId ?: it.uri }

    suspend fun start(alarm: Alarm, settings: AppSettings, kind: RingKind, startIntensified: Boolean) {
        stop()
        val gen = ++generation
        config = Config(alarm, settings, kind)
        intensified = startIntensified || kind == RingKind.BACKUP
        ducked = false
        silenced = false
        lastNote = null
        switching = false
        layerFailures = 0
        recoveries.reset()
        headsetConnectedNow = safely("route probe", false) { route.headsetConnected() }

        try {
            requestFocus()
            watchRoute()
            val resolved = SoundResolver.resolve(alarm.sound, random)
            val result = playWithFallback(startCandidates(resolved), gen, reason = null, fallbackFrom = 1)
            if (gen != generation) return // the ring ended while the sound was still loading
            if (result == PlayResult.FAILED) {
                diagnostics.error("Audio", "No playable sound at all for alarm ${alarm.id}; using the emergency beeper")
                startBeeper("no playable sound")
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            diagnostics.error("Audio", "Starting playback failed; using the emergency beeper", t)
            if (gen == generation) startBeeper("playback could not start: ${t.javaClass.simpleName}")
        }
        if (gen != generation) return

        safely("volume plan", Unit) { applyVolumePlan() }
        rampStartedAt = SystemClock.elapsedRealtime()
        startRamp(gen)
        startWatch(gen)
        if (intensified) safely("escalation layer", Unit) { startLayer() }
    }

    fun setIntensified(on: Boolean) {
        if (config == null || intensified == on) return
        intensified = on
        safely("volume plan", Unit) { applyVolumePlan() }
        if (on) safely("escalation layer", Unit) { startLayer() } else stopLayer()
        applyPlayerVolume()
    }

    /** Lowers the alarm while a voice announcement speaks. The duck is time-limited, see [maxDuckMs]. */
    fun setDucked(on: Boolean) {
        ducked = on
        duckedAt = if (on) SystemClock.elapsedRealtime() else 0L
        applyPlayerVolume()
    }

    fun setSilenced(on: Boolean) {
        if (silenced == on) return
        silenced = on
        applyPlayerVolume()
    }

    fun stop() {
        generation++
        rampJob?.cancel()
        rampJob = null
        watchJob?.cancel()
        watchJob = null
        stopBeeper()
        unwatchRoute()
        stopLayer()
        runCatching { main?.stop() }
        runCatching { main?.release() }
        main = null
        runCatching { enhancer?.release() }
        enhancer = null
        abandonFocus()
        if (config != null) restoreSystemVolume()
        originalIndexByRoute.clear()
        config = null
        activeSound = null
        intensified = false
        switching = false
    }

    /** Puts the alarm stream back if a previous session was killed before it could. Safe to call any time. */
    fun restoreSystemVolumeIfStale() {
        if (config == null) restoreSystemVolume()
    }

    // ---- playback ------------------------------------------------------------------------------------------

    private fun startCandidates(first: ResolvedSound): List<ResolvedSound> = buildList {
        add(first)
        if (first.bundledId != SoundSettings.DEFAULT_TONE_ID) add(SoundResolver.fallback())
        systemAlarmSound()?.let(::add)
    }

    private fun fallbackCandidates(): List<ResolvedSound> = buildList {
        add(SoundResolver.fallback())
        systemAlarmSound()?.let(::add)
    }

    private fun systemAlarmSound(): ResolvedSound? =
        runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) }.getOrNull()?.let { ResolvedSound(null, it.toString(), true) }

    /**
     * Tries each candidate in turn and keeps the first that really starts. Returns [PlayResult.ABORTED] when the ring
     * ended while a sound was loading, so a late arrival never starts playing for an alarm that is already over.
     */
    private suspend fun playWithFallback(candidates: List<ResolvedSound>, gen: Int, reason: String?, fallbackFrom: Int): PlayResult {
        val cfg = config ?: return PlayResult.ABORTED
        for ((index, candidate) in candidates.withIndex()) {
            val player = loadPlayer(candidate, cfg.settings)
            if (gen != generation) {
                player?.let { runCatching { it.release() } }
                return PlayResult.ABORTED
            }
            if (player == null) continue
            player.setOnCompletionListener { onTrackCompleted(gen, it) }
            player.setOnErrorListener { p, what, extra ->
                onPlayerError(gen, p, what, extra)
                true
            }
            val started = runCatching { player.start() }
                .onFailure { diagnostics.warn("Audio", "Could not start ${candidate.bundledId ?: candidate.uri}: ${it.message}") }
                .isSuccess
            if (!started) {
                runCatching { player.release() }
                continue
            }
            val previous = main
            main = player
            activeSound = candidate
            runCatching { enhancer?.release() }
            attachEnhancer(player)
            previous?.let { runCatching { it.release() } }
            // Candidates from [fallbackFrom] on are the built-in tones; getting that far means the chosen sound failed.
            if (index >= fallbackFrom || reason != null) {
                lastNote = "Fell back to a built-in sound"
                noteProblem("fell back to a built-in sound" + (reason?.let { " ($it)" } ?: ", the chosen one could not be played"))
            }
            return PlayResult.PLAYING
        }
        return PlayResult.FAILED
    }

    /**
     * Opens a sound off the main thread, giving up after [PREPARE_TIMEOUT_MS]: a song on a cloud drive or a stalled
     * storage card can block for a very long time, and an alarm must start within seconds.
     */
    private suspend fun loadPlayer(sound: ResolvedSound, settings: AppSettings): MediaPlayer? {
        val loading = ioScope.async { prepare(sound, settings) }
        val player = withTimeoutOrNull(PREPARE_TIMEOUT_MS) { loading.await() }
        if (player == null) {
            if (!loading.isCompleted) diagnostics.warn("Audio", "Opening ${sound.bundledId ?: sound.uri} took longer than ${PREPARE_TIMEOUT_MS / 1000}s; moving on")
            // Whatever the abandoned load eventually produces is released instead of leaking.
            loading.invokeOnCompletion { runCatching { loading.getCompleted()?.release() } }
        }
        return player
    }

    private fun prepare(sound: ResolvedSound, settings: AppSettings): MediaPlayer? {
        val player = MediaPlayer()
        return try {
            prepareHook?.invoke(sound)
            player.setAudioAttributes(attributes)
            val bundled = sound.bundledId?.let { BundledTones.byId(it) ?: BundledTones.default }
            val uri: Uri = when {
                bundled != null -> BundledTones.uri(context, bundled)
                sound.uri != null -> Uri.parse(sound.uri)
                else -> return null.also { player.release() }
            }
            player.setDataSource(context, uri)
            player.isLooping = sound.loop
            player.prepare()
            routePlayer(player, settings, headsetConnectedNow)
            player.setVolume(0f, 0f)
            player
        } catch (t: Throwable) {
            diagnostics.warn("Audio", "Could not prepare ${sound.bundledId ?: sound.uri}: ${t.javaClass.simpleName} ${t.message}")
            runCatching { player.release() }
            null
        }
    }

    /**
     * Pins the player to the phone speaker when the user asked for that, and decides whether a headset is "really
     * the route": only then does the hearing-safety cap apply. A headset that merely exists is not enough.
     */
    private fun routePlayer(player: MediaPlayer, settings: AppSettings, headsetConnected: Boolean) {
        headsetRouted = headsetConnected
        if (settings.audioRoute == AudioRoute.PHONE_SPEAKER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val speaker = safely("speaker lookup", null) { route.builtInSpeaker() }
            if (speaker != null && runCatching { player.setPreferredDevice(speaker) }.getOrDefault(false)) headsetRouted = false
        }
    }

    private fun attachEnhancer(player: MediaPlayer) {
        enhancer = runCatching { LoudnessEnhancer(player.audioSessionId).apply { enabled = false } }.getOrNull()
    }

    /** The player reported an error: whatever it was, the alarm must go on with something that works. */
    internal fun onPlayerError(gen: Int, player: MediaPlayer?, what: Int, extra: Int) {
        diagnostics.error("Audio", "MediaPlayer error what=$what extra=$extra")
        if (gen != generation || (player != null && player !== main)) return
        scope.launch { recover("MediaPlayer error $what/$extra") }
    }

    /** Test seam: behaves as if the current player had reported an error. */
    internal fun simulatePlayerError(what: Int = MediaPlayer.MEDIA_ERROR_UNKNOWN, extra: Int = 0) =
        onPlayerError(generation, main, what, extra)

    /** Test seam: behaves as if the current sound had reached its end. */
    internal fun simulateTrackCompleted() = onTrackCompleted(generation, main)

    /** Test seam: pauses the player the way another app or the platform might. */
    internal fun pauseForTest() {
        runCatching { main?.pause() }
    }

    /** Test seam: destroys the player the way a crashed media server would, leaving a dead object behind. */
    internal fun killPlayerForTest() {
        runCatching { main?.release() }
    }

    /**
     * Rebuilds playback from the bundled tone, then the system alarm tone, then the emergency beeper. A recovery that
     * keeps failing quickly goes straight to the beeper, so a player that dies on start cannot cause a rebuild storm.
     */
    private suspend fun recover(reason: String) {
        config ?: return
        if (switching) return
        val gen = generation
        switching = true
        try {
            val withinBudget = recoveries.tryRecover(SystemClock.elapsedRealtime())
            diagnostics.warn("Audio", "Recovering playback: $reason")
            val result = if (withinBudget) {
                runCatching { main?.release() }
                main = null
                runCatching { enhancer?.release() }
                enhancer = null
                playWithFallback(fallbackCandidates(), gen, reason, fallbackFrom = 0)
            } else {
                PlayResult.FAILED
            }
            when {
                gen != generation -> return
                result == PlayResult.FAILED -> startBeeper(reason)
                else -> {
                    stopBeeper()
                    safely("volume plan", Unit) { applyVolumePlan() }
                    applyPlayerVolume()
                }
            }
        } finally {
            switching = false
        }
    }

    /** A sound reached its end: loop it again, or move through the playlist skipping whatever cannot be opened. */
    private fun onTrackCompleted(gen: Int, finished: MediaPlayer?) {
        if (gen != generation || (finished != null && finished !== main)) return
        val cfg = config ?: return
        scope.launch {
            if (gen != generation || switching) return@launch
            val current = activeSound
            if (current == null || current.loop) {
                // A looping sound should never "complete"; if the platform says it did, start it again rather than go quiet.
                ensureAudible("sound ended")
                return@launch
            }
            switching = true
            try {
                val tracks = SoundResolver.playlistOrder(cfg.alarm.sound, current.uri, random)
                val result = playWithFallback(tracks + fallbackCandidates(), gen, reason = null, fallbackFrom = tracks.size)
                when {
                    gen != generation -> return@launch
                    result == PlayResult.FAILED -> startBeeper("playlist exhausted")
                    else -> {
                        safely("volume plan", Unit) { applyVolumePlan() }
                        applyPlayerVolume()
                    }
                }
            } finally {
                switching = false
            }
        }
    }

    /**
     * Called every second and when focus changes: if the player is not playing although the ring is on (paused by
     * another app, interrupted, dead) it is resumed, or rebuilt when resuming does not work.
     */
    private suspend fun ensureAudible(reason: String) {
        config ?: return
        if (switching) return
        val player = main
        if (player == null) {
            if (beeperJob?.isActive == true) {
                // The beeper is a stop-gap; try to get real sound back now and then.
                val now = SystemClock.elapsedRealtime()
                if (now - lastBeeperRetry >= BEEPER_RETRY_MS) {
                    lastBeeperRetry = now
                    recover("retrying real playback after the emergency beeper")
                }
            } else {
                recover("no player ($reason)")
            }
            return
        }
        if (runCatching { player.isPlaying }.getOrDefault(false)) return
        val resumed = runCatching { player.start(); player.isPlaying }.getOrDefault(false)
        if (resumed) {
            diagnostics.warn("Audio", "Playback had stopped ($reason); resumed it")
            return
        }
        recover("player stopped and would not resume ($reason)")
    }

    private fun startWatch(gen: Int) {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive && gen == generation) {
                delay(WATCH_TICK_MS)
                safely("heartbeat", Unit) {
                    if (ducked && SystemClock.elapsedRealtime() - duckedAt > maxDuckMs) {
                        diagnostics.warn("Audio", "announcement never finished; restoring the alarm volume")
                        ducked = false
                    }
                    ensureAudible("heartbeat")
                    if (intensified && layer == null && layerFailures < MAX_LAYER_FAILURES) startLayer()
                    applyPlayerVolume()
                }
            }
        }
    }

    // ---- emergency beeper ----------------------------------------------------------------------------------

    /** The platform's own beeper on the alarm stream: crude, but it needs no files and no MediaPlayer. */
    private fun startBeeper(reason: String) {
        if (beeperJob?.isActive == true) return
        noteProblem("emergency beeper in use ($reason)")
        lastBeeperRetry = SystemClock.elapsedRealtime()
        beeperJob = scope.launch {
            while (isActive) {
                val tone = fallbackTone ?: runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull().also { fallbackTone = it }
                runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900) }
                delay(BEEP_PERIOD_MS)
            }
        }
    }

    private fun stopBeeper() {
        beeperJob?.cancel()
        beeperJob = null
        runCatching { fallbackTone?.stopTone(); fallbackTone?.release() }
        fallbackTone = null
    }

    // ---- audio route ---------------------------------------------------------------------------------------

    private fun watchRoute() {
        if (deviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = routeEvent("device added")
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = routeEvent("device removed")
        }
        if (runCatching { audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }.isSuccess) {
            deviceCallback = callback
        }
    }

    private fun unwatchRoute() {
        deviceCallback?.let { runCatching { audioManager.unregisterAudioDeviceCallback(it) } }
        deviceCallback = null
    }

    private fun routeEvent(reason: String) {
        refreshRoute(safely("route probe", headsetConnectedNow) { route.headsetConnected() }, reason)
    }

    /**
     * Headphones plugged in or pulled out mid-ring. The ring never stops for it (an alarm must not behave like a music
     * player and pause when a cable comes out); what changes is the volume: the alarm stream keeps one volume per
     * output, so the plan is applied again for the new route, with the hearing cap only while a headset is the route.
     */
    internal fun refreshRoute(headsetConnected: Boolean, reason: String) {
        val cfg = config ?: return
        val before = headsetRouted
        headsetConnectedNow = headsetConnected
        headsetRouted = headsetConnected
        main?.let { routePlayer(it, cfg.settings, headsetConnected) }
        layer?.let { routePlayer(it, cfg.settings, headsetConnected) }
        diagnostics.info("Audio", "Audio route changed ($reason): headset is the route=$headsetRouted (was $before)")
        safely("volume plan", Unit) { applyVolumePlan() }
        applyPlayerVolume()
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
            originalIndexByRoute.getOrPut(headsetRouted) { current }
            val wanted = index.coerceAtMost(streamMax)
            if (current != wanted) {
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, wanted, 0) }
                    .onSuccess {
                        diagnostics.info("Audio", "alarm volume $current -> $wanted of $streamMax (original ${runtime.savedAlarmVolume} saved)")
                        val after = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                        if (after == 0) noteProblem("the alarm volume is stuck at 0 and could not be raised")
                    }
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

    private fun startRamp(gen: Int) {
        rampJob?.cancel()
        rampJob = scope.launch {
            val cfg = config ?: return@launch
            val total = cfg.alarm.volume.rampSeconds * 1000L
            while (isActive && gen == generation) {
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
        val gen = generation
        val tone = if (cfg.kind == RingKind.BACKUP) BundledTones.BACKUP else BundledTones.INTENSIFY
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(context, BundledTones.uri(context, tone))
            mp.isLooping = true
            mp.setOnErrorListener { p, what, extra ->
                diagnostics.error("Audio", "Escalation layer error what=$what extra=$extra")
                if (gen == generation && p === layer) {
                    stopLayer()
                    layerFailures++
                }
                true
            }
            mp.prepare()
            routePlayer(mp, cfg.settings, headsetConnectedNow)
            layer = mp
            applyPlayerVolume()
            mp.start()
        } catch (t: Throwable) {
            diagnostics.warn("Audio", "Escalation layer unavailable: ${t.message}")
            layerFailures++
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
        val saved = originalIndexByRoute[headsetRouted] ?: runtime.savedAlarmVolume
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
            .setOnAudioFocusChangeListener { change ->
                // An alarm keeps ringing whatever other apps do (a call, an assistant, a music app). It never fights
                // for focus back, it only makes sure nothing quietly paused it.
                diagnostics.info("Audio", "audio focus changed ($change); the alarm keeps ringing")
                scope.launch { ensureAudible("focus change $change") }
            }
            .build()
        focus = request
        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Not fatal: alarms play regardless; Android 17 may deny focus silently to non-exempt callers.
            diagnostics.warn("Audio", "Audio focus not granted (result=$result); playing anyway")
        }
    }

    private fun abandonFocus() {
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focus = null
    }

    // ---- bookkeeping ---------------------------------------------------------------------------------------

    /** Records that playback needed a fallback, for Alarm Health ("your last alarm used the emergency beeper"). */
    private fun noteProblem(message: String) {
        diagnostics.warn("Audio", message)
        runtime.lastAudioProblem = "${System.currentTimeMillis()}|$message"
    }

    private inline fun <T> safely(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        diagnostics.warn("Audio", "$what failed: ${t.javaClass.simpleName} ${t.message}")
        fallback
    }

    private companion object {
        const val RAMP_TICK_MS = 120L
        const val DUCK_GAIN = 0.3f
        const val WATCH_TICK_MS = 1_000L
        const val PREPARE_TIMEOUT_MS = 3_000L
        const val BEEP_PERIOD_MS = 1_200L
        const val BEEPER_RETRY_MS = 15_000L
        const val MAX_LAYER_FAILURES = 3
    }
}
