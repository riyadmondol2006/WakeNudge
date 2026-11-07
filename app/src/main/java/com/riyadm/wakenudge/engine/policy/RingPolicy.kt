package com.riyadm.wakenudge.engine.policy

import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.scheduling.SnoozePolicy
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingSession

/** Everything that can end a ring or quiet it. */
enum class RingCommand { Snooze, Dismiss, HoldStop, WayOutDone, Timeout, KeySilence, MissionMute, EmergencyPause }

/**
 * What the caller can show for itself. Only the ring screen can show more than [None]: a notification action, a key or a
 * button drawn for an earlier ring has nothing else to offer. The controller's own count is what backs
 * [MissionsComplete], so a caller claiming it does not make it true.
 */
enum class RingProof { None, Gesture, Hold, MissionsComplete, WayOutComplete }

enum class DenyReason {
    NotRinging,
    SnoozeUnavailable,
    NeedsMissions,
    NeedsHold,
    NeedsGesture,
    NeedsWayOut,
    EmergencyStopOff,
    NoTimeout,
    NoMutesLeft,
    NotOnAMission,
}

sealed interface RingDecision {
    data object Allow : RingDecision
    data class Deny(val reason: DenyReason) : RingDecision
}

/**
 * The one place that decides whether a command may end or quiet a ring. It is a pure function of the session, so a
 * notification, a stale button or a bug in a caller cannot get past what the alarm asks for.
 *
 * It only reads concrete settings, never a level. Where a command is allowed but something else can still go wrong (the
 * OS refusing a snooze, for instance) the controller handles that around this decision.
 */
object RingPolicy {

    fun decide(session: RingSession, command: RingCommand, proof: RingProof): RingDecision {
        if (session.phase != RingPhase.RINGING) return deny(DenyReason.NotRinging)
        return when (command) {
            RingCommand.Snooze -> snooze(session, proof)
            RingCommand.Dismiss -> dismiss(session, proof)
            RingCommand.HoldStop -> holdStop(session, proof)
            RingCommand.WayOutDone -> if (proof == RingProof.WayOutComplete) RingDecision.Allow else deny(DenyReason.NeedsWayOut)
            RingCommand.Timeout -> timeout(session)
            RingCommand.KeySilence -> RingDecision.Allow
            RingCommand.MissionMute -> mute(session, proof)
            RingCommand.EmergencyPause -> if (proof == RingProof.Gesture) RingDecision.Allow else deny(DenyReason.NeedsGesture)
        }
    }

    private fun snooze(s: RingSession, proof: RingProof): RingDecision {
        if (s.isTimer || !SnoozePolicy.canSnooze(s.alarm.snooze, s.snoozeCount)) return deny(DenyReason.SnoozeUnavailable)
        return when {
            // The ring screen would not launch itself any more: its notification carries the controls.
            s.safeMode -> RingDecision.Allow
            s.alarm.snoozeMissions.isNotEmpty() -> requires(proof == RingProof.MissionsComplete, DenyReason.NeedsMissions)
            // A snooze on the ring screen is still a choice someone looking at it makes; nothing away from the screen is.
            s.alarm.missions.isNotEmpty() -> requires(proof == RingProof.Gesture, DenyReason.NeedsGesture)
            else -> RingDecision.Allow
        }
    }

    private fun dismiss(s: RingSession, proof: RingProof): RingDecision = when {
        s.isTimer -> RingDecision.Allow
        s.safeMode -> RingDecision.Allow
        s.alarm.missions.isNotEmpty() ->
            requires(proof == RingProof.MissionsComplete && s.missionsDone >= s.alarm.missions.size, DenyReason.NeedsMissions)
        s.alarm.antiOversleep.holdToDismissSeconds > 0 -> requires(proof == RingProof.Hold, DenyReason.NeedsHold)
        else -> RingDecision.Allow
    }

    private fun holdStop(s: RingSession, proof: RingProof): RingDecision = when {
        !s.alarm.antiOversleep.emergencyStopEnabled -> deny(DenyReason.EmergencyStopOff)
        else -> requires(proof == RingProof.Hold, DenyReason.NeedsHold)
    }

    private fun timeout(s: RingSession): RingDecision =
        requires(s.alarm.antiOversleep.ringTimeoutMinutes != AntiOversleepSettings.RING_TIMEOUT_NEVER, DenyReason.NoTimeout)

    private fun mute(s: RingSession, proof: RingProof): RingDecision = when {
        !s.hasMissions -> deny(DenyReason.NotOnAMission)
        s.mutesUsed >= s.alarm.antiOversleep.muteBudget -> deny(DenyReason.NoMutesLeft)
        else -> requires(proof == RingProof.Gesture, DenyReason.NeedsGesture)
    }

    private fun requires(condition: Boolean, otherwise: DenyReason): RingDecision =
        if (condition) RingDecision.Allow else deny(otherwise)

    private fun deny(reason: DenyReason): RingDecision = RingDecision.Deny(reason)
}
