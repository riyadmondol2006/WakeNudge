package com.riyadm.wakenudge.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class SoundMode { SINGLE, RANDOM_BUNDLED, RANDOM_CUSTOM }

@Serializable
enum class SoundSource { BUNDLED, SYSTEM, CUSTOM }

/**
 * Which sound(s) an alarm plays. [uri] is a system-ringtone or user-picked local audio URI when [source] is not
 * [SoundSource.BUNDLED]. [playlist] feeds [SoundMode.RANDOM_CUSTOM]; [randomPool] narrows [SoundMode.RANDOM_BUNDLED]
 * (empty = every bundled tone).
 */
@Serializable
data class SoundSettings(
    val mode: SoundMode = SoundMode.SINGLE,
    val source: SoundSource = SoundSource.BUNDLED,
    val bundledId: String = DEFAULT_TONE_ID,
    val uri: String? = null,
    val playlist: List<String> = emptyList(),
    val randomPool: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_TONE_ID = "sunrise"
    }
}

/**
 * [level] is the target alarm-stream volume as a percentage of the device maximum (0 means "leave the system volume
 * alone"). The software ramp runs from [rampStartPercent] to 100 % of the stream over [rampSeconds].
 */
@Serializable
data class VolumeSettings(
    val level: Int = 80,
    val rampSeconds: Int = 30,
    val rampStartPercent: Int = 15,
    /** Whether the Extra Loud boost is on. Kept as its own flag so alarms saved before the strength slider keep working. */
    val extraLoud: Boolean = false,
    /** How much of the full boost to apply, 1..100 (100 = what Extra Loud always did). Only used while [extraLoud] is on. */
    val extraLoudPercent: Int = 100,
    val useMaxSystemVolume: Boolean = false,
    val escalateWhenIgnored: Boolean = true,
) {
    /** The boost strength the user actually gets: 0 when off, otherwise 1..100. */
    val extraLoudStrength: Int get() = if (extraLoud) extraLoudPercent.coerceIn(1, 100) else 0

    companion object {
        const val MAX_RAMP_SECONDS = 600
        const val ESCALATION_INACTIVITY_SECONDS = 40
    }
}

@Serializable
enum class VibrationPattern { CLASSIC, PULSE, HEARTBEAT, SOS, WAVE, STRONG_CONTINUOUS, ESCALATING, CUSTOM }

@Serializable
data class VibrationSettings(
    val enabled: Boolean = true,
    val pattern: VibrationPattern = VibrationPattern.PULSE,
    /** Amplitude as a percentage of the motor's range, applied where the hardware supports amplitude control. */
    val strength: Int = 100,
    /** Alternating off/on durations in ms (starting with a pause) for [VibrationPattern.CUSTOM]. */
    val customTimings: List<Long> = emptyList(),
)

@Serializable
data class SnoozeSettings(
    val enabled: Boolean = true,
    val minutes: Int = 10,
    /** 0 means unlimited. */
    val maxCount: Int = 3,
    val decreasing: Boolean = false,
    val decreaseStepMinutes: Int = 2,
)

@Serializable
data class WakeCheckSettings(
    val enabled: Boolean = false,
    val delayMinutes: Int = 5,
    val responseSeconds: Int = 60,
    /** How many consecutive "Still awake?" checks follow a dismissal. */
    val maxChecks: Int = 1,
    /** How many times an unanswered check may re-ring the alarm before giving up. */
    val maxReRings: Int = 3,
)

@Serializable
data class AntiOversleepSettings(
    /** Seconds the dismiss control must be held; 0 means a plain tap. */
    val holdToDismissSeconds: Int = 0,
    val backupEnabled: Boolean = true,
    val backupDelayMinutes: Int = 10,
    val backupCount: Int = 1,
    /** Minutes before ringing during which the alarm cannot be edited/disabled; 0 disables the protection. */
    val editLockMinutes: Int = 0,
    /** Auto-silence after this many minutes of ringing without any action. */
    val ringTimeoutMinutes: Int = 10,
)

@Serializable
data class SunriseSettings(
    val enabled: Boolean = false,
    /** The screen starts glowing this many minutes before the alarm. */
    val leadMinutes: Int = 10,
)

@Serializable
data class VoiceSettings(
    val enabled: Boolean = false,
    val announceTime: Boolean = true,
    val announceLabel: Boolean = true,
    val repeatSeconds: Int = 45,
)
