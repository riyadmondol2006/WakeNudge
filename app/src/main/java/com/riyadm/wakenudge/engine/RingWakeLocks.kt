package com.riyadm.wakenudge.engine

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The wake locks of a ring, in one place so that every one of them is timed (a leaked lock can never outlive its
 * timeout) and every acquire or release is safe to call twice or from any state.
 *
 * - **handoff**: bridges the gap between the trigger receiver finishing and the ring service getting going, during
 *   which nothing else would keep the CPU awake after a Doze wake-up.
 * - **ring**: held by the ring service while it plays.
 * - **screen**: a short bright-screen lock for when the full-screen alarm cannot be shown (permission revoked), so the
 *   heads-up notification is at least seen.
 */
@Singleton
class RingWakeLocks @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val power: PowerManager? = context.getSystemService(PowerManager::class.java)
    private var ring: PowerManager.WakeLock? = null
    private var handoff: PowerManager.WakeLock? = null
    private var screen: PowerManager.WakeLock? = null

    val ringHeld: Boolean get() = synchronized(this) { ring?.isHeld == true }
    val handoffHeld: Boolean get() = synchronized(this) { handoff?.isHeld == true }
    val screenHeld: Boolean get() = synchronized(this) { screen?.isHeld == true }

    @Synchronized fun acquireRing(timeoutMs: Long = RING_MAX_MS): Boolean {
        val lock = ring ?: create(PowerManager.PARTIAL_WAKE_LOCK, "WakeNudge:ring")?.also { ring = it } ?: return false
        return hold(lock, timeoutMs)
    }

    @Synchronized fun releaseRing() = release(ring)

    @Synchronized fun acquireHandoff(timeoutMs: Long = HANDOFF_MS): Boolean {
        val lock = handoff ?: create(PowerManager.PARTIAL_WAKE_LOCK, "WakeNudge:handoff")?.also { handoff = it } ?: return false
        return hold(lock, timeoutMs)
    }

    @Synchronized fun releaseHandoff() = release(handoff)

    /** Turns the display on for a few seconds. The flags are deprecated but still the only way to light the screen. */
    @Suppress("DEPRECATION")
    @Synchronized fun wakeScreen(timeoutMs: Long = SCREEN_MS): Boolean {
        val lock = screen ?: create(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "WakeNudge:screen")?.also { screen = it }
            ?: return false
        return hold(lock, timeoutMs)
    }

    @Synchronized fun releaseScreen() = release(screen)

    @Synchronized fun releaseAll() {
        release(ring)
        release(handoff)
        release(screen)
    }

    private fun create(level: Int, tag: String): PowerManager.WakeLock? =
        runCatching { power?.newWakeLock(level, tag)?.apply { setReferenceCounted(false) } }.getOrNull()

    private fun hold(lock: PowerManager.WakeLock, timeoutMs: Long): Boolean =
        runCatching { lock.acquire(timeoutMs) }.isSuccess

    private fun release(lock: PowerManager.WakeLock?) {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
    }

    companion object {
        /** Longest ring timeout is 30 minutes (see the alarm editor), plus slack for a sunrise lead-in. */
        const val RING_MAX_MS = 45 * 60_000L
        const val HANDOFF_MS = 15_000L
        const val SCREEN_MS = 10_000L
    }
}
