package com.riyadm.wakenudge.engine.sleeptrack

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.data.sleep.ClipFiles
import com.riyadm.wakenudge.data.sleep.SleepRepository
import com.riyadm.wakenudge.data.sleep.SnoreClipEntity
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.sleep.ClipDecision
import com.riyadm.wakenudge.domain.sleep.ClipEnvelope
import com.riyadm.wakenudge.domain.sleep.ClipPolicy
import com.riyadm.wakenudge.domain.sleep.ClipRef
import com.riyadm.wakenudge.domain.sleep.EpochAccumulator
import com.riyadm.wakenudge.domain.sleep.RingWatchPolicy
import com.riyadm.wakenudge.domain.sleep.SessionEnd
import com.riyadm.wakenudge.domain.sleep.SessionRules
import com.riyadm.wakenudge.domain.sleep.SleepLimits
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingSession
import com.riyadm.wakenudge.engine.RingSessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the screens show about tracking. */
sealed interface TrackStatus {
    data object Idle : TrackStatus
    data class Running(
        /** Wall clock, for the elapsed time on screen. */
        val startedAt: Long,
        val micOn: Boolean,
        /** An alarm rang and the microphone was switched off for the rest of the night. */
        val micStoppedForAlarm: Boolean,
    ) : TrackStatus
}

/** Something the screen should tell the user once. */
enum class TrackNotice {
    /** Android refused to run the tracking service. */
    REFUSED,
    /** The microphone could not be used; tracking runs with movement only. */
    MIC_UNAVAILABLE,
    /** This phone has no accelerometer, or it refused to start. */
    NO_SENSOR,
    /** The session reached 16 hours and ended itself. */
    TIME_LIMIT,
}

/** What the tracker needs from its foreground service. */
interface TrackHost {
    /** Keep running as a foreground service without the microphone type. */
    fun dropMicrophone()
    fun showStatus(status: TrackStatus.Running)
    fun stopHost()
}

/**
 * Runs one sleep session: movement (and sound, when asked) into epochs, epochs into `sleep.db`, snore clips kept by
 * [ClipPolicy], and the end of the session.
 *
 * Alarms come first. The tracker only *reads* [RingSessionStore.session]: it never calls the ring controller, never
 * takes its lock, never touches the alarm stream or audio focus. When a ring starts the microphone stops at once and
 * stays off; when an alarm that rang during the session is dismissed the session ends. A snooze keeps tracking.
 */
@Singleton
class SleepTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SleepRepository,
    private val files: ClipFiles,
    private val ringStore: RingSessionStore,
    private val history: HistoryRepository,
    private val time: TimeSource,
    private val cleanup: SleepCleanup,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "sleep tracking", e) })

    /** Database writes happen one at a time and in order. */
    private val io = Dispatchers.IO.limitedParallelism(1)

    private val _status = MutableStateFlow<TrackStatus>(TrackStatus.Idle)
    val status: StateFlow<TrackStatus> = _status.asStateFlow()

    private val _notice = MutableStateFlow<TrackNotice?>(null)
    val notice: StateFlow<TrackNotice?> = _notice.asStateFlow()

    fun consumeNotice() {
        _notice.value = null
    }

    fun report(notice: TrackNotice) {
        _notice.value = notice
    }

    private var session: Running? = null

    private inner class Running(
        val host: TrackHost,
        val startElapsed: Long,
        val startedAt: Long,
        val accumulator: EpochAccumulator,
        val movement: MovementSensor,
        val sound: SoundMonitor?,
        val wakeLock: PowerManager.WakeLock?,
    ) {
        @Volatile var sessionId: Long? = null
        var startJob: Job? = null
        val jobs = mutableListOf<Job>()
        var seenRing: RingWatchPolicy.SeenRing? = null
        var ringAtStart: Long? = null
        var micStoppedForAlarm = false
        var ending = false
        val clips = mutableListOf<ClipRef>()
    }

    val isActive: Boolean get() = synchronized(this) { session != null }

    /**
     * Starts a session. Called by the service once it is in the foreground ([withMic] says whether it got the microphone
     * type). Returns false when the movement sensor cannot run; the service then stops.
     */
    fun start(host: TrackHost, withMic: Boolean): Boolean = synchronized(this) {
        if (session != null) return true
        val startElapsed = SystemClock.elapsedRealtime()
        val accumulator = EpochAccumulator(startElapsed)
        val movement = MovementSensor(context, accumulator)
        if (!movement.start()) {
            report(TrackNotice.NO_SENSOR)
            return false
        }
        val ringingNow = ringStore.current?.phase == RingPhase.RINGING
        var sound: SoundMonitor? = null
        if (withMic && !ringingNow) {
            val monitor = SoundMonitor(context, accumulator) { candidate -> onClip(candidate) }
            if (monitor.start()) sound = monitor else report(TrackNotice.MIC_UNAVAILABLE)
        }
        if (withMic && sound == null) host.dropMicrophone()
        val wakeLock = if (movement.needsWakeLock) {
            context.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeNudge:sleep-track")?.apply {
                setReferenceCounted(false)
                // Bounded: never longer than a session can last.
                acquire(SleepLimits.MAX_SESSION_MS + 10 * 60_000L)
            }
        } else null
        val run = Running(host, startElapsed, time.nowMillis(), accumulator, movement, sound, wakeLock)
        run.ringAtStart = ringStore.current?.eventId
        session = run
        val status = TrackStatus.Running(run.startedAt, micOn = sound != null, micStoppedForAlarm = false)
        Log.i(TAG, "session started: microphone ${if (sound != null) "on" else "off"}, wake lock ${wakeLock != null}")
        _status.value = status
        host.showStatus(status)
        val created = scope.launch(io) {
            runCatching { repo.closeStale(runningId = null) }
            run.sessionId = runCatching { repo.startSession(run.startedAt, micUsed = sound != null) }.getOrNull()
        }
        run.startJob = created
        run.jobs += scope.launch(io) {
            created.join()
            tick(run)
        }
        // Always dispatched (not Main.immediate): reacting to a ring must never run inside the call that changed the ring.
        run.jobs += scope.launch(Dispatchers.Main) { ringStore.session.collect { onRing(run, it) } }
        true
    }

    /** Ends the running session, if any. */
    fun stop(reason: SessionEnd) {
        val run = synchronized(this) {
            val r = session ?: return
            if (r.ending) return
            r.ending = true
            r
        }
        Log.i(TAG, "session ending: $reason")
        run.sound?.stop()
        run.movement.flush()
        scope.launch(io) {
            // Give the flushed batch a moment to arrive.
            delay(500)
            run.movement.stop()
            val endedElapsed = SystemClock.elapsedRealtime()
            run.jobs.forEach { if (it.isActive) it.cancel() }
            // The row is created as the session starts; never end before it exists, nor create a second one.
            run.startJob?.join()
            val id = run.sessionId
            if (id != null) {
                runCatching { repo.appendEpochs(id, run.accumulator.drain(endedElapsed, final = true)) }
                val endedAt = run.startedAt + (endedElapsed - run.startElapsed)
                runCatching { repo.endSession(id, endedAt, reason) }
            }
            runCatching { run.wakeLock?.takeIf { it.isHeld }?.release() }
            synchronized(this@SleepTracker) { if (session === run) session = null }
            _status.value = TrackStatus.Idle
            if (reason == SessionEnd.TIME_LIMIT) report(TrackNotice.TIME_LIMIT)
            run.host.stopHost()
            runCatching { cleanup.run(runningId = null) }
        }
    }

    private suspend fun tick(run: Running) {
        while (scope.isActive && !run.ending) {
            delay(SleepLimits.EPOCH_MS)
            val id = run.sessionId ?: continue
            val now = SystemClock.elapsedRealtime()
            runCatching { repo.appendEpochs(id, run.accumulator.drain(now)) }.onFailure { Log.w(TAG, "could not store epochs", it) }
            if (SessionRules.reachedLimit(run.startElapsed, now)) {
                stop(SessionEnd.TIME_LIMIT)
                return
            }
        }
    }

    private fun onRing(run: Running, ring: RingSession?) {
        if (run.ending) return
        if (ring != null && ring.phase == RingPhase.RINGING) {
            if (RingWatchPolicy.stopsMicrophone(ringing = true)) stopMicForAlarm(run)
            if (ring.eventId != run.ringAtStart || ring.isTest) {
                run.seenRing = RingWatchPolicy.SeenRing(ring.eventId, ring.isTimer, ring.isTest)
            }
            return
        }
        if (ring != null) return // a silent sunrise glow: nothing to do yet
        val seen = run.seenRing ?: return
        run.seenRing = null
        scope.launch(io) {
            // The ring controller writes the outcome as the ring ends; give it a moment.
            delay(1_500)
            if (ringStore.current != null || run.ending) return@launch
            val event = if (seen.eventId > 0) runCatching { history.get(seen.eventId) } else Result.success(null)
            val ends = RingWatchPolicy.endsSession(
                seen, event.getOrNull()?.outcome, event.getOrNull()?.snoozeUntil ?: 0, historyReadable = event.isSuccess && (seen.eventId <= 0 || event.getOrNull() != null),
            )
            Log.i(TAG, "ring ${seen.eventId} gone (test=${seen.isTest}, timer=${seen.isTimer}, outcome=${event.getOrNull()?.outcome}): ${if (ends) "ending" else "still tracking"}")
            if (ends) stop(SessionEnd.ALARM_DISMISSED)
        }
    }

    private fun stopMicForAlarm(run: Running) {
        val sound = run.sound ?: return
        if (run.micStoppedForAlarm) return
        sound.stop()
        Log.i(TAG, "microphone stopped: a ring started")
        run.micStoppedForAlarm = true
        runCatching { run.host.dropMicrophone() }
        val status = TrackStatus.Running(run.startedAt, micOn = false, micStoppedForAlarm = true)
        _status.value = status
        runCatching { run.host.showStatus(status) }
        val at = time.nowMillis()
        scope.launch(io) { run.sessionId?.let { repo.noteMicStopped(it, at) } }
    }

    private fun onClip(candidate: ClipCandidate) {
        val run = synchronized(this) { session } ?: return
        scope.launch(io) {
            val id = run.sessionId ?: return@launch
            if (run.ending) return@launch
            val recordedAt = run.startedAt + (candidate.startElapsedMs - run.startElapsed)
            val decision = ClipPolicy.decide(run.clips, recordedAt, candidate.peakDb)
            when (decision) {
                ClipDecision.Drop -> return@launch
                is ClipDecision.Replace -> {
                    runCatching { repo.deleteClip(decision.id) }
                    run.clips.removeAll { it.id == decision.id }
                }
                ClipDecision.Add -> Unit
            }
            val file = files.newFile(id, recordedAt)
            if (!ClipEncoder.encode(candidate.pcm, candidate.sampleRate, file)) return@launch
            val clipId = runCatching {
                repo.addClip(
                    SnoreClipEntity(
                        sessionId = id, recordedAt = recordedAt,
                        durationMs = candidate.pcm.size * 1000L / candidate.sampleRate,
                        peakDb = candidate.peakDb, aboveFloorDb = candidate.aboveFloorDb,
                        fileName = file.name, envelope = ClipEnvelope.encode(ClipEnvelope.of(candidate.pcm)),
                    ),
                )
            }.getOrNull()
            if (clipId == null) file.delete() else run.clips += ClipRef(clipId, recordedAt, candidate.peakDb)
            Log.i(TAG, "snore clip ${if (clipId == null) "not stored" else "kept"} ($decision)")
        }
    }

    private companion object {
        const val TAG = "SleepTracker"
    }
}
