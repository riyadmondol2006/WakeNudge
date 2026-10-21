package com.riyadm.wakenudge.domain.scheduling

import com.riyadm.wakenudge.domain.model.Alarm

/**
 * Optional "last-minute edit protection": while an alarm is about to ring (or is ringing/snoozed) it cannot be
 * switched off, edited or deleted from the app, a widget or a notification. Always reversible: the user can turn the
 * protection off in the alarm's settings (when not locked), or override it by solving a short puzzle.
 */
object EditLockPolicy {

    /** Minutes of lock left, or null when the alarm is editable. */
    fun lockedMinutes(alarm: Alarm, nextTriggerAt: Long?, nowMillis: Long, activeOrSnoozed: Boolean): Int? {
        val window = alarm.antiOversleep.editLockMinutes
        if (window <= 0 || !alarm.enabled && !activeOrSnoozed) return null
        if (activeOrSnoozed) return window
        val at = nextTriggerAt ?: return null
        val remainingMs = at - nowMillis
        if (remainingMs < 0 || remainingMs > window * 60_000L) return null
        return ((remainingMs + 59_999) / 60_000).toInt().coerceAtLeast(1)
    }
}
