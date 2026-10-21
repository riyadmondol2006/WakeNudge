package com.riyadm.wakenudge.engine.audio

import com.riyadm.wakenudge.domain.model.VibrationPattern
import com.riyadm.wakenudge.domain.model.VibrationSettings
import kotlin.math.roundToInt

/** A repeating waveform: [timings] alternate off/on starting with an initial delay, [amplitudes] align with them. */
class Waveform(val timings: LongArray, val amplitudes: IntArray)

/** Builds vibration waveforms. Pure and unit-tested; the Android calls live in [AlarmVibrator]. */
object VibrationPatterns {
    const val MAX_CUSTOM_STEPS = 40
    const val MAX_STEP_MS = 3000L

    private fun on(ms: Long, amp: Int) = ms to amp
    private fun off(ms: Long) = ms to 0

    fun waveform(settings: VibrationSettings, intensified: Boolean): Waveform {
        val strength = if (intensified) 100 else settings.strength.coerceIn(10, 100)
        val amp = (255 * strength / 100.0).roundToInt().coerceIn(1, 255)
        val steps: List<Pair<Long, Int>> = when (settings.pattern) {
            VibrationPattern.CLASSIC -> listOf(off(0), on(500, amp), off(500))
            VibrationPattern.PULSE -> listOf(off(0), on(120, amp), off(120), on(120, amp), off(120), on(120, amp), off(700))
            VibrationPattern.HEARTBEAT -> listOf(off(0), on(110, amp), off(90), on(170, amp), off(850))
            VibrationPattern.SOS -> sos(amp)
            VibrationPattern.WAVE -> wave(amp)
            VibrationPattern.STRONG_CONTINUOUS -> listOf(off(0), on(1200, 255), off(90))
            VibrationPattern.ESCALATING -> escalating(amp)
            VibrationPattern.CUSTOM -> custom(settings.customTimings, amp)
        }
        // An ignored alarm gets the same pattern with shorter pauses, so it feels more insistent.
        val finalSteps = if (intensified) steps.map { (ms, a) -> if (a == 0) (ms * 6 / 10) to 0 else ms to a } else steps
        return Waveform(
            timings = finalSteps.map { it.first }.toLongArray(),
            amplitudes = finalSteps.map { it.second }.toIntArray(),
        )
    }

    private fun sos(amp: Int): List<Pair<Long, Int>> = buildList {
        add(off(0))
        repeat(3) { add(on(140, amp)); add(off(140)) }
        add(off(260))
        repeat(3) { add(on(420, amp)); add(off(140)) }
        add(off(260))
        repeat(3) { add(on(140, amp)); add(off(140)) }
        add(off(900))
    }

    private fun wave(amp: Int): List<Pair<Long, Int>> = buildList {
        add(off(0))
        val levels = listOf(0.25, 0.5, 0.75, 1.0, 0.75, 0.5, 0.25)
        levels.forEach { add(on(90, (amp * it).roundToInt().coerceAtLeast(1))) }
        add(off(650))
    }

    private fun escalating(amp: Int): List<Pair<Long, Int>> = buildList {
        add(off(0))
        listOf(100L to 0.35, 200L to 0.5, 350L to 0.7, 550L to 0.85, 800L to 1.0).forEach { (ms, level) ->
            add(on(ms, (amp * level).roundToInt().coerceAtLeast(1)))
            add(off(280))
        }
        add(off(500))
    }

    private fun custom(timings: List<Long>, amp: Int): List<Pair<Long, Int>> {
        val clean = timings.take(MAX_CUSTOM_STEPS).map { it.coerceIn(0, MAX_STEP_MS) }
        if (clean.none { it > 0 }) return listOf(off(0), on(400, amp), off(400))
        // Alternating off/on starting with an initial off; make sure the list ends with an off gap so repeats breathe.
        val list = clean.toMutableList()
        if (list.size % 2 == 1) list += 400L
        return list.mapIndexed { i, ms -> if (i % 2 == 0) off(ms) else on(ms, amp) }
    }
}
