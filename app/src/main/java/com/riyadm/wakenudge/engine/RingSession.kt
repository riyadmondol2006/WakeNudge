package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.scheduling.SnoozePolicy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
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

@Serializable
enum class RingPhase {
    /** Pre-alarm screen glow: silent. */
    SUNRISE,
    RINGING,
}

/**
 * Which ring a button was shown for. A tap that arrives after another ring took over (an alarm firing over a ringing
 * timer, a queued alarm starting after a dismissal, a lagging watch, a notification left from yesterday's ring of the
 * same alarm) must not end that other ring: it would skip its missions, cancel its backups and, for a delete-after-use
 * alarm, delete it.
 */
data class RingTarget(val alarmId: Long, val timerId: Long = 0, val eventId: Long = ANY_EVENT) {
    fun matches(session: RingSession): Boolean =
        session.alarm.id == alarmId && session.timerId == timerId && (eventId == ANY_EVENT || eventId == session.eventId)

    companion object {
        /** For a caller that was not shown one particular ring (tests, internal callers). */
        const val ANY_EVENT = Long.MIN_VALUE

        fun of(session: RingSession) = RingTarget(session.alarm.id, session.timerId, session.eventId)
    }
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
    /** Set after 40 s without interaction, or immediately for a backup alarm or a ring back from a restart. */
    val intensified: Boolean = false,
    /** The user pressed a volume key to quiet the alarm for a moment. On the [com.riyadm.wakenudge.core.TimeSource.elapsedMillis] clock. */
    val silencedUntil: Long = 0,
    /** On the [com.riyadm.wakenudge.core.TimeSource.elapsedMillis] clock, so changing the time cannot time out a ring. */
    val lastInteractionAt: Long = startedAt,
    val missionsDone: Int = 0,
    val timerId: Long = 0,
    /** The ring screen crashed repeatedly: the notification carries the controls instead of launching the screen. */
    val safeMode: Boolean = false,
    val mutesUsed: Int = 0,
    /** A backup mission stood in for one this phone could not do. */
    val fallback: Boolean = false,
    val wayOutProgress: Int = 0,
    val pauses: Int = 0,
    /** Times this ring has come back after the phone restarted. */
    val resumes: Int = 0,
    val pullBacks: Int = 0,
) {
    val isTimer: Boolean get() = kind == RingKind.TIMER
    val isTest: Boolean get() = kind == RingKind.TEST
    val hasMissions: Boolean get() = !isTimer && alarm.missions.isNotEmpty()

    /** A ring that came back from a restart has lost its snooze; restarting the phone must not be a way to get another. */
    val canSnooze: Boolean get() = !isTimer && resumes == 0 && SnoozePolicy.canSnooze(alarm.snooze, snoozeCount)

    /**
     * The alarm as the sound engine should play it: after a restart, the whole stream at once, whatever the alarm is set to.
     * Only a headset's hearing cap still applies. [alarm] itself stays as it is, since that is what gets saved and restored.
     */
    val soundAlarm: Alarm
        get() = if (resumes > 0) alarm.copy(volume = alarm.volume.copy(rampSeconds = 0, useMaxSystemVolume = true)) else alarm

    /** The request that would start this very session again, e.g. after the service had to be restarted. */
    fun toRequest() = RingRequest(alarm.id, eventId, scheduledAt, kind, snoozeCount, phase, timerId)
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
@Serializable
data class RingRequest(
    val alarmId: Long,
    val eventId: Long,
    val scheduledAt: Long,
    val kind: RingKind,
    val snoozeCount: Int = 0,
    val phase: RingPhase = RingPhase.RINGING,
    val timerId: Long = 0,
    /** How many times starting the service for this ring has already been retried (see [RingRetry]). */
    val attempt: Int = 0,
)
