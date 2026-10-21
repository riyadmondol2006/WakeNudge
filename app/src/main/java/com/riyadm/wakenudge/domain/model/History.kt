package com.riyadm.wakenudge.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class AlarmOutcome {
    /** Currently ringing or waiting for the user. */
    RINGING,
    DISMISSED,
    /** Ignored until the ring timeout; the backup alarm (if any) takes over. */
    MISSED,
    EMERGENCY_STOPPED,
}

@Serializable
enum class WakeCheckStatus { NONE, PENDING, PASSED, FAILED, CANCELLED }

/** One occurrence of an alarm going off, from the first ring to its final dismissal. */
@Serializable
data class AlarmEvent(
    val id: Long = 0,
    val alarmId: Long,
    val label: String,
    /** The wall-clock moment the alarm was supposed to ring (epoch millis). */
    val scheduledAt: Long,
    val firedAt: Long,
    val endedAt: Long? = null,
    val outcome: AlarmOutcome = AlarmOutcome.RINGING,
    val snoozeCount: Int = 0,
    val missionsDone: Int = 0,
    val missionsTotal: Int = 0,
    val backupFired: Boolean = false,
    val wakeCheck: WakeCheckStatus = WakeCheckStatus.NONE,
    val wakeCheckReRings: Int = 0,
    /** When a pending snooze ends (epoch millis), or 0. Persisted so a reboot cannot lose a snoozed alarm. */
    val snoozeUntil: Long = 0,
    /** Epoch millis of backup alarms that have not fired yet, kept so they can be re-armed after a reboot. */
    val pendingBackups: List<Long> = emptyList(),
) {
    /** Milliseconds between the first ring and the final dismissal (snooze time included). */
    val dismissDurationMs: Long? get() = endedAt?.let { (it - firedAt).coerceAtLeast(0) }

    /** Lateness of the first ring relative to the schedule. */
    val ringDelayMs: Long get() = (firedAt - scheduledAt).coerceAtLeast(0)

    /** A wake-up counts as successful when the user really dismissed it and any wake-up check was passed. */
    val isSuccessfulWakeUp: Boolean
        get() = outcome == AlarmOutcome.DISMISSED &&
            wakeCheck != WakeCheckStatus.FAILED
}
