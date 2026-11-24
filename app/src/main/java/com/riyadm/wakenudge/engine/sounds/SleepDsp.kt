package com.riyadm.wakenudge.engine.sounds

import kotlin.math.*
import kotlin.random.Random

enum class SleepSound { WHITE, PINK, BROWN, RAIN, OCEAN, WIND, STREAM, FAN, FIRE }

/** Continuous state, never reset between buffers. Recipes use only seeded noise and oscillators.
 * White: low-passed white; pink: six octave one-poles; brown: leaky integrator.
 * Rain: bright filtered noise; ocean/wind: slow envelopes on dark noise; stream: two
 * wandering resonances over noise; fan: filtered noise with a 60 Hz hum; fire: short
 * randomly excited decays over a low bed. Slew limiting removes impulse discontinuities.
 */
class SleepGenerator(val sound: SleepSound, seed: Int, private val rate: Int = 24000) {
    private val random = Random(seed)
    private val bands = DoubleArray(6)
    private var low = 0.0
    private var brown = 0.0
    private var crackle = 0.0
    private var previous = 0.0
    private var frame = 0L
    fun fill(out: FloatArray) { for (i in out.indices) out[i] = next() }
    private fun next(): Float {
        val t = frame++.toDouble() / rate
        val w = random.nextDouble(-1.0, 1.0)
        low += 0.075 * (w - low)
        brown = (brown + w * 0.035) * 0.998
        var pink = 0.0
        for (j in bands.indices) {
            val a = 0.5 / (1 shl j)
            bands[j] += a * (w - bands[j]); pink += bands[j]
        }
        crackle *= 0.99
        if (random.nextDouble() < 0.0006) crackle += random.nextDouble(-0.7, 0.7)
        val value = when (sound) {
            SleepSound.WHITE -> w * 0.26
            SleepSound.PINK -> pink * 0.18
            SleepSound.BROWN -> brown * 0.45
            SleepSound.RAIN -> (w - low) * 0.27
            SleepSound.OCEAN -> low * (0.65 + 0.4 * sin(t * 0.55)) * 2.0
            SleepSound.WIND -> low * (0.7 + 0.25 * sin(t * 0.19) + 0.15 * sin(t * 0.47)) * 1.6
            SleepSound.STREAM -> low * 1.3 + 0.065 * sin(t * 920 + 5 * sin(t * 0.7)) + 0.035 * sin(t * 1370 + 7 * sin(t * 0.43))
            SleepSound.FAN -> low * 1.2 + 0.09 * sin(2 * PI * 60 * t)
            SleepSound.FIRE -> low * 1.3 + crackle * 0.7
        }
        val target = value.coerceIn(-0.8, 0.8) * min(1.0, t / 0.25)
        previous += (target - previous).coerceIn(-0.18, 0.18)
        return previous.toFloat()
    }
}

object SleepEnvelope {
    fun gain(remainingMs: Long): Float = (remainingMs / 30000f).coerceIn(0f, 1f)
}
