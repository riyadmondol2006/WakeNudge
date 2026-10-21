package com.riyadm.wakenudge.engine.audio

import com.riyadm.wakenudge.domain.model.VolumeSettings
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

/** Result of [VolumePlanner.plan]. All values are advisory inputs to the audio engine. */
data class VolumePlan(
    /** Alarm-stream index to set, or null to leave the system volume alone. */
    val streamIndex: Int?,
    /** LoudnessEnhancer gain in millibels (0 = effect not needed). */
    val enhancerMillibels: Int,
    /** True when hearing-safety limits reduced what the user asked for. */
    val cappedForHearing: Boolean,
)

/**
 * Pure policy for how loud an alarm may be. Keeping it free of Android types makes the safety rules unit-testable:
 * hearing protection on headphones always wins over Extra Loud and escalation.
 */
object VolumePlanner {
    const val MAX_ENHANCER_MB = 900
    private const val EXTRA_LOUD_TARGET_LUFS = -10.0
    private const val ESCALATION_BOOST_MB = 300
    private const val CUSTOM_AUDIO_FALLBACK_BOOST_MB = 600

    /**
     * What the Extra Loud slider's 100 % means even for a tone that is already loud, so the control always does
     * something audible. Quieter tones get more, up to [MAX_ENHANCER_MB].
     */
    private const val MIN_FULL_BOOST_MB = 300

    /** Never let an alarm stay silent just because the alarm stream was muted. */
    const val MIN_AUDIBLE_PERCENT = 60

    fun plan(
        volume: VolumeSettings,
        streamMax: Int,
        currentStreamIndex: Int,
        headsetRoute: Boolean,
        hearingCapEnabled: Boolean,
        capPercent: Int,
        escalated: Boolean,
        /** Loudness of the sound being played if known (bundled tones), null for unknown custom audio. */
        sourceLufs: Double?,
    ): VolumePlan {
        val wantsMax = volume.useMaxSystemVolume || (escalated && volume.escalateWhenIgnored)
        var percent = when {
            wantsMax -> 100
            volume.level > 0 -> volume.level.coerceIn(1, 100)
            // "Use system volume": respect it, but never ring into a muted stream.
            currentStreamIndex <= 0 -> MIN_AUDIBLE_PERCENT
            else -> 0
        }

        var capped = false
        val limit = capPercent.coerceIn(10, 100)
        if (headsetRoute && hearingCapEnabled && percent > limit) {
            percent = limit
            capped = true
        }
        if (headsetRoute && hearingCapEnabled && percent == 0 && currentStreamIndex > percentToIndex(limit, streamMax)) {
            percent = limit
            capped = true
        }

        var boost = 0
        if (!(headsetRoute && hearingCapEnabled)) {
            val strength = volume.extraLoudStrength
            if (strength > 0) {
                // The full boost brings the tone up to the target loudness; the slider takes a proportion of it.
                val full = sourceLufs?.let { dbToMillibels((EXTRA_LOUD_TARGET_LUFS - it).coerceAtLeast(0.0)).coerceIn(MIN_FULL_BOOST_MB, MAX_ENHANCER_MB) }
                    ?: CUSTOM_AUDIO_FALLBACK_BOOST_MB
                boost += (full * strength / 100.0).roundToInt()
            }
            if (escalated && volume.escalateWhenIgnored) boost += ESCALATION_BOOST_MB
        }
        return VolumePlan(
            streamIndex = if (percent == 0) null else percentToIndex(percent, streamMax),
            enhancerMillibels = boost.coerceIn(0, MAX_ENHANCER_MB),
            cappedForHearing = capped,
        )
    }

    fun percentToIndex(percent: Int, streamMax: Int): Int =
        ceil(streamMax * percent.coerceIn(0, 100) / 100.0).roundToInt().coerceIn(1, streamMax.coerceAtLeast(1))

    private fun dbToMillibels(db: Double) = (db * 100).roundToInt()

    /**
     * Software gain (0..1) of the volume ramp after [elapsedMs]. Growth is geometric, i.e. linear in decibels, which
     * is how loudness is perceived: the first seconds are gentle and the last ones do not jump.
     */
    fun rampGain(elapsedMs: Long, rampSeconds: Int, startPercent: Int): Float {
        if (rampSeconds <= 0) return 1f
        val start = (startPercent.coerceIn(1, 100) / 100f).coerceAtLeast(0.02f)
        val fraction = (elapsedMs / (rampSeconds * 1000f)).coerceIn(0f, 1f)
        return start.toDouble().pow((1f - fraction).toDouble()).toFloat()
    }
}
