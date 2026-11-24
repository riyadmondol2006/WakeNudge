package com.riyadm.wakenudge.engine.sleeptrack

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.riyadm.wakenudge.domain.sleep.EpochAccumulator
import com.riyadm.wakenudge.domain.sleep.SleepLimits
import kotlin.math.abs

/**
 * The accelerometer at 10 readings a second, batched by the sensor hub for up to 20 seconds so the CPU can sleep in
 * between. A wake-up accelerometer (if the phone has one) wakes the CPU itself when its batch is full; without one the
 * tracker holds a partial wake lock for the session, since a sleeping CPU would let the batch overflow and lose movement.
 */
class MovementSensor(context: Context, private val accumulator: EpochAccumulator) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var sensor: Sensor? = null
    private var thread: HandlerThread? = null

    /** True when the sensor cannot wake the CPU by itself and the tracker should hold a wake lock. */
    var needsWakeLock = true
        private set

    fun start(): Boolean {
        val m = manager ?: return false
        val s = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val t = HandlerThread("sleep-motion").apply { start() }
        val ok = runCatching { m.registerListener(this, s, SleepLimits.SENSOR_PERIOD_US, SleepLimits.SENSOR_BATCH_US, Handler(t.looper)) }.getOrDefault(false)
        if (!ok) {
            t.quitSafely()
            return false
        }
        sensor = s
        thread = t
        needsWakeLock = !s.isWakeUpSensor
        return true
    }

    /** Asks the hub to hand over what it holds, so the last epoch is complete before the session closes. */
    fun flush() {
        runCatching { manager?.flush(this) }
    }

    fun stop() {
        runCatching { manager?.unregisterListener(this) }
        thread?.quitSafely()
        thread = null
        sensor = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        // Sensor timestamps share the elapsed clock on current phones; a vendor that differs is replaced by "now".
        val stamped = event.timestamp / 1_000_000
        val t = if (abs(now - stamped) <= 5 * 60_000L) stamped else now
        accumulator.addMotion(t, event.values[0], event.values[1], event.values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        fun available(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }
}
