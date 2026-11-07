package com.riyadm.wakenudge.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** What answered a "Still awake?" check. A plain tap is proof of nothing on an alarm that asks for proof. */
enum class WakeProof { NONE, FOCUS_TAP, STEPS }

/**
 * The hardware step counter's running total since boot, or null when there is none, the permission is refused, or it
 * does not answer in time. Only the counter: it keeps counting while nothing listens, so it can say how far the user
 * walked since the dismissal. The step detector reports only while it is listened to, and the accelerometer is what a
 * shaken phone fools, so neither is used here.
 */
fun interface StepTotal {
    suspend fun read(): Float?
}

/** The pure rules of a check that needs proof. */
object WakeProofPolicy {
    /** Steps since the dismissal that answer the check on their own. */
    const val STEPS_NEEDED = 30

    /** The prompt comes up to this much later than the alarm's delay, so it cannot be waited out to the second. */
    const val MAX_EXTRA_DELAY_MS = 3 * 60_000L

    /** Whether the steps walked since [baseline] answer the check. A restart in between (the total went down) is no proof. */
    fun stepsAnswer(baseline: Float?, now: Float?): Boolean =
        baseline != null && now != null && now >= baseline && now - baseline >= STEPS_NEEDED

    fun extraDelayMs(random: Random): Long = random.nextLong(0, MAX_EXTRA_DELAY_MS + 1)

    /** Whether [proof] may answer a check of an alarm that does or does not [requireProof]. */
    fun accepts(requireProof: Boolean, proof: WakeProof): Boolean = !requireProof || proof != WakeProof.NONE
}

@Singleton
class HardwareStepTotal @Inject constructor(@ApplicationContext private val context: Context) : StepTotal {
    override suspend fun read(): Float? = try {
        val manager = context.getSystemService(SensorManager::class.java)
        val counter = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (manager == null || counter == null || !granted) null
        else withTimeoutOrNull(READ_TIMEOUT_MS) { firstValue(manager, counter) }?.takeUnless { it.isNaN() }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        null
    }

    private suspend fun firstValue(manager: SensorManager, counter: Sensor): Float = suspendCancellableCoroutine { cont ->
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                manager.unregisterListener(this)
                if (cont.isActive) cont.resumeWith(Result.success(event.values[0]))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        cont.invokeOnCancellation { manager.unregisterListener(listener) }
        if (!manager.registerListener(listener, counter, SensorManager.SENSOR_DELAY_NORMAL) && cont.isActive) {
            cont.resumeWith(Result.success(Float.NaN))
        }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 3_000L
    }
}
