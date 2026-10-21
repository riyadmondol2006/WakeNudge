package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A rescue trigger remembers *what* to ring in the trigger's index: the [RingKind] and how many times the ring has
 * already been retried share one integer, so a retry of a retry cannot loop forever.
 */
object RingRetry {
    /** Long enough for the OS to tear a crashed process down, short enough that the alarm is hardly interrupted. */
    const val DELAY_MS = 2_000L
    const val MAX_ATTEMPTS = 3

    fun encode(kind: RingKind, attempt: Int): Int = attempt.coerceAtLeast(0) * KIND_SPAN + kind.ordinal
    fun kindOf(index: Int): RingKind = RingKind.entries.getOrNull(index.coerceAtLeast(0) % KIND_SPAN) ?: RingKind.PRIMARY
    fun attemptOf(index: Int): Int = index.coerceAtLeast(0) / KIND_SPAN

    private const val KIND_SPAN = 100
}

/**
 * What to do when the app dies while an alarm is ringing. The ring screen shares a process with the ring service, so a
 * crash in the screen takes the sound down with it: the alarm is re-armed a moment later. A screen that crashes twice
 * in a few minutes is not shown automatically any more ("safe mode": sound plus a notification with direct controls).
 */
object CrashRescuePolicy {
    const val WINDOW_MS = 10 * 60_000L
    const val MAX_RESCUES = 5

    /** Crash times (epoch millis) that still count, newest last. */
    fun recent(crashTimes: List<Long>, now: Long): List<Long> = crashTimes.filter { now - it in 0..WINDOW_MS }

    fun shouldRescue(crashTimes: List<Long>, now: Long): Boolean = recent(crashTimes, now).size <= MAX_RESCUES

    /** From the second crash inside the window the full-screen ring UI is left out of the notification. */
    fun safeMode(crashTimes: List<Long>, now: Long): Boolean = recent(crashTimes, now).size >= 2
}

/**
 * Keeps an alarm alive across a crash or an unexpected service death. A crash records itself, then re-arms the ring
 * with an alarm-clock trigger (exempt from background-start limits) so a fresh process picks the alarm up again.
 */
@Singleton
class RingCrashGuard @Inject constructor(
    private val store: RingSessionStore,
    private val runtime: RuntimeState,
    private val pending: PendingAlarms,
    private val time: TimeSource,
    private val diagnostics: DiagnosticsLog,
) {
    /** Chains in front of the platform's handler, which still gets to end the process afterwards. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { onCrash(error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Called with whatever is about to kill the process. Returns whether a rescue was armed (for tests). */
    internal fun onCrash(error: Throwable): Boolean {
        val session = store.current ?: return false
        diagnostics.error("Rescue", "crash while alarm ${session.alarm.id} was ${session.phase}", error)
        return rescue(session, "crash: ${error.javaClass.simpleName}")
    }

    /** Arms a one-shot ring for [session]; false when it is not worth it (silent phase, test, timer) or loops. */
    fun rescue(session: RingSession, reason: String): Boolean {
        if (session.phase != RingPhase.RINGING || session.isTimer || session.isTest) return false
        val now = time.nowMillis()
        runtime.recordRingCrash(now)
        val crashes = runtime.ringCrashTimes()
        if (!CrashRescuePolicy.shouldRescue(crashes, now)) {
            diagnostics.error("Rescue", "giving up on alarm ${session.alarm.id}: too many crashes in a row")
            return false
        }
        val index = RingRetry.encode(session.kind, 0)
        val armed = pending.setAlarmClock(
            TriggerKind.RING_RETRY, session.alarm.id, now + RingRetry.DELAY_MS,
            index = index, eventId = session.eventId, payloadAt = session.scheduledAt,
        )
        diagnostics.warn("Rescue", "alarm ${session.alarm.id} ring rescue ${if (armed) "armed" else "could NOT be armed"} ($reason)")
        return armed
    }
}
