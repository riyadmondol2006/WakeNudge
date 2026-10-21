package com.riyadm.wakenudge.engine.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Speaks the time/label with the device's text-to-speech engine, preferring an installed (offline) voice and never
 * asking for network synthesis. If no engine is available the alarm simply rings without announcements.
 */
class VoiceAnnouncer(
    context: Context,
    private val onSpeaking: (Boolean) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var queued: String? = null
    private val appContext = context.applicationContext

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                failed = true
                onProblem("TextToSpeech init failed ($status)")
            } else {
                configure()
                ready = true
                queued?.let { speak(it) }
                queued = null
            }
        }
    }

    private fun configure() {
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        val locale = Locale.getDefault()
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            onProblem("TTS has no voice for $locale")
        }
        val offline: Voice? = runCatching {
            engine.voices.orEmpty()
                .filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language && !it.features.orEmpty().contains("notInstalled") }
                .maxByOrNull { it.quality }
        }.getOrNull()
        if (offline != null) engine.voice = offline
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onSpeaking(true)
            override fun onDone(utteranceId: String?) = onSpeaking(false)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onSpeaking(false)
        })
    }

    fun speak(text: String) {
        if (failed) return
        if (!ready) {
            queued = text
            return
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wakenudge-announce")
        if (result != TextToSpeech.SUCCESS) onProblem("TTS speak failed ($result)")
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
        onSpeaking(false)
    }
}
