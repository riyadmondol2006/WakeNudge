package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.domain.model.Alarm
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RingKind {
    /** The alarm's scheduled time. */
    PRIMARY,
    /** A snooze period ended. */
    SNOOZE_RETURN,
    /** The backup alarm took over because the primary was ignored. */
    BACKUP,
    /** An unanswered "Still awake?" check rings the alarm again. */
    WAKE_CHECK_RERING,
    /** Alarm Health's test alarm: no history, no follow-ups. */
    TEST,
    /** A countdown timer finished. */
    TIMER,
}

enum class RingPhase {
    /** Pre-alarm screen glow: silent. */
    SUNRISE,
    RINGING,
}

/** Everything the service, the ring screen and the notification need to agree on while an alarm is active. */
data class RingSession(
    val alarm: Alarm,
    val kind: RingKind,
    val phase: RingPhase,
    /** History row id; -1 for test alarms and timers. */
    val eventId: Long,
    val scheduledAt: Long,
    val startedAt: Long,
    val snoozeCount: Int = 0,
    /** Set after 40 s without interaction, or immediately for a backup alarm. */
    val intensified: Boolean = false,
    /** The user pressed a volume key to quiet the alarm for a moment. */
    val silencedUntil: Long = 0,
    val lastInteractionAt: Long = startedAt,
    val missionsDone: Int = 0,
    val timerId: Long = 0,
) {
    val isTimer: Boolean get() = kind == RingKind.TIMER
    val isTest: Boolean get() = kind == RingKind.TEST
    val hasMissions: Boolean get() = !isTimer && alarm.missions.isNotEmpty()
}

/** Process-wide holder of the single active ring session. */
@Singleton
class RingSessionStore @Inject constructor() {
    private val state = MutableStateFlow<RingSession?>(null)
    val session: StateFlow<RingSession?> = state.asStateFlow()

    val current: RingSession? get() = state.value

    fun set(session: RingSession?) {
        state.value = session
    }

    fun update(transform: (RingSession) -> RingSession) {
        state.update { it?.let(transform) }
    }

    /** Whether the ring screen is on top; the service re-surfaces it when the user wanders away. */
    private val uiVisible = MutableStateFlow(false)
    val isUiVisible: StateFlow<Boolean> = uiVisible.asStateFlow()
    fun setUiVisible(visible: Boolean) {
        uiVisible.value = visible
    }
}

/** Intent payload that starts a ring session; kept primitive so it survives process boundaries. */
data class RingRequest(
    val alarmId: Long,
    val eventId: Long,
    val scheduledAt: Long,
    val kind: RingKind,
    val snoozeCount: Int = 0,
    val phase: RingPhase = RingPhase.RINGING,
    val timerId: Long = 0,
)
