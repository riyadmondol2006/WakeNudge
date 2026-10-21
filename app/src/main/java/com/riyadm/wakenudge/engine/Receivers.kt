package com.riyadm.wakenudge.engine

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
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
    fun launcher(): RingServiceLauncher
    fun wakeChecks(): WakeCheckController
    fun timers(): TimerController
    fun alarmActions(): AlarmActions
    fun notifier(): Notifier
    fun runtime(): RuntimeState
    fun time(): TimeSource
    fun audio(): AlarmAudioEngine
    fun diagnostics(): DiagnosticsLog
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
        val e = context.engine()
        runAsync {
            e.notifier().ensureChannels()
            when (action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                Intent.ACTION_MY_PACKAGE_REPLACED,
                -> {
                    val before = e.runtime().registeredTriggers()
                    e.audio().restoreSystemVolumeIfStale()
                    e.scheduler().rescheduleAll("system:$action")
                    e.ring().recoverStaleState()
                    if (action != Intent.ACTION_MY_PACKAGE_REPLACED) e.recovery().process(before)
                }
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                    e.time().refreshZone()
                    e.scheduler().rescheduleAll("system:$action")
                }
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                    e.scheduler().rescheduleAll("exact-alarm-permission")
                else -> e.diagnostics().info("System", "ignored $action")
            }
        }
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
                Actions.SNOOZE -> ring.snooze()
                Actions.DISMISS ->
                    if (e.ringStore().current?.alarm?.id == id) ring.dismiss() else ring.dismissSnoozed(id)
                Actions.SNOOZE_WAKE_NOW -> ring.wakeNow(id)?.let(e.launcher()::start)
                Actions.SKIP_NEXT -> {
                    e.alarmActions().skipNext(id)
                    e.notifier().cancelUpcoming(id)
                }
                Actions.WAKE_CHECK_OK -> e.wakeChecks().answer(id)
                Actions.WAKE_CHECK_CANCEL -> e.wakeChecks().abandon(id, WakeCheckStatus.CANCELLED)
                Actions.TIMER_PAUSE -> e.timers().pause(id)
                Actions.TIMER_RESUME -> e.timers().start(id)
                Actions.TIMER_RESET -> e.timers().reset(id)
                Actions.TIMER_ADD_MINUTE ->
                    if (e.ringStore().current?.isTimer == true) ring.addMinuteToRingingTimer() else e.timers().addMinute(id)
                Actions.TIMER_STOP -> ring.dismiss()
            }
        }
    }
}
