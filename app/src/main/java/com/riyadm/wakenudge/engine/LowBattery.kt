package com.riyadm.wakenudge.engine

import android.content.Context
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** How full the battery is (0..100) and whether a charger is filling it. */
data class BatteryReading(val percent: Int, val charging: Boolean)

/** Reads the battery; an interface so the warning logic can be tested without a phone. */
fun interface BatteryGauge {
    /** Null when the phone will not say. */
    fun read(): BatteryReading?
}

@Singleton
class SystemBatteryGauge @Inject constructor(@ApplicationContext private val context: Context) : BatteryGauge {
    override fun read(): BatteryReading? = try {
        val manager = context.getSystemService(BatteryManager::class.java)
        // Integer.MIN_VALUE (or another out-of-range value) means the property is not supported.
        val percent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (manager == null || percent == null || percent !in 0..100) null else BatteryReading(percent, manager.isCharging)
    } catch (_: RuntimeException) {
        null
    }
}

/**
 * A phone that runs flat in the night cannot ring in the morning, and nothing an alarm app does afterwards helps. So
 * some hours before the next alarm the battery is looked at once, and if it is low and nothing is charging it, the
 * user hears about it while they can still plug the phone in.
 */
object LowBatteryPolicy {
    /** At or below this charge, and not charging, the phone may not last the night. */
    const val LOW_PERCENT = 20

    /** How long before the alarm to look: around bedtime for a morning alarm. */
    const val LEAD_MS = 8 * 3_600_000L

    /** Closer to the alarm than this, the alarm rings before a low battery matters, and a notice would only disturb. */
    const val MIN_AHEAD_MS = 30 * 60_000L

    /** Further away than this there is a whole day to charge; the check is about getting through tonight. */
    const val MAX_AHEAD_MS = 14 * 3_600_000L

    /** When to look at the battery for an alarm at [nextAlarmAt], or null when there is nothing worth checking for. */
    fun checkAt(nextAlarmAt: Long?, nowMs: Long): Long? {
        if (nextAlarmAt == null || nextAlarmAt - nowMs < MIN_AHEAD_MS) return null
        // An alarm set less than eight hours ahead is checked straight away: its owner is awake and holding the phone.
        return maxOf(nextAlarmAt - LEAD_MS, nowMs + 60_000L)
    }

    fun shouldWarn(battery: BatteryReading?, nextAlarmAt: Long?, nowMs: Long): Boolean {
        if (battery == null || battery.charging || battery.percent > LOW_PERCENT || nextAlarmAt == null) return false
        return nextAlarmAt - nowMs in MIN_AHEAD_MS..MAX_AHEAD_MS
    }
}
