package com.riyadm.wakenudge.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class WorldClockCity(
    val zoneId: String,
    val label: String,
    val sortOrder: Int = 0,
)

@Serializable
data class ExceptionDate(
    val epochDay: Long,
    val label: String = "",
)

/** Persistent state of the "Still awake?" follow-up for an alarm that was just dismissed. */
@Serializable
data class WakeCheck(
    val alarmId: Long,
    val eventId: Long,
    val phase: Phase,
    /** When the prompt is (or was) due, epoch millis. */
    val promptAt: Long,
    /** When an unanswered prompt re-rings the alarm, epoch millis. 0 while [Phase.WAITING]. */
    val deadlineAt: Long = 0,
    val checksDone: Int = 0,
    val reRings: Int = 0,
) {
    @Serializable
    enum class Phase { WAITING, PROMPTED }
}
