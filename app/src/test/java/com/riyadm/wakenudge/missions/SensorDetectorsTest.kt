package com.riyadm.wakenudge.missions

import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.missions.sensors.ShakeDetector
import com.riyadm.wakenudge.missions.sensors.SquatDetector
import com.riyadm.wakenudge.missions.sensors.StepCounterTracker
import com.riyadm.wakenudge.missions.sensors.StepDetector
import com.riyadm.wakenudge.missions.sensors.StepSensorAvailability
import com.riyadm.wakenudge.missions.sensors.StepSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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

class ShakeDetectorTest {
    private fun count(detector: ShakeDetector, seconds: Int, signal: (Float) -> Float): Int {
        var shakes = 0
        for (i in 0 until seconds * SAMPLE_HZ) {
            val t = i.toFloat() / SAMPLE_HZ
            val (x, y, z) = reading(signal(t))
            if (detector.onSample(i * DT_NS, x, y, z)) shakes++
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
        val shakes = count(ShakeDetector.forDifficulty(Difficulty.MEDIUM), seconds = 10) { t -> G + 22f * sin(2f * PI.toFloat() * 3f * t) }
        assertTrue("counted $shakes", shakes in 27..32)
    }

    @Test fun `gentle swaying stays below the easy threshold`() {
        val shakes = count(ShakeDetector.forDifficulty(Difficulty.EASY), seconds = 10) { t -> G + 3f * sin(2f * PI.toFloat() * 1.5f * t) }
        assertEquals(0, shakes)
    }

    @Test fun `harder levels need harder shakes`() {
        val moderate: (Float) -> Float = { t -> G + 12f * sin(2f * PI.toFloat() * 2f * t) }
        val easy = count(ShakeDetector.forDifficulty(Difficulty.EASY), 10, moderate)
        val medium = count(ShakeDetector.forDifficulty(Difficulty.MEDIUM), 10, moderate)
        val hard = count(ShakeDetector.forDifficulty(Difficulty.HARD), 10, moderate)
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
    private fun stepsWhileWalking(frequencyHz: Float, seconds: Int, amplitude: Float = 3f, tilt: Float = 25f, noise: Float = 0.1f, seed: Int = 1): Int {
        val detector = StepDetector()
        val random = Random(seed)
        var steps = 0
        for (i in 0 until seconds * SAMPLE_HZ) {
            val t = i.toFloat() / SAMPLE_HZ
            val w = 2f * PI.toFloat() * frequencyHz * t
            // A heel-strike shaped bounce: fundamental plus a weaker second harmonic.
            val bounce = amplitude * sin(w) + 0.35f * amplitude * sin(2f * w + 0.8f)
            val (x, y, z) = reading(G + bounce, tilt, noise, random)
            steps += detector.onSample(i * DT_NS, x, y, z)
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
        var walking = 0
        var afterStop = 0
        for (i in 0 until 20 * SAMPLE_HZ) {
            val t = i.toFloat() / SAMPLE_HZ
            val bounce = if (t < 10f) 3f * sin(2f * PI.toFloat() * 1.8f * t) else 0f
            val (x, y, z) = reading(G + bounce)
            val s = detector.onSample(i * DT_NS, x, y, z)
            if (t < 10.5f) walking += s else afterStop += s
        }
        assertTrue("walking $walking", walking in 15..19)
        assertEquals(0, afterStop)
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
