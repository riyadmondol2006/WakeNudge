package com.riyadm.wakenudge.engine.sleeptrack

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.riyadm.wakenudge.domain.sleep.EpochAccumulator
import com.riyadm.wakenudge.domain.sleep.FrameAnalyzer
import com.riyadm.wakenudge.domain.sleep.PcmRing
import com.riyadm.wakenudge.domain.sleep.SleepLimits
import com.riyadm.wakenudge.domain.sleep.SnoreDetector

/** A moment of snoring worth keeping: the last few seconds of sound, cut once the episode was over. */
class ClipCandidate(
    val pcm: ShortArray,
    val sampleRate: Int,
    /** Start of the clip on the elapsed clock. */
    val startElapsedMs: Long,
    val peakDb: Float,
    val aboveFloorDb: Float,
)

/**
 * The microphone while "Record snoring" is on: sound level per 50 ms frame into the epochs, snore-like episodes from
 * [SnoreDetector], and a clip of the last [SleepLimits.MAX_CLIP_MS] once snoring is confirmed (at most one a minute;
 * which clips are kept is decided by the tracker). Sound is analysed as it arrives and only the clip candidates leave
 * this class; nothing else is stored. Runs on its own thread; [stop] returns at once and the thread releases the
 * microphone as it ends.
 */
class SoundMonitor(
    private val context: Context,
    private val accumulator: EpochAccumulator,
    private val onClip: (ClipCandidate) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var record: AudioRecord? = null
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    fun start(): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val (r, rate) = open() ?: return false
        val started = runCatching { r.startRecording(); r.recordingState == AudioRecord.RECORDSTATE_RECORDING }.getOrDefault(false)
        if (!started) {
            runCatching { r.release() }
            return false
        }
        record = r
        running = true
        thread = Thread({ loop(r, rate) }, "sleep-sound").apply { isDaemon = true; start() }
        return true
    }

    /** Stops listening now. Safe from any thread, and more than once. */
    fun stop() {
        running = false
        record?.let { runCatching { it.stop() } }
    }

    private fun open(): Pair<AudioRecord, Int>? {
        val am = context.getSystemService(AudioManager::class.java)
        // Unprocessed sound where the phone offers it, else the voice-recognition input, which has no automatic gain:
        // gain control would make a quiet room look as loud as snoring.
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        for (rate in intArrayOf(16_000, 44_100)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val r = try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
                AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 2 * 2))
            } catch (_: SecurityException) {
                return null
            } catch (_: Exception) {
                continue
            }
            if (r.state == AudioRecord.STATE_INITIALIZED) return r to rate
            runCatching { r.release() }
        }
        return null
    }

    private fun loop(r: AudioRecord, rate: Int) {
        val frameMs = 50L
        val frame = ShortArray((rate * frameMs / 1000).toInt())
        val analyzer = FrameAnalyzer(rate)
        val detector = SnoreDetector(frameMs)
        val ring = PcmRing((rate * SleepLimits.MAX_CLIP_MS / 1000).toInt())
        var pendingAt = -1L
        var pendingPeak = Float.NEGATIVE_INFINITY
        var pendingAbove = 0f
        var lastClipAt = Long.MIN_VALUE / 2
        try {
            while (running) {
                var filled = 0
                while (filled < frame.size && running) {
                    val n = r.read(frame, filled, frame.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (!running || filled <= 0) break
                val t = SystemClock.elapsedRealtime() - frameMs
                ring.write(frame, filled)
                val f = analyzer.analyze(frame, filled)
                accumulator.addSound(t, f.db)
                detector.onFrame(t, f)?.let { event ->
                    accumulator.addSnore(event.endMs)
                    if (t - lastClipAt >= CLIP_SPACING_MS) {
                        if (pendingAt < 0) pendingAt = t + CLIP_TAIL_MS
                        pendingPeak = maxOf(pendingPeak, event.peakDb)
                        pendingAbove = maxOf(pendingAbove, event.aboveFloorDb)
                    }
                }
                if (pendingAt in 0..t) {
                    val pcm = ring.last(Int.MAX_VALUE) // everything held: up to the clip length
                    val start = t + frameMs - pcm.size * 1000L / rate
                    runCatching { onClip(ClipCandidate(pcm, rate, start, pendingPeak, pendingAbove)) }
                    lastClipAt = t
                    pendingAt = -1
                    pendingPeak = Float.NEGATIVE_INFINITY
                    pendingAbove = 0f
                }
            }
        } finally {
            running = false
            runCatching { r.stop() }
            runCatching { r.release() }
            if (record === r) record = null
        }
    }

    private companion object {
        /** A candidate is cut this long after the snore that triggered it, so the clip ends after the sound. */
        const val CLIP_TAIL_MS = 1_500L
        /** At most one candidate a minute. */
        const val CLIP_SPACING_MS = 60_000L
    }
}
