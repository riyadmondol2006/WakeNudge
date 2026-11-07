package com.riyadm.wakenudge.engine

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the phone's audio is doing about calls, from [AudioManager.getMode]. */
enum class CallMode {
    NONE,
    /** A call is ringing in. */
    RINGING,
    /** A phone call (or one being screened or redirected). */
    CALL,
    /** A voice or video call in an app. */
    COMMUNICATION,
    ;

    companion object {
        fun of(audioMode: Int): CallMode = when (audioMode) {
            AudioManager.MODE_RINGTONE -> RINGING
            AudioManager.MODE_IN_CALL, MODE_CALL_SCREENING, MODE_CALL_REDIRECT -> CALL
            AudioManager.MODE_IN_COMMUNICATION, MODE_COMMUNICATION_REDIRECT -> COMMUNICATION
            else -> NONE
        }

        // Spelled out: the constants arrived in API 30 and 33, and the values are what older phones would never report.
        private const val MODE_CALL_SCREENING = 4
        private const val MODE_CALL_REDIRECT = 5
        private const val MODE_COMMUNICATION_REDIRECT = 6
    }
}

/**
 * Whether a call keeps the alarm quiet. A phone call, or one ringing in, does so for as long as it lasts: the alarm never
 * covers a call. A call in an app could be started by anyone to silence the alarm, so it quiets one ring for at most
 * [COMMUNICATION_BUDGET_MS] in all. Pure: the caller says what the phone is doing and when.
 */
class CallPause {
    private var ringKey = Long.MIN_VALUE
    private var communicationUsedMs = 0L
    private var lastCommunicationAt = -1L

    /** A new ring starts with the whole budget. */
    fun reset(key: Long) {
        if (key == ringKey) return
        ringKey = key
        communicationUsedMs = 0
        lastCommunicationAt = -1
    }

    fun quiet(mode: CallMode, elapsedNow: Long): Boolean = when (mode) {
        CallMode.RINGING, CallMode.CALL -> {
            lastCommunicationAt = -1
            true
        }
        CallMode.COMMUNICATION -> {
            if (lastCommunicationAt >= 0) communicationUsedMs += (elapsedNow - lastCommunicationAt).coerceIn(0, MAX_STEP_MS)
            lastCommunicationAt = elapsedNow
            communicationUsedMs < COMMUNICATION_BUDGET_MS
        }
        CallMode.NONE -> {
            lastCommunicationAt = -1
            false
        }
    }

    /** Whether a ring is quiet because of a call it is allowed to wait for, not one that has used up its time. */
    fun isCallActive(mode: CallMode): Boolean = mode == CallMode.RINGING || mode == CallMode.CALL ||
        (mode == CallMode.COMMUNICATION && communicationUsedMs < COMMUNICATION_BUDGET_MS)

    companion object {
        const val COMMUNICATION_BUDGET_MS = 10 * 60_000L

        /** A gap longer than this between two looks (the service was busy, the CPU slept) counts for no more. */
        private const val MAX_STEP_MS = 5_000L
    }
}

/**
 * Follows the phone's audio mode while a ring is on, without any phone permission: `AudioManager.getMode` and, on Android
 * 12 and later, its change listener. The ring service asks it once a second, and the listener makes it react at once.
 */
@Singleton
class CallWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val time: TimeSource,
    private val diagnostics: DiagnosticsLog,
) {
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val pause = CallPause()
    private var listener: Any? = null

    @Volatile private var lastMode = CallMode.NONE

    fun mode(): CallMode = runCatching { CallMode.of(audio?.mode ?: AudioManager.MODE_NORMAL) }.getOrDefault(CallMode.NONE)

    /** Starts listening for [onChange]; harmless to call again. */
    fun start(onChange: () -> Unit) {
        if (listener != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val l = AudioManager.OnModeChangedListener { mode ->
            val now = CallMode.of(mode)
            if (now != lastMode) diagnostics.info("Ring", "call state changed: $lastMode -> $now")
            lastMode = now
            onChange()
        }
        if (runCatching { audio?.addOnModeChangedListener(ContextCompat.getMainExecutor(context), l) }.isSuccess) listener = l
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (listener as? AudioManager.OnModeChangedListener)?.let { runCatching { audio?.removeOnModeChangedListener(it) } }
        listener = null
    }

    /** Whether a call keeps the ring identified by [ringKey] quiet right now. */
    @Synchronized
    fun keepsQuiet(ringKey: Long): Boolean {
        pause.reset(ringKey)
        return pause.quiet(mode().also { lastMode = it }, time.elapsedMillis())
    }

    /** Whether a call is going on that the ring waits for: no pull-back covers it. */
    @Synchronized
    fun callActive(): Boolean = pause.isCallActive(mode())
}
