package com.riyadm.wakenudge.domain.sleep

import kotlin.math.ceil
import kotlin.math.max

/**
 * Turns a night of 30-second epochs into estimated stages. This is a transparent rule of thumb, not a measurement: a
 * phone on a mattress feels the body move and (with the microphone on) hears the room. It cannot see brain waves, so the
 * stages are a guess shaped by what sleep usually looks like. Every rule is here and in docs/ARCHITECTURE.md.
 *
 * 1. **Enough data?** Fewer than [MIN_DATA_EPOCHS] epochs with readings, or readings for less than [MIN_COVERAGE] of the
 *    time in bed, gives [NightVerdict.TOO_LITTLE_DATA]. Not a single reading above [EpochAccumulator.MOVE_THRESHOLD] in
 *    [NO_MOVEMENT_EPOCHS] or more epochs gives [NightVerdict.NO_MOVEMENT]: a sleeper always turns over, so the phone was
 *    most likely not on the bed.
 * 2. **Movement per epoch**, relative to this phone tonight: the noise floor is the 10th percentile of the epochs' mean
 *    change of acceleration. An epoch is *restless* with two or more clear movements or a mean at least [RESTLESS_FACTOR]
 *    times the floor, a *twitch* with one movement or at least [TWITCH_FACTOR] times the floor, otherwise *still*. With the
 *    microphone on, a twitch during a loud sound that is not snoring ([NOISE_ABOVE_FLOOR_DB] over the night's quiet level)
 *    counts as restless: people who talk, cough or get up are awake.
 * 3. **Awake** when at least [AWAKE_RESTLESS] of the five epochs around it (2.5 minutes) are restless.
 * 4. **Falling asleep** is the start of the first [ONSET_EPOCHS] epochs (10 minutes) in a row with readings, none of them
 *    restless or awake. Everything before it is awake; so is everything after the last sleeping epoch. Latency is the time
 *    from the start of the session to that point.
 * 5. **Deep** inside long stretches of complete stillness: a run of still epochs of at least [DEEP_RUN] epochs (20
 *    minutes), or [DEEP_RUN_LATE] (30 minutes) in the last third of the sleep, where deep sleep is rarer. The first
 *    [DEEP_LEAD_IN] and last [DEEP_TAIL] epochs of the run stay light, since sleep deepens gradually.
 * 6. **REM** from [REM_EARLIEST] epochs (an hour) after falling asleep, where the body is quiet apart from small twitches:
 *    within five minutes around the epoch at least [REM_TWITCHES] twitches and nothing restless, and (with the microphone
 *    on) no snoring, which is uncommon in REM.
 * 7. Every other sleeping epoch is **light**. Epochs without readings get no stage and count towards nothing.
 */
object SleepStageEstimator {
    const val MIN_DATA_EPOCHS = 20
    const val MIN_COVERAGE = 0.5
    const val NO_MOVEMENT_EPOCHS = 40
    const val RESTLESS_FACTOR = 3.0f
    const val TWITCH_FACTOR = 1.6f
    const val MIN_FLOOR = 0.002f
    const val NOISE_ABOVE_FLOOR_DB = 20f
    const val AWAKE_RESTLESS = 3
    const val ONSET_EPOCHS = 20
    const val DEEP_RUN = 40
    const val DEEP_RUN_LATE = 60
    const val DEEP_LEAD_IN = 10
    const val DEEP_TAIL = 4
    const val REM_EARLIEST = 120
    const val REM_WINDOW = 10
    const val REM_TWITCHES = 2

    enum class Motion { UNKNOWN, STILL, TWITCH, RESTLESS }

    fun estimate(epochs: List<Epoch>, inBedMs: Long): NightSummary {
        val short = inBedMs < SleepLimits.SHORT_SESSION_MS
        val count = max(ceil(inBedMs.coerceAtLeast(0) / SleepLimits.EPOCH_MS.toDouble()).toInt(), (epochs.maxOfOrNull { it.index } ?: -1) + 1)
        val byIndex = arrayOfNulls<Epoch>(count)
        epochs.forEach { if (it.index in 0 until count && it.hasMovementData) byIndex[it.index] = it }
        val data = byIndex.filterNotNull()
        if (data.size < MIN_DATA_EPOCHS || data.size < count * MIN_COVERAGE) return NightSummary.none(NightVerdict.TOO_LITTLE_DATA, inBedMs, short)
        if (data.size >= NO_MOVEMENT_EPOCHS && data.all { it.moveSamples == 0 }) return NightSummary.none(NightVerdict.NO_MOVEMENT, inBedMs, short)

        val motion = motions(byIndex)
        val awake = BooleanArray(count) { i ->
            motion[i] != Motion.UNKNOWN && (i - 2..i + 2).count { it in 0 until count && motion[it] == Motion.RESTLESS } >= AWAKE_RESTLESS
        }
        val calm = { i: Int -> motion[i] != Motion.UNKNOWN && motion[i] != Motion.RESTLESS && !awake[i] }
        val onset = (0..count - ONSET_EPOCHS).firstOrNull { start -> (start until start + ONSET_EPOCHS).all(calm) }
        val stages = arrayOfNulls<SleepStage>(count)
        if (onset == null) {
            for (i in 0 until count) if (motion[i] != Motion.UNKNOWN) stages[i] = SleepStage.AWAKE
            return summarise(stages.toList(), inBedMs, latency = null, onset = null, end = null, short = short)
        }
        val end = (count - 1 downTo onset).first { motion[it] != Motion.UNKNOWN && !awake[it] }

        val deep = deepEpochs(motion, onset, end)
        for (i in 0 until count) {
            if (motion[i] == Motion.UNKNOWN) continue
            stages[i] = when {
                i < onset || i > end || awake[i] -> SleepStage.AWAKE
                deep[i] -> SleepStage.DEEP
                isRem(i, onset, motion, byIndex) -> SleepStage.REM
                else -> SleepStage.LIGHT
            }
        }
        return summarise(stages.toList(), inBedMs, latency = onset * SleepLimits.EPOCH_MS, onset = onset, end = end, short = short)
    }

    /** Movement class of every epoch, judged against this night's own noise floor. */
    fun motions(byIndex: Array<Epoch?>): Array<Motion> {
        val data = byIndex.filterNotNull()
        val floor = percentile(data.map { it.meanDelta }, 0.10).coerceAtLeast(MIN_FLOOR)
        val sounds = data.mapNotNull { it.soundDb }
        val quiet = if (sounds.isEmpty()) null else percentile(sounds, 0.10)
        return Array(byIndex.size) { i ->
            val e = byIndex[i] ?: return@Array Motion.UNKNOWN
            val base = when {
                e.moveSamples >= 2 || e.meanDelta >= floor * RESTLESS_FACTOR -> Motion.RESTLESS
                e.moveSamples == 1 || e.meanDelta >= floor * TWITCH_FACTOR -> Motion.TWITCH
                else -> Motion.STILL
            }
            val noisy = quiet != null && e.snores == 0 && (e.soundPeakDb ?: Float.NEGATIVE_INFINITY) - quiet >= NOISE_ABOVE_FLOOR_DB
            if (base == Motion.TWITCH && noisy) Motion.RESTLESS else base
        }
    }

    private fun deepEpochs(motion: Array<Motion>, onset: Int, end: Int): BooleanArray {
        val deep = BooleanArray(motion.size)
        val lateFrom = onset + (end - onset) * 2 / 3
        var i = onset
        while (i <= end) {
            if (motion[i] != Motion.STILL) { i++; continue }
            var j = i
            while (j + 1 <= end && motion[j + 1] == Motion.STILL) j++
            val length = j - i + 1
            val needed = if ((i + j) / 2 >= lateFrom) DEEP_RUN_LATE else DEEP_RUN
            if (length >= needed) for (k in i + DEEP_LEAD_IN..j - DEEP_TAIL) deep[k] = true
            i = j + 1
        }
        return deep
    }

    private fun isRem(i: Int, onset: Int, motion: Array<Motion>, byIndex: Array<Epoch?>): Boolean {
        if (i - onset < REM_EARLIEST || motion[i] == Motion.RESTLESS) return false
        val window = (i - REM_WINDOW / 2..i + REM_WINDOW / 2).filter { it in motion.indices }
        if (window.any { motion[it] == Motion.RESTLESS }) return false
        if (window.count { motion[it] == Motion.TWITCH } < REM_TWITCHES) return false
        return window.none { (byIndex[it]?.snores ?: 0) > 0 }
    }

    private fun summarise(stages: List<SleepStage?>, inBedMs: Long, latency: Long?, onset: Int?, end: Int?, short: Boolean): NightSummary {
        fun ms(stage: SleepStage) = stages.count { it == stage } * SleepLimits.EPOCH_MS
        val awakeAfterOnset = if (onset == null || end == null) 0L
        else (onset..end).count { stages[it] == SleepStage.AWAKE } * SleepLimits.EPOCH_MS
        val deep = ms(SleepStage.DEEP)
        val light = ms(SleepStage.LIGHT)
        val rem = ms(SleepStage.REM)
        return NightSummary(
            verdict = NightVerdict.OK, stages = stages, inBedMs = inBedMs, asleepMs = deep + light + rem, latencyMs = latency,
            awakeAfterOnsetMs = awakeAfterOnset, deepMs = deep, lightMs = light, remMs = rem, awakeMs = ms(SleepStage.AWAKE), short = short,
        )
    }

    /** Nearest-rank percentile of [values] (0..1); 0 for an empty list. */
    fun percentile(values: List<Float>, p: Double): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val rank = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[rank]
    }
}
