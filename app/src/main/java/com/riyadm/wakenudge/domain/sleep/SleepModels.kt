package com.riyadm.wakenudge.domain.sleep

/** The fixed numbers of sleep tracking, in one place so the tests, the service and the docs agree. */
object SleepLimits {
    /** Movement and sound are summarised per epoch of this length; only these summaries are stored. */
    const val EPOCH_MS = 30_000L

    /** A session ends itself after this long, in case "I'm up" was never tapped and no alarm rang. */
    const val MAX_SESSION_MS = 16 * 60 * 60_000L

    /** Sessions shorter than this are kept but marked short. */
    const val SHORT_SESSION_MS = 60 * 60_000L

    /** Nights older than this are deleted automatically. */
    const val SESSION_RETENTION_MS = 365L * 24 * 60 * 60_000L

    /** Snore clips older than this are deleted automatically. */
    const val CLIP_RETENTION_MS = 7L * 24 * 60 * 60_000L

    /** At most this many clips are kept per night: the loudest. */
    const val MAX_CLIPS_PER_NIGHT = 3

    /** A clip is at most this long. */
    const val MAX_CLIP_MS = 10_000L

    /** Accelerometer sampling period: 10 samples a second is plenty for a body turning over and cheap to batch. */
    const val SENSOR_PERIOD_US = 100_000

    /** How long the sensor hub may hold samples before handing them over, so the CPU can sleep in between. */
    const val SENSOR_BATCH_US = 20_000_000

    fun epochsPerHour(): Int = (60 * 60_000L / EPOCH_MS).toInt()
}

/** A stage as the estimator sees it. Order is the order of the stacked bar from the bottom up. */
enum class SleepStage { DEEP, LIGHT, REM, AWAKE }

/** Why a session ended. Stored by name. */
enum class SessionEnd {
    /** "I'm up" on the screen or in the notification. */
    USER,
    /** An alarm that rang during the session was dismissed. */
    ALARM_DISMISSED,
    /** The session ran for [SleepLimits.MAX_SESSION_MS]. */
    TIME_LIMIT,
    /** Android stopped the service (time limit, low memory) or the process died; closed when next seen. */
    STOPPED_BY_SYSTEM,
}

/**
 * One 30-second epoch as stored. [samples] is how many accelerometer readings arrived; an epoch with none carries no
 * movement data (the sensor was stopped or the phone dropped its batch) and the estimator treats it as unknown.
 * Sound fields are null when the microphone was off for that epoch.
 */
data class Epoch(
    val index: Int,
    val samples: Int,
    /** Mean change of acceleration between consecutive readings, m/s². Breathing on a mattress is ~0.01, turning over ~0.3+. */
    val meanDelta: Float,
    /** Largest change between two consecutive readings, m/s². */
    val maxDelta: Float,
    /** Readings whose change was at least [EpochAccumulator.MOVE_THRESHOLD]. */
    val moveSamples: Int,
    /** Average sound level of the epoch in dBFS (energy average), or null without the microphone. */
    val soundDb: Float? = null,
    /** Loudest 50 ms frame of the epoch in dBFS, or null without the microphone. */
    val soundPeakDb: Float? = null,
    /** Snore-like episodes that ended in this epoch. */
    val snores: Int = 0,
) {
    val hasMovementData: Boolean get() = samples > 0
}

/** What a night's estimate is, or why there is none. */
enum class NightVerdict {
    OK,
    /** Too few epochs carried movement data to say anything. */
    TOO_LITTLE_DATA,
    /** Hardly any movement all night: the phone was probably not on the bed. */
    NO_MOVEMENT,
}

/** The estimate for one session. All durations in milliseconds. */
data class NightSummary(
    val verdict: NightVerdict,
    /** One stage per epoch, null where there was no data. Empty unless [verdict] is [NightVerdict.OK]. */
    val stages: List<SleepStage?>,
    val inBedMs: Long,
    val asleepMs: Long,
    /** From the start of the session to the first sustained stillness; null when no sleep was found. */
    val latencyMs: Long?,
    /** Time awake between falling asleep and the last sleep epoch. */
    val awakeAfterOnsetMs: Long,
    val deepMs: Long,
    val lightMs: Long,
    val remMs: Long,
    val awakeMs: Long,
    val short: Boolean,
) {
    val hasEstimate: Boolean get() = verdict == NightVerdict.OK

    companion object {
        fun none(verdict: NightVerdict, inBedMs: Long, short: Boolean) =
            NightSummary(verdict, emptyList(), inBedMs, 0, null, 0, 0, 0, 0, 0, short)
    }
}
