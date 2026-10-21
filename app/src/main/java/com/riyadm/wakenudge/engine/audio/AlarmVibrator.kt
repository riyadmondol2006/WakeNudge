package com.riyadm.wakenudge.engine.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.riyadm.wakenudge.domain.model.VibrationSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmVibrator @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    val isAvailable: Boolean get() = vibrator.hasVibrator()
    val hasAmplitudeControl: Boolean get() = vibrator.hasAmplitudeControl()

    /** Repeats the pattern until [stop]. Uses the ALARM usage so it is not suppressed by silent mode or DND. */
    fun start(settings: VibrationSettings, intensified: Boolean) {
        if (!settings.enabled || !isAvailable) return
        val w = VibrationPatterns.waveform(settings, intensified)
        val effect = if (hasAmplitudeControl) {
            VibrationEffect.createWaveform(w.timings, w.amplitudes, 0)
        } else {
            VibrationEffect.createWaveform(w.timings, 0)
        }
        play(effect)
    }

    /** Plays the pattern once (editor preview). */
    fun preview(settings: VibrationSettings) {
        if (!isAvailable) return
        val w = VibrationPatterns.waveform(settings.copy(enabled = true), intensified = false)
        val effect = if (hasAmplitudeControl) {
            VibrationEffect.createWaveform(w.timings, w.amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(w.timings, -1)
        }
        play(effect)
    }

    fun stop() = vibrator.cancel()

    private fun play(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build())
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
        }
    }
}
