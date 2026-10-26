package com.riyadm.wakenudge.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.domain.usecase.ActionResult
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.usecase.AlarmActions
import com.riyadm.wakenudge.engine.audio.AlarmAudioEngine
import com.riyadm.wakenudge.engine.notify.Actions
import com.riyadm.wakenudge.engine.notify.Notifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs [block] off the main thread while keeping the broadcast alive; never lets an exception escape. */
internal fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val result = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("WakeNudge", "receiver work failed", t)
        } finally {
            result.finish()
        }
    }
}

/**
 * Receivers reach the object graph through an EntryPoint on the application component rather than field injection:
 * that works in Direct Boot (before first unlock) and avoids bytecode-level superclass rewriting.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface EngineEntryPoint {
    fun triggerHandler(): TriggerHandler
    fun scheduler(): AlarmScheduler
    fun recovery(): MissedAlarmRecovery
    fun ring(): RingController
    fun ringStore(): RingSessionStore
    fun launcher(): RingStarter
    fun wakeChecks(): WakeCheckController
    fun timers(): TimerController
    fun alarmActions(): AlarmActions
    fun notifier(): Notifier
    fun runtime(): RuntimeState
    fun time(): TimeSource
    fun audio(): AlarmAudioEngine
    fun diagnostics(): DiagnosticsLog
    fun systemEvents(): SystemEventHandler
}

internal fun Context.engine(): EngineEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, EngineEntryPoint::class.java)

/** Receives the exact triggers registered through [PendingAlarms]. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val handler = context.engine().triggerHandler()
        runAsync { handler.handle(intent) }
    }
}

/** Reboot, app update, clock and time-zone changes: everything that can invalidate registered triggers. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val handler = context.engine().systemEvents()
        runAsync { handler.handle(action) }
    }
}

/** Buttons on notifications (snooze, dismiss, skip, timer controls, "I'm awake"). */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val e = context.engine()
        val id = intent.getLongExtra(Actions.EXTRA_ID, 0)
        runAsync {
            val ring = e.ring()
            when (intent.action) {
                // Each button acts on the alarm it was shown for, checked inside the controller's lock.
                Actions.SNOOZE -> ring.snooze(RingTarget(id))
                Actions.DISMISS -> if (!ring.dismiss(RingTarget(id))) ring.dismissSnoozed(id)
                Actions.SNOOZE_WAKE_NOW -> ring.wakeNow(id)?.let(e.launcher()::start)
                Actions.SKIP_NEXT -> {
                    val at = intent.getLongExtra(Actions.EXTRA_AT, 0).takeIf { it > 0 }
                    // A refused skip (edit protection) leaves the heads-up up, so it does not look as if it worked.
                    if (e.alarmActions().skipNext(id, occurrenceAt = at) !is ActionResult.Locked) e.notifier().cancelUpcoming(id)
                }
                Actions.WAKE_CHECK_OK -> e.wakeChecks().answer(id)
                Actions.WAKE_CHECK_CANCEL -> e.wakeChecks().abandon(id, WakeCheckStatus.CANCELLED)
                Actions.TIMER_PAUSE -> e.timers().pause(id)
                Actions.TIMER_RESUME -> e.timers().start(id)
                Actions.TIMER_RESET -> e.timers().reset(id)
                Actions.TIMER_ADD_MINUTE ->
                    if (e.ringStore().current?.isTimer == true) ring.addMinuteToRingingTimer() else e.timers().addMinute(id)
                Actions.TIMER_STOP -> ring.dismiss(RingTarget(RingIds.TIMER_ALARM_BASE - id, timerId = id))
            }
        }
    }
}
