package com.riyadm.wakenudge.engine

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A call keeps the alarm quiet; a call in an app only for so long, or it would be a way to silence any alarm. */
class CallPauseTest {
    @Test fun `audio modes map to what they mean for an alarm`() {
        assertEquals(CallMode.NONE, CallMode.of(AudioManager.MODE_NORMAL))
        assertEquals(CallMode.RINGING, CallMode.of(AudioManager.MODE_RINGTONE))
        assertEquals(CallMode.CALL, CallMode.of(AudioManager.MODE_IN_CALL))
        assertEquals(CallMode.COMMUNICATION, CallMode.of(AudioManager.MODE_IN_COMMUNICATION))
        assertEquals(CallMode.CALL, CallMode.of(4))
        assertEquals(CallMode.CALL, CallMode.of(5))
        assertEquals(CallMode.COMMUNICATION, CallMode.of(6))
        assertEquals(CallMode.NONE, CallMode.of(-2))
    }

    @Test fun `a phone call keeps the alarm quiet for as long as it lasts`() {
        val pause = CallPause().also { it.reset(1) }
        var t = 0L
        while (t < 3 * 3_600_000L) {
            assertTrue(pause.quiet(CallMode.CALL, t))
            t += 1_000
        }
        assertFalse(pause.quiet(CallMode.NONE, t))
        assertTrue("a call ringing in is never covered", pause.quiet(CallMode.RINGING, t + 1_000))
    }

    @Test fun `a call in an app quiets one ring for at most ten minutes in all`() {
        val pause = CallPause().also { it.reset(1) }
        var t = 0L
        // Six minutes, a break, then more: the budget is for the whole ring, not for each call.
        while (t <= 6 * 60_000L) { assertTrue(pause.quiet(CallMode.COMMUNICATION, t)); t += 1_000 }
        assertFalse(pause.quiet(CallMode.NONE, t)); t += 60_000
        var quietMs = 0L
        while (t <= 30 * 60_000L) {
            if (pause.quiet(CallMode.COMMUNICATION, t)) quietMs += 1_000
            t += 1_000
        }
        assertTrue("about four minutes left, was $quietMs", quietMs in 3 * 60_000L..4 * 60_000L + 2_000)
        assertFalse(pause.isCallActive(CallMode.COMMUNICATION))
        assertTrue("a phone call still counts", pause.isCallActive(CallMode.CALL))
        pause.reset(2)
        assertTrue("the next ring has the whole budget", pause.quiet(CallMode.COMMUNICATION, t))
    }

    @Test fun `a long gap between looks is not counted as call time`() {
        val pause = CallPause().also { it.reset(1) }
        assertTrue(pause.quiet(CallMode.COMMUNICATION, 0))
        assertTrue(pause.quiet(CallMode.COMMUNICATION, 3_600_000))
    }
}
