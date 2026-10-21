package com.riyadm.wakenudge.engine.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.riyadm.wakenudge.domain.model.SoundSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays a few seconds of a sound so the user can choose by ear; goes through the alarm stream like the real thing.
 *
 * Choosing a sound starts its preview straight away ([toggle] = false restarts it), while the explicit play/stop
 * button toggles. Only one preview ever plays, and everything is main-thread: the player prepares asynchronously so a
 * slow cloud-backed file can never freeze the picker.
 */
@Singleton
class SoundPreviewer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private val stopRunnable = Runnable { stop() }
    private val _playing = MutableStateFlow<String?>(null)
    private val _failed = MutableStateFlow<String?>(null)

    /** A key identifying what is playing (bundled id or URI), or null when silent. */
    val playing: StateFlow<String?> = _playing.asStateFlow()

    /** The key of the last sound that could not be played (deleted file, unsupported format), until the next attempt. */
    val failed: StateFlow<String?> = _failed.asStateFlow()

    /** True when the alarm stream is at zero: previews are then silent, though a real alarm never rings into silence. */
    val alarmStreamSilent: Boolean
        get() = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0 }.getOrDefault(false)

    fun playBundled(id: String, toggle: Boolean = true) = play(id, toggle) { BundledTones.byId(id)?.let { BundledTones.uri(context, it) } }

    fun playUri(uri: String, toggle: Boolean = true) = play(uri, toggle) { Uri.parse(uri) }

    fun play(settings: SoundSettings) {
        val resolved = SoundResolver.resolve(settings)
        resolved.bundledId?.let { playBundled(it, toggle = false) } ?: resolved.uri?.let { playUri(it, toggle = false) }
    }

    private fun play(key: String, toggle: Boolean, uri: () -> Uri?) {
        val wasPlaying = _playing.value == key
        stop()
        if (toggle && wasPlaying) return
        _failed.value = null
        val target = uri() ?: run { _failed.value = key; return }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(context, target)
            mp.setOnPreparedListener {
                // A newer preview may have replaced this one while it was preparing.
                if (player !== mp) return@setOnPreparedListener
                runCatching { it.start() }.onFailure { fail(mp, key) }
                main.removeCallbacks(stopRunnable)
                main.postDelayed(stopRunnable, PREVIEW_MS)
            }
            mp.setOnCompletionListener { if (player === mp) stop() }
            mp.setOnErrorListener { _, _, _ -> fail(mp, key); true }
            player = mp
            _playing.value = key
            mp.prepareAsync()
        } catch (t: Throwable) {
            fail(mp, key)
        }
    }

    private fun fail(mp: MediaPlayer, key: String) {
        runCatching { mp.release() }
        if (player === mp) {
            player = null
            _playing.value = null
            main.removeCallbacks(stopRunnable)
        }
        _failed.value = key
    }

    fun stop() {
        main.removeCallbacks(stopRunnable)
        val p = player
        player = null
        _playing.value = null
        if (p != null) {
            runCatching { p.stop() }
            runCatching { p.release() }
        }
    }

    private companion object {
        const val PREVIEW_MS = 6_000L
    }
}
