package com.riyadm.wakenudge.missions.sensors

/**
 * Notices a sensor that has gone silent: no reading and no progress for [timeoutMs] means the phone cannot do this mission.
 * A step sensor only speaks when someone walks, so for it ([gated]) only time in which the phone is moving counts: lying
 * still in bed does not turn the mission into its shorter backup. Pure; the mission calls [tick] about once a second.
 */
class SensorLiveness(private val gated: Boolean = false, private val timeoutMs: Long = TIMEOUT_MS) {
    private var silentMs = 0L
    private var lastTick = -1L
    private var lastMotion = Long.MIN_VALUE / 2

    /** The sensor reported something, or the count moved. */
    fun onReading() {
        silentMs = 0
    }

    /** The phone is moving (from the accelerometer), so a step sensor ought to be counting. */
    fun onMotion(elapsedNow: Long) {
        lastMotion = elapsedNow
    }

    /** Returns whether the sensor counts as silent at [elapsedNow]. */
    fun tick(elapsedNow: Long): Boolean {
        if (lastTick >= 0) {
            val step = (elapsedNow - lastTick).coerceIn(0, MAX_STEP_MS)
            if (!gated || elapsedNow - lastMotion <= MOTION_WINDOW_MS) silentMs += step
        }
        lastTick = elapsedNow
        return silentMs >= timeoutMs
    }

    companion object {
        const val TIMEOUT_MS = 30_000L
        private const val MAX_STEP_MS = 2_000L
        private const val MOTION_WINDOW_MS = 2_000L
    }
}
