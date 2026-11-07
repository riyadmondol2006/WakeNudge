package com.riyadm.wakenudge.missions

import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.missions.sensors.CadenceGuard
import com.riyadm.wakenudge.missions.sensors.ShakeDetector
import com.riyadm.wakenudge.missions.sensors.SquatDetector
import com.riyadm.wakenudge.missions.sensors.StepCounterTracker
import com.riyadm.wakenudge.missions.sensors.StepDetector
import com.riyadm.wakenudge.missions.sensors.StepSensorAvailability
import com.riyadm.wakenudge.missions.sensors.StepSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val G = 9.80665f
private const val SAMPLE_HZ = 50
private const val DT_NS = 1_000_000_000L / SAMPLE_HZ

/** Accelerometer reading for a phone tilted by [tiltDegrees] about the x axis feeling [verticalSpecificForce] m/s² up. */
private fun reading(verticalSpecificForce: Float, tiltDegrees: Float = 0f, noise: Float = 0f, random: Random? = null): Triple<Float, Float, Float> {
    val tilt = tiltDegrees * PI.toFloat() / 180f
    fun n() = if (random == null || noise == 0f) 0f else (random.nextFloat() * 2f - 1f) * noise
    return Triple(n(), verticalSpecificForce * sin(tilt) + n(), verticalSpecificForce * cos(tilt) + n())
}

/**
 * The phase of a [hz] motion as a person makes it: every cycle is a little longer or shorter than the last, with a
 * relative spread of [variation] (0 is a machine). One call per sample.
 */
private class Rhythm(private val hz: Float, private val variation: Float, seed: Int = 1) {
    private val random = Random(seed)
    private var phase = 0.0
    private var freq = hz.toDouble()

    fun next(): Float {
        val now = phase
        val before = phase.rem(2 * PI)
        phase += 2 * PI * freq / SAMPLE_HZ
        // A new cycle: uniform within ±sqrt(3) spreads has exactly that standard deviation.
        if (phase.rem(2 * PI) < before) freq = hz * (1 + variation * (random.nextDouble() * 2 - 1) * sqrt(3.0))
        return now.toFloat()
    }
}

class ShakeDetectorTest {
    /** Shakes of [amplitude] at [hz], by a person ([variation] about 0.2) or a machine (0). */
    private fun count(detector: ShakeDetector, seconds: Int, amplitude: Float, hz: Float, variation: Float = 0.2f, seed: Int = 1, fromSecond: Int = 0): Int {
        val rhythm = Rhythm(hz, variation, seed)
        var shakes = 0
        for (i in 0 until seconds * SAMPLE_HZ) {
            val (x, y, z) = reading(G + amplitude * sin(rhythm.next()))
            if (detector.onSample(i * DT_NS, x, y, z) && i >= fromSecond * SAMPLE_HZ) shakes++
        }
        return shakes
    }

    @Test fun `a phone lying still counts nothing`() {
        val random = Random(1)
        val detector = ShakeDetector.forDifficulty(Difficulty.EASY)
        var shakes = 0
        for (i in 0 until 20 * SAMPLE_HZ) {
            val (x, y, z) = reading(G, noise = 0.15f, random = random)
            if (detector.onSample(i * DT_NS, x, y, z)) shakes++
        }
        assertEquals(0, shakes)
    }

    @Test fun `a vigorous 3 Hz shake counts roughly three shakes a second`() {
        val shakes = count(ShakeDetector.forDifficulty(Difficulty.MEDIUM), seconds = 10, amplitude = 22f, hz = 3f)
        assertTrue("counted $shakes", shakes in 25..32)
    }

    @Test fun `gentle swaying stays below the easy threshold`() {
        val shakes = count(ShakeDetector.forDifficulty(Difficulty.EASY), seconds = 10, amplitude = 3f, hz = 1.5f)
        assertEquals(0, shakes)
    }

    @Test fun `a phone on a shaker, or a held-steady metronome shake, stops counting`() {
        for (hz in listOf(2f, 2.7f, 3f, 3.6f, 4f, 5f)) for (difficulty in Difficulty.entries) {
            // Ten strokes are needed to know the rhythm; from then on nothing counts.
            val later = count(ShakeDetector.forDifficulty(difficulty), seconds = 40, amplitude = 25f, hz = hz, variation = 0f, fromSecond = 10)
            assertEquals("$hz Hz at $difficulty, after the first 10 s", 0, later)
        }
    }

    @Test fun `a person shaking keeps counting, at any pace`() {
        for (hz in listOf(2f, 3f, 4f)) for (seed in 1..5) {
            val shakes = count(ShakeDetector.forDifficulty(Difficulty.MEDIUM), seconds = 10, amplitude = 22f, hz = hz, variation = 0.2f, seed = seed)
            // Never more than one shake per 300 ms dead time at Medium, however fast the hand.
            assertTrue("$hz Hz, seed $seed: counted $shakes", shakes >= (minOf(hz, 1f / 0.3f) * 10 * 0.75f).toInt())
        }
    }

    @Test fun `harder levels need harder shakes`() {
        val easy = count(ShakeDetector.forDifficulty(Difficulty.EASY), 10, amplitude = 12f, hz = 2f)
        val medium = count(ShakeDetector.forDifficulty(Difficulty.MEDIUM), 10, amplitude = 12f, hz = 2f)
        val hard = count(ShakeDetector.forDifficulty(Difficulty.HARD), 10, amplitude = 12f, hz = 2f)
        assertTrue("easy $easy", easy > 10)
        assertTrue("medium $medium", medium > 10)
        assertEquals(0, hard)
    }

    @Test fun `the refractory period merges a stroke and its rebound but not separate shakes`() {
        val detector = ShakeDetector(thresholdMs2 = 8f, refractoryNs = 300_000_000L)
        assertTrue(detector.onSample(1_000_000_000L, 0f, 0f, G + 20f))
        assertEquals(false, detector.onSample(1_100_000_000L, 0f, 0f, G - 20f))
        assertEquals(false, detector.onSample(1_299_000_000L, 0f, 0f, G + 20f))
        assertTrue(detector.onSample(1_400_000_000L, 0f, 0f, G + 20f))
    }

    @Test fun `shaking is detected in any orientation`() {
        val detector = ShakeDetector(thresholdMs2 = 8f, refractoryNs = 300_000_000L)
        assertTrue(detector.onSample(0L, 25f, 0f, 0f))
        assertTrue(detector.onSample(1_000_000_000L, 0f, -22f, 3f))
    }
}

class StepDetectorTest {
    /** A walk at [frequencyHz] whose step intervals vary by [variation] (people: about 0.02 to 0.04; a machine: 0). */
    private fun stepsWhileWalking(
        frequencyHz: Float,
        seconds: Int,
        amplitude: Float = 3f,
        tilt: Float = 25f,
        noise: Float = 0.1f,
        seed: Int = 1,
        variation: Float = 0.03f,
        fromSecond: Int = 0,
    ): Int {
        val detector = StepDetector()
        val random = Random(seed)
        val rhythm = Rhythm(frequencyHz, variation, seed)
        var steps = 0
        for (i in 0 until seconds * SAMPLE_HZ) {
            val w = rhythm.next()
            // A heel-strike shaped bounce: fundamental plus a weaker second harmonic.
            val bounce = amplitude * sin(w) + 0.35f * amplitude * sin(2f * w + 0.8f)
            val (x, y, z) = reading(G + bounce, tilt, noise, random)
            val credited = detector.onSample(i * DT_NS, x, y, z)
            if (i >= fromSecond * SAMPLE_HZ) steps += credited
        }
        return steps
    }

    @Test fun `walking at 1_8 Hz yields about 36 steps in 20 seconds`() {
        val steps = stepsWhileWalking(1.8f, 20)
        assertTrue("counted $steps", steps in 33..38)
    }

    @Test fun `a slow and a brisk cadence are both counted`() {
        val slow = stepsWhileWalking(1.1f, 20)
        val brisk = stepsWhileWalking(2.4f, 20)
        assertTrue("slow $slow (expected ~22)", slow in 19..24)
        assertTrue("brisk $brisk (expected ~48)", brisk in 43..50)
    }

    @Test fun `a gentler walk with a smaller bounce still counts`() {
        val steps = stepsWhileWalking(1.8f, 20, amplitude = 1.6f)
        assertTrue("counted $steps", steps in 31..38)
    }

    @Test fun `a phone on a machine that walks for it stops counting`() {
        for (hz in listOf(1.1f, 1.8f, 2.4f)) for (seed in 1..5) {
            val later = stepsWhileWalking(hz, 60, seed = seed, variation = 0f, fromSecond = 15)
            assertTrue("$hz Hz, seed $seed: $later steps after the first 15 s of ${(hz * 45).toInt()}", later <= hz * 45 * 0.15f)
        }
    }

    @Test fun `people walk evenly, and an even walk still counts in full`() {
        for (hz in listOf(1.1f, 1.8f, 2.4f)) for (seed in 1..5) for (variation in listOf(0.02f, 0.04f)) {
            val steps = stepsWhileWalking(hz, 20, seed = seed, variation = variation)
            assertTrue("$hz Hz, $variation, seed $seed: counted $steps", steps >= (hz * 20 * 0.9f).toInt())
        }
    }

    @Test fun `a phone lying still counts zero steps`() {
        val detector = StepDetector()
        val random = Random(5)
        var steps = 0
        for (i in 0 until 60 * SAMPLE_HZ) {
            val (x, y, z) = reading(G, 10f, noise = 0.2f, random = random)
            steps += detector.onSample(i * DT_NS, x, y, z)
        }
        assertEquals(0, steps)
    }

    @Test fun `isolated jolts such as picking the phone up are not steps`() {
        val detector = StepDetector()
        var steps = 0
        for (i in 0 until 30 * SAMPLE_HZ) {
            val t = i.toFloat() / SAMPLE_HZ
            // A 0.2 s bump of 6 m/s² every three seconds.
            val bump = if ((t % 3f) < 0.2f) 6f * sin(PI.toFloat() * (t % 3f) / 0.2f) else 0f
            val (x, y, z) = reading(G + bump)
            steps += detector.onSample(i * DT_NS, x, y, z)
        }
        assertEquals(0, steps)
    }

    @Test fun `stopping stops the count`() {
        val detector = StepDetector()
        val rhythm = Rhythm(1.8f, 0.03f)
        var walking = 0
        var afterStop = 0
        for (i in 0 until 20 * SAMPLE_HZ) {
            val t = i.toFloat() / SAMPLE_HZ
            val phase = rhythm.next()
            val bounce = if (t < 10f) 3f * sin(phase) else 0f
            val (x, y, z) = reading(G + bounce)
            val s = detector.onSample(i * DT_NS, x, y, z)
            if (t < 10.5f) walking += s else afterStop += s
        }
        assertTrue("walking $walking", walking in 15..19)
        assertEquals(0, afterStop)
    }
}

class CadenceGuardTest {
    private fun feed(guard: CadenceGuard, intervalsMs: List<Double>, startMs: Double = 0.0): List<Boolean> {
        var t = startMs
        return listOf(guard.isMechanical(t * 1e6)) + intervalsMs.map { t += it; guard.isMechanical(t * 1e6) }
    }

    @Test fun `nothing is judged before ten events`() {
        assertEquals(List(9) { false }, feed(CadenceGuard(0.08), List(8) { 333.0 }))
    }

    @Test fun `a steady rhythm is a machine's from the tenth event on, an uneven one never`() {
        assertEquals(List(9) { false } + List(5) { true }, feed(CadenceGuard(0.08), List(13) { 333.0 }))
        val person = listOf(300.0, 380.0, 290.0, 350.0, 410.0, 300.0, 330.0, 270.0, 390.0, 340.0, 300.0, 360.0)
        assertTrue(feed(CadenceGuard(0.08), person).none { it })
    }

    @Test fun `a pattern that alternates but repeats exactly is a machine's too`() {
        val alternating = List(14) { if (it % 2 == 0) 300.0 else 450.0 }
        assertTrue("its spread is wide", CadenceGuard(0.08).also { feed(it, alternating) }.variationOrNull()!! > 0.08)
        assertTrue(feed(CadenceGuard(0.08), alternating).last())
    }

    @Test fun `a pause starts the window over`() {
        val guard = CadenceGuard(0.08)
        assertTrue(feed(guard, List(12) { 333.0 }).last())
        assertEquals(List(9) { false }, feed(guard, List(8) { 333.0 }, startMs = 20_000.0))
    }
}

class StepSourceTest {
    private fun source(counter: Boolean = true, detector: Boolean = true, accel: Boolean = true, granted: Boolean = true, prefer: Boolean = false) =
        StepSensorAvailability(counter, detector, accel, granted, prefer).choose()

    @Test fun `the hardware counter is preferred when permitted`() = assertEquals(StepSource.COUNTER, source())

    @Test fun `falls back to the detector then the accelerometer`() {
        assertEquals(StepSource.DETECTOR, source(counter = false))
        assertEquals(StepSource.ACCELEROMETER, source(counter = false, detector = false))
        assertEquals(StepSource.NONE, source(counter = false, detector = false, accel = false))
    }

    @Test fun `without the permission the accelerometer is used and the permission would help`() {
        assertEquals(StepSource.ACCELEROMETER, source(granted = false))
        assertTrue(StepSensorAvailability(true, true, true, false).permissionWouldHelp)
        assertEquals(false, StepSensorAvailability(false, false, true, false).permissionWouldHelp)
        assertEquals(StepSource.NONE, source(granted = false, accel = false))
    }

    @Test fun `the user can choose the motion sensor explicitly`() = assertEquals(StepSource.ACCELEROMETER, source(prefer = true))
}

class StepCounterTrackerTest {
    @Test fun `the first reading is the baseline`() {
        val tracker = StepCounterTracker()
        assertEquals(0, tracker.onCounter(5000f))
        assertEquals(7, tracker.onCounter(5007f))
    }

    @Test fun `a counter reset keeps the steps already earned`() {
        val tracker = StepCounterTracker()
        tracker.onCounter(100f); tracker.onCounter(112f)
        assertEquals(12, tracker.onCounter(3f).let { tracker.steps })
        assertEquals(17, tracker.onCounter(8f))
    }

    @Test fun `state survives a save and restore`() {
        val tracker = StepCounterTracker()
        tracker.onCounter(900f); tracker.onCounter(925f)
        val restored = StepCounterTracker.restore(tracker.toSaved())
        assertEquals(25, restored.steps)
        assertEquals(30, restored.onCounter(930f))
    }

    @Test fun `detector steps and accelerometer steps accumulate`() {
        val tracker = StepCounterTracker()
        tracker.onDetectorStep(); tracker.onDetectorStep()
        assertEquals(5, tracker.add(3))
        assertEquals(5, StepCounterTracker.restore(tracker.toSaved()).steps)
    }
}

class SquatDetectorTest {
    /** Vertical specific force for a body bobbing sinusoidally: displacement x(t) = -amplitude*sin(2 pi f t). */
    private fun repsFor(
        frequencyHz: Float,
        amplitudeM: Float,
        seconds: Int,
        difficulty: Difficulty = Difficulty.MEDIUM,
        tilt: Float = 35f,
        noise: Float = 0.03f,
        seed: Int = 2,
    ): Pair<Int, SquatDetector> {
        val detector = SquatDetector.forDifficulty(difficulty)
        val random = Random(seed)
        var reps = 0
        val w = 2f * PI.toFloat() * frequencyHz
        for (i in 0 until seconds * SAMPLE_HZ) {
            val t = i.toFloat() / SAMPLE_HZ
            val acceleration = amplitudeM * w * w * sin(w * t)
            val (x, y, z) = reading(G + acceleration, tilt, noise, random)
            if (detector.onSample(i * DT_NS, x, y, z)) reps++
        }
        return reps to detector
    }

    @Test fun `a 0_4 Hz up-and-down motion counts one rep per cycle`() {
        val (reps, _) = repsFor(0.4f, amplitudeM = 0.12f, seconds = 30)
        // 12 cycles; the first second or two are spent warming up.
        assertTrue("counted $reps", reps in 10..12)
    }

    @Test fun `the count is independent of how the phone is tilted`() {
        for (tilt in listOf(0f, 20f, 60f, 90f)) {
            val (reps, _) = repsFor(0.4f, amplitudeM = 0.12f, seconds = 30, tilt = tilt)
            assertTrue("tilt $tilt counted $reps", reps in 10..12)
        }
    }

    @Test fun `random hand jitter counts no reps`() {
        val detector = SquatDetector.forDifficulty(Difficulty.EASY)
        val random = Random(11)
        var reps = 0
        for (i in 0 until 90 * SAMPLE_HZ) {
            val t = i.toFloat() / SAMPLE_HZ
            // 9 Hz and 12 Hz tremor plus sensor noise, with the phone tilted in the hands.
            val tremor = 0.8f * sin(2f * PI.toFloat() * 9f * t) + 0.5f * sin(2f * PI.toFloat() * 12f * t + 1f)
            val (x, y, z) = reading(G + tremor, 30f, noise = 0.12f, random = random)
            if (detector.onSample(i * DT_NS, x, y, z)) reps++
        }
        assertEquals(0, reps)
    }

    @Test fun `a phone lying still counts no reps`() {
        val detector = SquatDetector.forDifficulty(Difficulty.EASY)
        val random = Random(4)
        var reps = 0
        for (i in 0 until 120 * SAMPLE_HZ) {
            val (x, y, z) = reading(G, 12f, noise = 0.05f, random = random)
            if (detector.onSample(i * DT_NS, x, y, z)) reps++
        }
        assertEquals(0, reps)
    }

    @Test fun `bouncing too fast is not a squat`() {
        val (reps, _) = repsFor(1.8f, amplitudeM = 0.12f, seconds = 20)
        assertEquals(0, reps)
    }

    @Test fun `shallow movement does not count but a deep one does`() {
        assertEquals(0, repsFor(0.4f, amplitudeM = 0.03f, seconds = 30).first)
        assertTrue(repsFor(0.4f, amplitudeM = 0.2f, seconds = 30, difficulty = Difficulty.HARD).first >= 9)
    }

    @Test fun `hard mode needs a deeper squat than easy`() {
        val shallowEnough = 0.07f
        assertTrue(repsFor(0.4f, shallowEnough, 30, Difficulty.EASY).first >= 9)
        assertEquals(0, repsFor(0.4f, shallowEnough, 30, Difficulty.HARD).first)
    }

    @Test fun `the phase flips to bottom during the descent and back after the rep`() {
        val detector = SquatDetector.forDifficulty(Difficulty.EASY)
        val w = 2f * PI.toFloat() * 0.4f
        var sawBottom = false
        var repAt = -1
        for (i in 0 until 12 * SAMPLE_HZ) {
            val t = i.toFloat() / SAMPLE_HZ
            val (x, y, z) = reading(G + 0.12f * w * w * sin(w * t))
            if (detector.onSample(i * DT_NS, x, y, z) && repAt < 0) repAt = i
            if (detector.phase == SquatDetector.Phase.AT_BOTTOM) sawBottom = true
        }
        assertTrue(sawBottom)
        assertTrue(repAt > 0)
        assertEquals(SquatDetector.Phase.READY, SquatDetector.forDifficulty(Difficulty.EASY).phase)
    }
}
