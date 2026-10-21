package com.riyadm.wakenudge.engine

import android.app.NotificationManager
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.SoundMode
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.engine.audio.RecoveryBudget
import com.riyadm.wakenudge.engine.audio.SoundResolver
import com.riyadm.wakenudge.engine.notify.RingUiFallback
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions behind the "alarm must still sound" machinery: retry codes, crash policy, budgets, fallbacks. */
class RingRetryTest {
    @Test fun `every ring kind survives the round trip through a trigger index`() {
        for (kind in RingKind.entries) for (attempt in 0..RingRetry.MAX_ATTEMPTS) {
            val index = RingRetry.encode(kind, attempt)
            assertEquals(kind, RingRetry.kindOf(index))
            assertEquals(attempt, RingRetry.attemptOf(index))
        }
    }

    @Test fun `a garbage index still yields a ring instead of an exception`() {
        assertEquals(RingKind.PRIMARY, RingRetry.kindOf(99))
        assertEquals(RingKind.PRIMARY, RingRetry.kindOf(-5))
        assertEquals(0, RingRetry.attemptOf(-5))
    }

    @Test fun `a session knows the request that would start it again`() {
        val alarm = Alarm(id = 9, label = "x")
        val s = RingSession(alarm, RingKind.BACKUP, RingPhase.RINGING, eventId = 4, scheduledAt = 1_000, startedAt = 2_000, snoozeCount = 2)
        assertEquals(RingRequest(9, 4, 1_000, RingKind.BACKUP, snoozeCount = 2, phase = RingPhase.RINGING, timerId = 0), s.toRequest())
    }
}

class CrashRescuePolicyTest {
    private val now = 10_000_000L

    @Test fun `one crash is rescued and the screen is still allowed to open`() {
        val crashes = listOf(now - 1_000)
        assertTrue(CrashRescuePolicy.shouldRescue(crashes, now))
        assertFalse(CrashRescuePolicy.safeMode(crashes, now))
    }

    @Test fun `two crashes in a few minutes put the ring into safe mode`() {
        val crashes = listOf(now - 120_000, now - 1_000)
        assertTrue(CrashRescuePolicy.safeMode(crashes, now))
        assertTrue(CrashRescuePolicy.shouldRescue(crashes, now))
    }

    @Test fun `old crashes are forgotten`() {
        val crashes = listOf(now - CrashRescuePolicy.WINDOW_MS - 1, now - CrashRescuePolicy.WINDOW_MS - 5_000)
        assertTrue(CrashRescuePolicy.recent(crashes, now).isEmpty())
        assertFalse(CrashRescuePolicy.safeMode(crashes, now))
    }

    @Test fun `a crash loop is not rescued for ever`() {
        val storm = List(CrashRescuePolicy.MAX_RESCUES + 1) { now - 1_000L * it }
        assertFalse(CrashRescuePolicy.shouldRescue(storm, now))
        assertTrue(CrashRescuePolicy.shouldRescue(storm.drop(1), now))
    }
}

class RecoveryBudgetTest {
    @Test fun `a few recoveries are fine, a storm of them is not`() {
        val budget = RecoveryBudget(maxRecoveries = 3, windowMs = 10_000)
        assertTrue(budget.tryRecover(0))
        assertTrue(budget.tryRecover(1_000))
        assertTrue(budget.tryRecover(2_000))
        assertFalse("the fourth within the window goes to the beeper", budget.tryRecover(3_000))
    }

    @Test fun `the budget refills as the window passes`() {
        val budget = RecoveryBudget(maxRecoveries = 2, windowMs = 10_000)
        budget.tryRecover(0); budget.tryRecover(1_000)
        assertFalse(budget.tryRecover(2_000))
        assertTrue(budget.tryRecover(20_000))
    }

    @Test fun `a new ring starts with a full budget`() {
        val budget = RecoveryBudget(maxRecoveries = 1, windowMs = 10_000)
        budget.tryRecover(0); budget.tryRecover(1)
        budget.reset()
        assertTrue(budget.tryRecover(2))
    }
}

class PlaylistFallbackTest {
    private val playlist = listOf("content://a", "content://b", "content://c")
    private val settings = SoundSettings(mode = SoundMode.RANDOM_CUSTOM, playlist = playlist)

    @Test fun `every track is offered once and the one that just played comes last`() {
        repeat(30) { n ->
            val order = SoundResolver.playlistOrder(settings, "content://b", Random(n))
            assertEquals(playlist.toSet(), order.mapNotNull { it.uri }.toSet())
            assertEquals("content://b", order.last().uri)
            assertEquals(3, order.size)
        }
    }

    @Test fun `a track that cannot be opened is therefore skipped rather than ending the sound`() {
        val order = SoundResolver.playlistOrder(settings, "content://a", Random(1))
        // The caller walks the list until one opens; with two of three broken the third is still reached.
        assertEquals(setOf("content://b", "content://c"), order.take(2).mapNotNull { it.uri }.toSet())
    }

    @Test fun `a playlist of one loops and an empty one offers nothing to try`() {
        assertTrue(SoundResolver.playlistOrder(SoundSettings(playlist = listOf("content://only")), "content://only").single().loop)
        assertTrue(SoundResolver.playlistOrder(SoundSettings(), null).isEmpty())
    }
}

class RingUiFallbackTest {
    private val high = NotificationManager.IMPORTANCE_HIGH

    @Test fun `a healthy setup needs no help`() {
        assertFalse(RingUiFallback.mayNotAppear(36, notificationsEnabled = true, ringChannelImportance = high, fullScreenAllowed = true))
    }

    @Test fun `blocked notifications, a muted channel or a revoked full screen permission each need the fallback`() {
        assertTrue(RingUiFallback.mayNotAppear(36, notificationsEnabled = false, ringChannelImportance = high, fullScreenAllowed = true))
        assertTrue(RingUiFallback.mayNotAppear(36, true, NotificationManager.IMPORTANCE_NONE, true))
        assertTrue(RingUiFallback.mayNotAppear(36, true, NotificationManager.IMPORTANCE_LOW, true))
        assertTrue(RingUiFallback.mayNotAppear(34, true, high, fullScreenAllowed = false))
    }

    @Test fun `the full screen permission only matters from android 14 and a missing channel is not a problem`() {
        assertFalse(RingUiFallback.mayNotAppear(33, true, high, fullScreenAllowed = false))
        assertFalse(RingUiFallback.mayNotAppear(36, true, null, true))
    }
}
