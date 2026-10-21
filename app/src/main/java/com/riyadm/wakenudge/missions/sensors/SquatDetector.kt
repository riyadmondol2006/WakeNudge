package com.riyadm.wakenudge.missions.sensors

import com.riyadm.wakenudge.domain.model.Difficulty
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Counts squat repetitions from a phone held against the chest or in both hands.
 *
 * Vertical acceleration is the accelerometer magnitude minus a slowly tracked gravity. To first order that equals the
 * gravity-aligned component, but unlike projecting onto an estimated "down" axis it does not leak gravity into the
 * signal when the torso leans during the squat. The (band-limited) acceleration is integrated twice with leaky
 * integrators, which keeps drift bounded, to get a vertical excursion in metres.
 *
 * A repetition is a drop of at least [minDepthM] followed by a recovery of [RISE_FRACTION] of that drop, completed
 * within [MIN_DURATION_S]..[MAX_DURATION_S] seconds. Hand tremor and bumps are far shallower or faster than that and
 * are ignored.
 */
class SquatDetector(private val minDepthM: Float = 0.10f) {
    enum class Phase {
        /** Standing (or waiting for the next drop). */
        READY,

        /** The drop has been detected; the user should now stand back up. */
        AT_BOTTOM,
    }

    var phase: Phase = Phase.READY
        private set

    private var startNs = -1L
    private var lastNs = -1L
    private var gravity = STANDARD_GRAVITY
    private var accel = 0f
    private var velocity = 0f
    private var position = 0f

    private var topPosition = 0f
    private var topNs = 0L
    private var nearTopNs = 0L
    private var valleyPosition = 0f

    /** Feeds one accelerometer sample; returns true when it completes a repetition. */
    fun onSample(timestampNs: Long, x: Float, y: Float, z: Float): Boolean {
        val magnitude = sqrt(x * x + y * y + z * z)
        if (lastNs < 0) {
            startNs = timestampNs
            lastNs = timestampNs
            // A first sample far from 1 g means the phone is already moving; trust the nominal value instead.
            gravity = if (abs(magnitude - STANDARD_GRAVITY) > 2f) STANDARD_GRAVITY else magnitude
            return false
        }
        val dt = ((timestampNs - lastNs) / 1e9f).coerceIn(0.001f, 0.2f)
        lastNs = timestampNs
        val elapsed = (timestampNs - startNs) / 1e9f
        val warmingUp = elapsed < WARMUP_S

        gravity += (magnitude - gravity) * dt / ((if (warmingUp) WARMUP_GRAVITY_TAU_S else GRAVITY_TAU_S) + dt)
        accel += (magnitude - gravity - accel) * dt / (ACCEL_TAU_S + dt)
        // Leak fast while warming up so the start-up transient (integrators begin at rest even if the phone does not) dies out.
        val leak = exp(-dt / (if (warmingUp) WARMUP_INTEGRATOR_TAU_S else INTEGRATOR_TAU_S))
        velocity = (velocity + accel * dt) * leak
        position = (position + velocity * dt) * leak

        if (warmingUp) {
            topPosition = position
            topNs = timestampNs
            nearTopNs = timestampNs
            return false
        }
        return track(timestampNs)
    }

    private fun track(now: Long): Boolean {
        when (phase) {
            Phase.READY -> {
                if (position >= topPosition || seconds(now - topNs) > MAX_DURATION_S) {
                    topPosition = position
                    topNs = now
                }
                if (position >= topPosition - NEAR_TOP_FRACTION * minDepthM) nearTopNs = now
                if (topPosition - position >= minDepthM) {
                    phase = Phase.AT_BOTTOM
                    valleyPosition = position
                }
            }
            Phase.AT_BOTTOM -> {
                if (position < valleyPosition) valleyPosition = position
                val depth = topPosition - valleyPosition
                if (position - valleyPosition >= RISE_FRACTION * depth) {
                    val duration = seconds(now - nearTopNs)
                    reset(now)
                    return duration in MIN_DURATION_S..MAX_DURATION_S
                }
                // Lingering at the bottom for too long is not a rep; start over from here.
                if (seconds(now - nearTopNs) > MAX_DURATION_S) reset(now)
            }
        }
        return false
    }

    private fun reset(now: Long) {
        phase = Phase.READY
        topPosition = position
        topNs = now
        nearTopNs = now
    }

    private fun seconds(ns: Long): Float = ns / 1e9f

    companion object {
        const val MIN_DURATION_S = 0.8f
        const val MAX_DURATION_S = 4.0f
        private const val RISE_FRACTION = 0.7f
        private const val NEAR_TOP_FRACTION = 0.25f
        private const val WARMUP_S = 1.5f
        private const val WARMUP_GRAVITY_TAU_S = 0.4f
        private const val GRAVITY_TAU_S = 3.0f
        private const val ACCEL_TAU_S = 0.06f
        private const val INTEGRATOR_TAU_S = 1.2f
        private const val WARMUP_INTEGRATOR_TAU_S = 0.4f

        /** Deeper required drop on harder levels; a full squat moves a chest-held phone roughly 0.25-0.4 m. */
        fun forDifficulty(difficulty: Difficulty): SquatDetector = SquatDetector(
            when (difficulty) {
                Difficulty.EASY -> 0.10f
                Difficulty.MEDIUM -> 0.14f
                Difficulty.HARD -> 0.18f
            },
        )
    }
}
