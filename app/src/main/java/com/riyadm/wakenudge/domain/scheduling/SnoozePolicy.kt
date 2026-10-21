package com.riyadm.wakenudge.domain.scheduling

import com.riyadm.wakenudge.domain.model.SnoozeSettings

/** Smart-snooze rules: a cap on snoozes and optionally shrinking intervals. */
object SnoozePolicy {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 60

    /** [snoozesSoFar] is how many times this occurrence has already been snoozed. */
    fun canSnooze(settings: SnoozeSettings, snoozesSoFar: Int): Boolean =
        settings.enabled && (settings.maxCount <= 0 || snoozesSoFar < settings.maxCount)

    /** Minutes to wait for the snooze that follows [snoozesSoFar] earlier ones. */
    fun nextIntervalMinutes(settings: SnoozeSettings, snoozesSoFar: Int): Int {
        val base = settings.minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        if (!settings.decreasing) return base
        val step = settings.decreaseStepMinutes.coerceAtLeast(0)
        return (base - step * snoozesSoFar).coerceAtLeast(MIN_MINUTES)
    }

    fun remaining(settings: SnoozeSettings, snoozesSoFar: Int): Int? =
        if (settings.maxCount <= 0) null else (settings.maxCount - snoozesSoFar).coerceAtLeast(0)
}
