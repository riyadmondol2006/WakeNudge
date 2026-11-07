package com.riyadm.wakenudge.health

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.engine.DurableRingStore
import com.riyadm.wakenudge.engine.RuntimeState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One past end of this app's process, as Android reports it. */
data class ProcessExit(val atMillis: Long, val reason: Int, val description: String?)

object ExitReasons {
    /**
     * What is worth writing down about exits since [seenUpTo], given the ring that was on ([ringStartedAt], null if none):
     * the user stopping the app, or the system killing it for memory, while an alarm rang.
     */
    fun noteworthy(exits: List<ProcessExit>, seenUpTo: Long, ringStartedAt: Long?): List<String> {
        if (ringStartedAt == null) return emptyList()
        return exits.filter { it.atMillis > seenUpTo && it.atMillis >= ringStartedAt }.sortedBy { it.atMillis }.mapNotNull { exit ->
            when {
                exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user during a ring (${exit.description.orEmpty()})"
                exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory during a ring"
                exit.description?.contains("MemoryLimiter", ignoreCase = true) == true -> "killed by the memory limiter during a ring (${exit.description})"
                else -> null
            }
        }
    }
}

/**
 * Tells Alarm Health why a ring was cut short: Android only reports how the last process ended after the fact, so this
 * runs at start, while the saved ring still says what was ringing. It decides nothing and cannot fail a start.
 */
@Singleton
class ExitPostMortem @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: RuntimeState,
    private val diagnostics: DiagnosticsLog,
) {
    fun report() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
                .map { ProcessExit(it.timestamp, it.reason, it.description) }
            val ring = DurableRingStore(runtime, diagnostics).ring()
            ExitReasons.noteworthy(exits, runtime.lastExitSeenAt, ring?.startedAtWall).forEach { diagnostics.warn("Exit", "${ring?.alarmId}: $it") }
            exits.maxOfOrNull { it.atMillis }?.let { runtime.lastExitSeenAt = it }
        }.onFailure { diagnostics.warn("Exit", "could not read how the last process ended: ${it.message}") }
    }

    private companion object {
        const val MAX_EXITS = 8
    }
}
