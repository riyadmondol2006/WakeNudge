package com.riyadm.wakenudge.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TimerState { IDLE, RUNNING, PAUSED, RINGING }

@Serializable
data class CountdownTimer(
    val id: Long = 0,
    val label: String = "",
    val durationMs: Long,
    val state: TimerState = TimerState.IDLE,
    /** Wall-clock end time while [TimerState.RUNNING] (epoch millis). */
    val endsAtMillis: Long = 0,
    /** Remaining time while [TimerState.PAUSED] or [TimerState.IDLE]. */
    val remainingMs: Long = durationMs,
    val createdAt: Long = 0,
) {
    fun remainingAt(nowMillis: Long): Long = when (state) {
        TimerState.RUNNING -> (endsAtMillis - nowMillis).coerceAtLeast(0)
        TimerState.RINGING -> 0
        else -> remainingMs
    }
}
