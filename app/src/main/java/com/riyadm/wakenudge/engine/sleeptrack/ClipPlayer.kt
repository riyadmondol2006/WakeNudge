package com.riyadm.wakenudge.engine.sleeptrack

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.riyadm.wakenudge.engine.RingSessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Listen": plays one snore clip on the media stream. It asks for transient focus and stops on any focus loss, so an
 * alarm (which takes exclusive focus) is never fought; it also stops the moment a ring starts and refuses to start
 * during one.
 */
@Singleton
class ClipPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ringStore: RingSessionStore,
) {
    // Always dispatched: stopping must never run inside the call that started a ring.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _playing = MutableStateFlow<Long?>(null)

    /** The id of the clip playing, or null. */
    val playing: StateFlow<Long?> = _playing.asStateFlow()

    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private val audio get() = context.getSystemService(AudioManager::class.java)

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        scope.launch { ringStore.session.collect { if (it != null) stop() } }
    }

    /** Returns false when the clip could not be played (missing file, a ring on, focus refused). */
    fun play(clipId: Long, file: File): Boolean {
        stop()
        if (ringStore.current != null || !file.isFile) return false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }
            .build()
        if (audio?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = request
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { stop() }
            mp.setOnErrorListener { _, _, _ -> stop(); true }
            mp.setOnPreparedListener { if (player === mp) it.start() }
            player = mp
            _playing.value = clipId
            mp.prepareAsync()
            true
        } catch (_: Exception) {
            stop()
            runCatching { mp.release() }
            false
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        focus?.let { runCatching { audio?.abandonAudioFocusRequest(it) } }
        focus = null
        _playing.value = null
    }
}
