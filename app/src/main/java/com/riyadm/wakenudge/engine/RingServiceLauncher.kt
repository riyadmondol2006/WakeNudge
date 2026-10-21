package com.riyadm.wakenudge.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.engine.notify.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Starts a ring for a request. A seam so recovery and trigger handling can be tested without real audio. */
fun interface RingStarter {
    fun start(request: RingRequest)
}

/** Starts [RingService] for a request and reports honestly when the OS refuses. */
@Singleton
class RingServiceLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: RuntimeState,
    private val notifier: Notifier,
    private val diagnostics: DiagnosticsLog,
) : RingStarter {
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
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            // E.g. ForegroundServiceStartNotAllowedException. The backup alarm will try again; meanwhile surface it.
            runtime.lastServiceStartFailure = "${System.currentTimeMillis()}: ${t.javaClass.simpleName}: ${t.message}"
            diagnostics.error("Launcher", "could not start the ring service", t)
            notifier.postRingFallback()
        }
    }

    fun stop() {
        context.stopService(Intent(context, RingService::class.java))
    }
}
