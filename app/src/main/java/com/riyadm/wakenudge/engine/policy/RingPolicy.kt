package com.riyadm.wakenudge.engine.policy

import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingSession

/** Everything that can end a ring or quiet it. */
enum class RingCommand { Snooze, Dismiss, HoldStop, WayOutDone, Timeout, KeySilence, MissionMute, EmergencyPause }

/**
 * What the caller can show for itself. Only the ring screen can show more than [None]: a notification action, a key or a
 * button drawn for an earlier ring has nothing else to offer. The controller's own count is what backs
 * [MissionsComplete], so a caller claiming it does not make it true.
 */
enum class RingProof { None, Gesture, Hold, MissionsComplete, WayOutComplete, SafeSumsComplete }

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
    NoWayOut,
    WayOutNotOpen,
    NeedsSafeSums,
}

/**
 * The way to end a ring without its mission, worked out from the alarm's settings. An alarm with no mission has none: it
 * ends with a slide or a hold already, so a second control there would have no purpose.
 */
sealed interface WayOut {
    /** No way out: no mission to skip, or the user removed it. Emergency and the backup mission still work, and the reboot cap. */
    data object None : WayOut

    /** Hold a button for [seconds]. */
    data class Hold(val seconds: Int) : WayOut

    /** After [delayMinutes] of ringing, [steps] tasks of the mixed chain. */
    data class Chain(val delayMinutes: Int, val steps: Int) : WayOut
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
            RingCommand.WayOutDone -> wayOutDone(session, proof)
            RingCommand.Timeout -> timeout(session)
            RingCommand.KeySilence -> RingDecision.Allow
            RingCommand.MissionMute -> mute(session, proof)
            RingCommand.EmergencyPause -> if (proof == RingProof.Gesture) RingDecision.Allow else deny(DenyReason.NeedsGesture)
        }
    }

    /** How long the way-out hold takes. Long enough not to become a habit; the old 3 s setting is not honoured below this. */
    const val WAY_OUT_HOLD_SECONDS = 10

    /** How long one Emergency pause lasts. */
    const val EMERGENCY_PAUSE_MS = 3 * 60_000L

    /** Pauses in one ring that also silence it. From the next one on, only the pull-back stops, unless a call is active. */
    const val SILENT_PAUSES = 2

    fun wayOut(s: RingSession): WayOut {
        val ao = s.alarm.antiOversleep
        return when {
            s.isTimer || s.alarm.missions.isEmpty() || ao.wayOutRemoved -> WayOut.None
            ao.emergencyStopEnabled -> WayOut.Hold(WAY_OUT_HOLD_SECONDS)
            else -> WayOut.Chain(ao.wayOutDelayMinutes.coerceIn(0, MAX_WAY_OUT_DELAY_MINUTES), wayOutSteps(ao))
        }
    }

    /** How many tasks the way-out chain asks for. Always some, never endless, whatever a restored file says. */
    fun wayOutSteps(ao: AntiOversleepSettings): Int = ao.wayOutCount.coerceIn(MIN_WAY_OUT_STEPS, MAX_WAY_OUT_STEPS)

    /** When the way-out chain can be started, on the wall clock (counted from when the ring first started); null without one. */
    fun wayOutOpensAt(s: RingSession): Long? = (wayOut(s) as? WayOut.Chain)?.let { s.startedAt + it.delayMinutes * 60_000L }

    /** How many plain sums the safe ring screen asks for, when the alarm does not end with one tap. */
    fun safeSums(s: RingSession): Int = if (s.alarm.dismissesWithOneTap) 0 else s.alarm.antiOversleep.fallbackCount.coerceIn(1, MAX_SAFE_SUMS)

    const val MIN_WAY_OUT_STEPS = 3
    const val MAX_WAY_OUT_STEPS = 30
    const val MAX_WAY_OUT_DELAY_MINUTES = 30
    const val MAX_SAFE_SUMS = 20

    private fun snooze(s: RingSession, proof: RingProof): RingDecision {
        if (!s.canSnooze) return deny(DenyReason.SnoozeUnavailable)
        return when {
            // The safe screen is plain and has no missions: its own button is the gesture. A notification snoozes only an
            // alarm with no mission at all, as everywhere else.
            s.safeMode -> if (s.alarm.missions.isEmpty() && s.alarm.snoozeMissions.isEmpty()) RingDecision.Allow else requires(proof == RingProof.Gesture, DenyReason.NeedsGesture)
            s.alarm.snoozeMissions.isNotEmpty() -> requires(proof == RingProof.MissionsComplete, DenyReason.NeedsMissions)
            // A snooze on the ring screen is still a choice someone looking at it makes; nothing away from the screen is.
            s.alarm.missions.isNotEmpty() -> requires(proof == RingProof.Gesture, DenyReason.NeedsGesture)
            else -> RingDecision.Allow
        }
    }

    private fun dismiss(s: RingSession, proof: RingProof): RingDecision = when {
        s.isTimer -> RingDecision.Allow
        // The ring screen kept crashing: the safe screen's sums stand in for whatever the alarm asks, and only an alarm that
        // ends with one tap anyway ends from its notification.
        s.safeMode && s.alarm.dismissesWithOneTap -> RingDecision.Allow
        s.safeMode -> requires(proof == RingProof.SafeSumsComplete && s.safeSumsDone >= safeSums(s), DenyReason.NeedsSafeSums)
        s.alarm.missions.isNotEmpty() ->
            requires(proof == RingProof.MissionsComplete && s.missionsDone >= s.alarm.missions.size, DenyReason.NeedsMissions)
        s.alarm.antiOversleep.holdToDismissSeconds > 0 -> requires(proof == RingProof.Hold, DenyReason.NeedsHold)
        else -> RingDecision.Allow
    }

    private fun holdStop(s: RingSession, proof: RingProof): RingDecision = when (wayOut(s)) {
        is WayOut.Hold -> requires(proof == RingProof.Hold, DenyReason.NeedsHold)
        is WayOut.Chain -> deny(DenyReason.EmergencyStopOff)
        WayOut.None -> deny(DenyReason.NoWayOut)
    }

    /** The controller counts the chain itself, and only once it has opened, so the count is what backs the proof. */
    private fun wayOutDone(s: RingSession, proof: RingProof): RingDecision = when (val w = wayOut(s)) {
        is WayOut.Chain -> requires(proof == RingProof.WayOutComplete && s.wayOutProgress >= w.steps, DenyReason.NeedsWayOut)
        else -> deny(DenyReason.NoWayOut)
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
