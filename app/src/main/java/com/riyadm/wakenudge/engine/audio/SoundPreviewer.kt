package com.riyadm.wakenudge.engine.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.riyadm.wakenudge.domain.model.SoundSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Plays a few seconds of a sound so the user can choose by ear; goes through the alarm stream like the real thing. */
@Singleton
class SoundPreviewer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<String?>(null)

    /** A key identifying what is playing (bundled id or URI), or null when silent. */
    val playing: StateFlow<String?> = _playing.asStateFlow()

    fun playBundled(id: String) = play(id) { BundledTones.byId(id)?.let { BundledTones.uri(context, it) } }

    fun playUri(uri: String) = play(uri) { Uri.parse(uri) }

    fun play(settings: SoundSettings) {
        val resolved = SoundResolver.resolve(settings)
        resolved.bundledId?.let(::playBundled) ?: resolved.uri?.let(::playUri)
    }

    private fun play(key: String, uri: () -> Uri?) {
        val wasPlaying = _playing.value == key
        stop()
        if (wasPlaying) return
        val target = uri() ?: return
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(context, target)
            mp.prepare()
            mp.setOnCompletionListener { stop() }
            mp.start()
            player = mp
            _playing.value = key
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (_playing.value == key) stop() }, PREVIEW_MS)
        } catch (t: Throwable) {
            runCatching { mp.release() }
            _playing.value = null
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _playing.value = null
    }

    private companion object {
        const val PREVIEW_MS = 6_000L
    }
}
