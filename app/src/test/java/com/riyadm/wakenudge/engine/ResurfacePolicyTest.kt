package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.engine.policy.ResurfaceAction
import com.riyadm.wakenudge.engine.policy.ResurfaceAction.ALERT
import com.riyadm.wakenudge.engine.policy.ResurfaceAction.NONE
import com.riyadm.wakenudge.engine.policy.ResurfaceAction.PULL_BACK
import com.riyadm.wakenudge.engine.policy.ResurfaceAction.WAKE
import com.riyadm.wakenudge.engine.policy.ResurfaceInput
import com.riyadm.wakenudge.engine.policy.ResurfacePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the ring screen comes back, second by second on a fake clock: the D9 and D10 table. */
class ResurfacePolicyTest {
    private var now = 1_000_000L
    private val policy = ResurfacePolicy()

    private fun input(
        inFront: Boolean = false, screenOn: Boolean = true, locked: Boolean = false, overlays: Boolean = true,
        force: Boolean = true, back: Boolean = true, paused: Boolean = false, call: Boolean = false, dialog: Boolean = false, ringing: Boolean = true,
    ) = ResurfaceInput(now, ringing, back, force, inFront, screenOn, locked, overlays, paused, call, dialog)

    /** Runs the policy once a second for [seconds] with [state] and returns when each action happened (seconds from the start). */
    private fun run(seconds: Int, state: () -> ResurfaceInput): List<Pair<Int, ResurfaceAction>> {
        val start = now
        val out = mutableListOf<Pair<Int, ResurfaceAction>>()
        repeat(seconds + 1) {
            val action = policy.decide(state())
            if (action != NONE) out += ((now - start) / 1000).toInt() to action
            now += 1_000
        }
        return out
    }

    private fun onScreen(seconds: Int) = run(seconds) { input(inFront = true) }

    @Test fun `in front, nothing happens`() {
        assertEquals(emptyList<Pair<Int, ResurfaceAction>>(), onScreen(120))
    }

    @Test fun `left while unlocked, it comes back after 1 s, three times quickly, then backs off`() {
        onScreen(5)
        val times = run(60) { input() }.filter { it.second == PULL_BACK }.map { it.first }
        // 1, 1, 1 then 5, then 10 s apart: each pull-back is followed by the user leaving again at once.
        assertEquals(listOf(1, 2, 3, 8, 18, 28, 38, 48, 58), times)
    }

    @Test fun `a minute on the ring screen starts the backoff over`() {
        onScreen(1)
        run(30) { input() }
        assertTrue(policy.pullBackStreak > 3)
        onScreen(59)
        assertTrue("not yet", policy.pullBackStreak > 3)
        onScreen(2)
        assertEquals(0, policy.pullBackStreak)
        assertEquals(1, run(1) { input() }.single { it.second == PULL_BACK }.first)
    }

    @Test fun `a ring that came back with no screen at all is pulled back too`() {
        assertEquals(1 to PULL_BACK, run(3) { input() }.first())
    }

    @Test fun `without the overlay permission, or not set to force, it only alerts every 20 s`() {
        for (state in listOf(input(overlays = false), input(force = false))) {
            val policy = ResurfacePolicy()
            var t = 0
            val actions = mutableListOf<Pair<Int, ResurfaceAction>>()
            while (t <= 65) {
                val a = policy.decide(state.copy(now = now + t * 1000L))
                if (a != NONE) actions += t to a
                t++
            }
            assertEquals(listOf(20 to ALERT, 40 to ALERT, 60 to ALERT), actions)
        }
    }

    @Test fun `an alarm that does not want its screen back is left alone`() {
        assertEquals(emptyList<Pair<Int, ResurfaceAction>>(), run(120) { input(back = false) })
        assertEquals(emptyList<Pair<Int, ResurfaceAction>>(), run(120) { input(ringing = false) })
    }

    @Test fun `locked, it never launches anything over the lock screen, it only alerts`() {
        val actions = run(65) { input(locked = true) }
        assertTrue(actions.none { it.second == PULL_BACK || it.second == WAKE })
        assertEquals(listOf(20, 40, 60), actions.map { it.first })
    }

    @Test fun `nothing covers a call, an Emergency pause or a mission's permission dialog`() {
        for (state in listOf(input(call = true), input(paused = true), input(dialog = true), input(call = true, screenOn = false), input(paused = true, screenOn = false))) {
            val policy = ResurfacePolicy()
            for (t in 0..300) assertEquals("$state at $t s", NONE, policy.decide(state.copy(now = now + t * 1000L)))
        }
    }

    @Test fun `after the pause or the call it comes back`() {
        run(30) { input(paused = true) }
        assertEquals(PULL_BACK, run(2) { input() }.first().second)
    }

    @Test fun `screen off with the power button, lit again after 3 s, then every 10 s while it stays off`() {
        onScreen(3)
        policy.onScreenOff(now)
        val actions = run(25) { input(screenOn = false) }
        assertEquals(listOf(3 to WAKE, 13 to WAKE, 23 to WAKE), actions)
    }

    @Test fun `three screen-offs within 6 s look like an SOS, so nothing for a minute`() {
        onScreen(1)
        repeat(3) {
            policy.onScreenOff(now)
            now += 1_500
        }
        val actions = run(70) { input(screenOn = false) }
        assertTrue("nothing during the hold-off: $actions", actions.all { it.first >= 57 })
        assertEquals(WAKE, actions.first().second)
    }

    @Test fun `two presses, a double-press for the camera, are not an SOS`() {
        onScreen(1)
        policy.onScreenOff(now)
        now += 500
        policy.onScreenOff(now)
        assertEquals(3 to WAKE, run(4) { input(screenOn = false) }.first())
    }

    @Test fun `split screen or a dialog over the screen count as left`() {
        // The service passes "resumed and not in multi-window" as in front; anything else is left.
        assertEquals(1 to PULL_BACK, run(2) { input(inFront = false) }.first())
    }
}
