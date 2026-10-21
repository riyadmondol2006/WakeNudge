package com.riyadm.wakenudge.missions.sensors

/** Where the steps of the STEPS mission come from, best source first. */
enum class StepSource { COUNTER, DETECTOR, ACCELEROMETER, NONE }

/** What the phone offers; separated from the Android classes so the choice can be unit tested. */
data class StepSensorAvailability(
    val hasCounter: Boolean,
    val hasDetector: Boolean,
    val hasAccelerometer: Boolean,
    /** True when ACTIVITY_RECOGNITION is granted (always true below Android 10, where the permission does not exist). */
    val permissionGranted: Boolean,
    /** The user chose to count with the motion sensor instead of granting the permission. */
    val preferAccelerometer: Boolean = false,
) {
    fun choose(): StepSource = when {
        preferAccelerometer && hasAccelerometer -> StepSource.ACCELEROMETER
        permissionGranted && hasCounter -> StepSource.COUNTER
        permissionGranted && hasDetector -> StepSource.DETECTOR
        hasAccelerometer -> StepSource.ACCELEROMETER
        else -> StepSource.NONE
    }

    /** Whether asking for the permission would unlock a better source than the one in use. */
    val permissionWouldHelp: Boolean get() = !permissionGranted && (hasCounter || hasDetector)
}

/**
 * Turns the hardware step counter's running total (steps since boot) into steps taken during the mission. The first
 * reading is the baseline, because the counter does not reset between missions. State is plain numbers so it can be
 * kept with `rememberSaveable` across configuration changes; a counter value below the baseline (a reboot mid-mission)
 * re-bases while keeping what was already earned.
 */
class StepCounterTracker(carried: Int = 0, baseline: Float? = null, last: Float? = null) {
    var carried: Int = carried
        private set
    var baseline: Float? = baseline
        private set
    var last: Float? = last
        private set

    val steps: Int get() = carried + ((last ?: 0f) - (baseline ?: 0f)).toInt().coerceAtLeast(0)

    fun onCounter(total: Float): Int {
        val base = baseline
        if (base == null) {
            baseline = total
        } else if (total < base) {
            carried += ((last ?: base) - base).toInt().coerceAtLeast(0)
            baseline = total
        }
        last = total
        return steps
    }

    /** Each step-detector event is exactly one step. */
    fun onDetectorStep(): Int {
        carried += 1
        return steps
    }

    /** Steps from the accelerometer fallback (may credit several at once). */
    fun add(count: Int): Int {
        carried += count
        return steps
    }

    fun toSaved(): List<Float> = listOf(carried.toFloat(), baseline ?: Float.NaN, last ?: Float.NaN)

    companion object {
        fun restore(saved: List<Float>): StepCounterTracker =
            StepCounterTracker(saved[0].toInt(), saved[1].takeUnless { it.isNaN() }, saved[2].takeUnless { it.isNaN() })
    }
}
