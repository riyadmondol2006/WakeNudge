package com.riyadm.wakenudge.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.engine.notify.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Starts a ring for a request. A seam so recovery and trigger handling can be tested without real audio. */
fun interface RingStarter {
    fun start(request: RingRequest)
}

/**
 * Starts [RingService] for a request and reports honestly when the OS refuses. A refusal (Android 15 forbids media
 * foreground services from boot-completed receivers, and background-start rules differ by vendor) is not the end of
 * the alarm: the user gets a heads-up notification at once and the ring is retried a moment later from an alarm-clock
 * trigger, a context in which the system does allow the service to start.
 */
@Singleton
class RingServiceLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: RuntimeState,
    private val notifier: Notifier,
    private val diagnostics: DiagnosticsLog,
    private val pending: PendingAlarms,
    private val locks: RingWakeLocks,
    private val time: TimeSource,
) : RingStarter {
    /** Test seam: replaces the call that actually starts the service. */
    @Volatile internal var startService: (Context, Intent) -> Unit = { c, i -> ContextCompat.startForegroundService(c, i) }

    override fun start(request: RingRequest) {
        val intent = Intent(context, RingService::class.java)
            .setAction(RingService.ACTION_BEGIN)
            .putExtra(RingService.EXTRA_ALARM_ID, request.alarmId)
            .putExtra(RingService.EXTRA_EVENT_ID, request.eventId)
            .putExtra(RingService.EXTRA_SCHEDULED_AT, request.scheduledAt)
            .putExtra(RingService.EXTRA_KIND, request.kind.name)
            .putExtra(RingService.EXTRA_SNOOZE_COUNT, request.snoozeCount)
            .putExtra(RingService.EXTRA_PHASE, request.phase.name)
            .putExtra(RingService.EXTRA_TIMER_ID, request.timerId)
        // Keeps the CPU awake between this call and the service taking over (it releases the lock itself).
        locks.acquireHandoff()
        try {
            startService(context, intent)
        } catch (t: Throwable) {
            // E.g. ForegroundServiceStartNotAllowedException. Surface it now and try again from an alarm trigger.
            runtime.lastServiceStartFailure = "${System.currentTimeMillis()}: ${t.javaClass.simpleName}: ${t.message}"
            diagnostics.error("Launcher", "could not start the ring service", t)
            runCatching { notifier.postRingFallback() }
            scheduleRetry(request)
        }
    }

    /** Re-arms the ring a couple of seconds ahead, up to [RingRetry.MAX_ATTEMPTS] times. Timers and tests are not retried. */
    private fun scheduleRetry(request: RingRequest) {
        if (request.kind == RingKind.TIMER || request.kind == RingKind.TEST || request.attempt >= RingRetry.MAX_ATTEMPTS) {
            locks.releaseHandoff()
            return
        }
        val armed = pending.setAlarmClock(
            TriggerKind.RING_RETRY, request.alarmId, time.nowMillis() + RingRetry.DELAY_MS,
            index = RingRetry.encode(request.kind, request.attempt), eventId = request.eventId, payloadAt = request.scheduledAt,
        )
        diagnostics.warn("Launcher", "ring retry ${request.attempt + 1} for alarm ${request.alarmId} ${if (armed) "armed" else "could NOT be armed"}")
    }

    fun stop() {
        context.stopService(Intent(context, RingService::class.java))
    }
}
