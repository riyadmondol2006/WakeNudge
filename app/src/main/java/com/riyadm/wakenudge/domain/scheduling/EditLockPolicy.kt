package com.riyadm.wakenudge.domain.scheduling

import com.riyadm.wakenudge.domain.model.Alarm

/**
 * Why an alarm may not be changed right now. [minutes] is what is left of the window before the alarm rings (0 while
 * it rings or is snoozed, when the lock lasts until the ring is over).
 */
data class EditLock(val minutes: Int, val whileRinging: Boolean) {
    /** Nothing lifts the lock while the alarm rings or is snoozed: the ring itself is the way to end it. */
    val canOverride: Boolean get() = !whileRinging
}

/**
 * Last-minute edit protection: while an alarm is about to ring, or rings or is snoozed, it cannot be switched off,
 * skipped, edited, deleted or paused from the app, a widget, the tile or a notification. Always reversible outside a
 * ring: anything can be changed outside the window, a just-saved alarm stays editable for [GRACE_MS], and the lock can
 * be lifted before the ring by proving the user is awake.
 */
object EditLockPolicy {

    /** A just-saved alarm stays editable this long, so saving one inside its own window does not lock a typo in. */
    const val GRACE_MS = 10 * 60_000L

    /** The longest window, 12 hours, so it can cover the night before. */
    const val MAX_WINDOW_MINUTES = 720

    /**
     * The lock on [alarm], or null when it is editable. A ring or snooze locks an alarm with a mission even when its
     * window is 0: otherwise deleting or switching off a snoozed alarm would end it without the mission.
     */
    fun lockOf(alarm: Alarm, nextTriggerAt: Long?, nowMillis: Long, activeOrSnoozed: Boolean): EditLock? {
        val window = alarm.antiOversleep.editLockMinutes.coerceIn(0, MAX_WINDOW_MINUTES)
        if (activeOrSnoozed) return if (window > 0 || alarm.missions.isNotEmpty()) EditLock(0, whileRinging = true) else null
        if (window <= 0 || !alarm.enabled) return null
        val sinceSave = nowMillis - alarm.updatedAt
        if (alarm.updatedAt > 0 && sinceSave in 0 until GRACE_MS) return null
        val at = nextTriggerAt ?: return null
        val remainingMs = at - nowMillis
        if (remainingMs < 0 || remainingMs > window * 60_000L) return null
        return EditLock(((remainingMs + 59_999) / 60_000).toInt().coerceAtLeast(1), whileRinging = false)
    }

    /** Minutes of lock left, or null when the alarm is editable (0 while it rings or is snoozed). */
    fun lockedMinutes(alarm: Alarm, nextTriggerAt: Long?, nowMillis: Long, activeOrSnoozed: Boolean): Int? =
        lockOf(alarm, nextTriggerAt, nowMillis, activeOrSnoozed)?.minutes
}
