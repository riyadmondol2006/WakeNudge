package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.engine.policy.RingCommand
import com.riyadm.wakenudge.engine.policy.RingCommand.Dismiss
import com.riyadm.wakenudge.engine.policy.RingCommand.EmergencyPause
import com.riyadm.wakenudge.engine.policy.RingCommand.HoldStop
import com.riyadm.wakenudge.engine.policy.RingCommand.KeySilence
import com.riyadm.wakenudge.engine.policy.RingCommand.MissionMute
import com.riyadm.wakenudge.engine.policy.RingCommand.Snooze
import com.riyadm.wakenudge.engine.policy.RingCommand.Timeout
import com.riyadm.wakenudge.engine.policy.RingCommand.WayOutDone
import com.riyadm.wakenudge.engine.policy.RingDecision
import com.riyadm.wakenudge.engine.policy.RingPolicy
import com.riyadm.wakenudge.engine.policy.RingProof
import com.riyadm.wakenudge.engine.policy.RingProof.Gesture
import com.riyadm.wakenudge.engine.policy.RingProof.Hold
import com.riyadm.wakenudge.engine.policy.RingProof.MissionsComplete
import com.riyadm.wakenudge.engine.policy.RingProof.None
import com.riyadm.wakenudge.engine.policy.RingProof.WayOutComplete
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every command with every proof on every shape of ring. The table is the specification: a new command or proof fails here. */
class RingPolicyTest {
    private val math = listOf(MissionConfig(MissionType.MATH), MissionConfig(MissionType.TYPING))

    private fun session(
        alarm: Alarm = Alarm(id = 1),
        kind: RingKind = RingKind.PRIMARY,
        phase: RingPhase = RingPhase.RINGING,
        missionsDone: Int = 0,
        safeMode: Boolean = false,
        snoozeCount: Int = 0,
        mutesUsed: Int = 0,
        resumes: Int = 0,
    ) = RingSession(
        alarm, kind, phase, eventId = 5, scheduledAt = 1, startedAt = 1, snoozeCount = snoozeCount, missionsDone = missionsDone,
        safeMode = safeMode, mutesUsed = mutesUsed, resumes = resumes, timerId = if (kind == RingKind.TIMER) 2 else 0,
    )

    private val all = RingProof.entries.toSet()

    /** For each shape, the proofs each command accepts. A command left out accepts nothing. */
    private val table: Map<String, Pair<RingSession, Map<RingCommand, Set<RingProof>>>> = mapOf(
        "a plain alarm" to (session() to mapOf(
            Snooze to all, Dismiss to all, HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all,
            EmergencyPause to setOf(Gesture),
        )),
        "a hold-to-dismiss alarm" to (session(Alarm(id = 1, antiOversleep = AntiOversleepSettings(holdToDismissSeconds = 5))) to mapOf(
            Snooze to all, Dismiss to setOf(Hold), HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all,
            EmergencyPause to setOf(Gesture),
        )),
        "missions not done" to (session(Alarm(id = 1, missions = math), missionsDone = 1) to mapOf(
            Snooze to setOf(Gesture), HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all,
            MissionMute to setOf(Gesture), EmergencyPause to setOf(Gesture),
        )),
        "missions all done" to (session(Alarm(id = 1, missions = math), missionsDone = 2) to mapOf(
            Snooze to setOf(Gesture), Dismiss to setOf(MissionsComplete), HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all,
            MissionMute to setOf(Gesture), EmergencyPause to setOf(Gesture),
        )),
        "a mission to snooze" to (session(Alarm(id = 1, snoozeMissions = math)) to mapOf(
            Snooze to setOf(MissionsComplete), Dismiss to all, HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all,
            EmergencyPause to setOf(Gesture),
        )),
        "snooze switched off" to (session(Alarm(id = 1, snooze = SnoozeSettings(enabled = false))) to mapOf(
            Dismiss to all, HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all, EmergencyPause to setOf(Gesture),
        )),
        "snoozes used up" to (session(Alarm(id = 1, snooze = SnoozeSettings(maxCount = 2)), snoozeCount = 2) to mapOf(
            Dismiss to all, HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all, EmergencyPause to setOf(Gesture),
        )),
        "a strict alarm" to (
            session(
                Alarm(id = 1, missions = math, snooze = SnoozeSettings(enabled = false), antiOversleep = AntiOversleepSettings(emergencyStopEnabled = false, ringTimeoutMinutes = 0)),
            ) to mapOf(
                WayOutDone to setOf(WayOutComplete), KeySilence to all, MissionMute to setOf(Gesture), EmergencyPause to setOf(Gesture),
            )
            ),
        "mutes used up" to (session(Alarm(id = 1, missions = math, antiOversleep = AntiOversleepSettings(muteBudget = 2)), mutesUsed = 2) to mapOf(
            Snooze to setOf(Gesture), HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all, EmergencyPause to setOf(Gesture),
        )),
        // The one remaining shortcut, until the plain-Views ring screen arrives.
        "safe mode with missions" to (session(Alarm(id = 1, missions = math), safeMode = true) to mapOf(
            Snooze to all, Dismiss to all, HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all,
            MissionMute to setOf(Gesture), EmergencyPause to setOf(Gesture),
        )),
        "back after a restart, nothing else changed" to (session(resumes = 1) to mapOf(
            Dismiss to all, HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all, EmergencyPause to setOf(Gesture),
        )),
        "back after a restart, missions not done" to (session(Alarm(id = 1, missions = math), missionsDone = 1, resumes = 3) to mapOf(
            HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all, MissionMute to setOf(Gesture), EmergencyPause to setOf(Gesture),
        )),
        "back after a restart, missions all done" to (session(Alarm(id = 1, missions = math), missionsDone = 2, resumes = 1) to mapOf(
            Dismiss to setOf(MissionsComplete), HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all,
            MissionMute to setOf(Gesture), EmergencyPause to setOf(Gesture),
        )),
        "back after a restart, with a mission to snooze" to (session(Alarm(id = 1, snoozeMissions = math), resumes = 1) to mapOf(
            Dismiss to all, HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all, EmergencyPause to setOf(Gesture),
        )),
        "back after a restart, in safe mode" to (session(Alarm(id = 1, missions = math), safeMode = true, resumes = 1) to mapOf(
            Dismiss to all, HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all,
            MissionMute to setOf(Gesture), EmergencyPause to setOf(Gesture),
        )),
        "a timer" to (session(Alarm(id = -1001, antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 3)), kind = RingKind.TIMER) to mapOf(
            Dismiss to all, HoldStop to setOf(Hold), WayOutDone to setOf(WayOutComplete), Timeout to all, KeySilence to all, EmergencyPause to setOf(Gesture),
        )),
        "a silent sunrise" to (session(Alarm(id = 1, missions = math), phase = RingPhase.SUNRISE) to emptyMap()),
    )

    @Test fun `every command with every proof on every shape of ring`() {
        for ((name, row) in table) {
            val (shape, allowed) = row
            for (command in RingCommand.entries) for (proof in RingProof.entries) {
                val expected = proof in allowed[command].orEmpty()
                val decision = RingPolicy.decide(shape, command, proof)
                assertEquals("$name: $command with $proof gave $decision", expected, decision is RingDecision.Allow)
            }
        }
    }

    @Test fun `what the session offers as a snooze is what the policy allows`() {
        for ((name, row) in table) {
            val shape = row.first
            if (shape.phase != RingPhase.RINGING) continue
            assertEquals(name, RingPolicy.decide(shape, Snooze, Gesture) is RingDecision.Allow || RingPolicy.decide(shape, Snooze, MissionsComplete) is RingDecision.Allow, shape.canSnooze)
        }
    }

    @Test fun `nothing proves more than the count in the session`() {
        val alarm = Alarm(id = 1, missions = math)
        for (done in 0..1) {
            assertEquals(false, RingPolicy.decide(session(alarm, missionsDone = done), Dismiss, MissionsComplete) is RingDecision.Allow)
        }
        assertEquals(true, RingPolicy.decide(session(alarm, missionsDone = 2), Dismiss, MissionsComplete) is RingDecision.Allow)
    }

    @Test fun `a nonsense stored timeout still counts as a timeout, only zero is Never`() {
        val never = session(Alarm(id = 1, antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 0)))
        val nonsense = session(Alarm(id = 1, antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = -3)))
        assertEquals(false, RingPolicy.decide(never, Timeout, None) is RingDecision.Allow)
        assertEquals(true, RingPolicy.decide(nonsense, Timeout, None) is RingDecision.Allow)
    }
}
