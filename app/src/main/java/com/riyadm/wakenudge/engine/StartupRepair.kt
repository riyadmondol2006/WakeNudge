package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android forgets every alarm of an app that is force-stopped (Settings, Force stop, or the "clean up" some phone makers
 * run when an app is swiped away) and tells the app nothing about it. The first chance to notice is the app's next
 * start, so every start looks: if the watchdog or any armed alarm is missing from AlarmManager, the whole schedule is
 * registered again, and alarms that fell due while nothing was armed are rung or reported as missed.
 *
 * The same happens at every boot (nothing survives a reboot), side by side with the boot broadcast. Each of the two
 * passes reads the armed times before its own reschedule and then looks for overdue alarms in them, so whichever runs
 * first, an alarm due while the phone was off is never lost; recovery is serialised and remembers what it handled.
 */
@Singleton
class StartupRepair @Inject constructor(
    private val pending: PendingAlarms,
    private val runtime: RuntimeState,
    private val scheduler: AlarmScheduler,
    private val recovery: MissedAlarmRecovery,
    private val time: TimeSource,
    private val diagnostics: DiagnosticsLog,
) {
    /** Returns whether anything had to be registered again. */
    suspend fun repairIfNeeded(): Boolean {
        val missing = missingTriggers() ?: return false
        diagnostics.warn("Scheduler", "$missing at app start; registering every alarm again")
        val before = runtime.registeredTriggers()
        scheduler.rescheduleAll("app-start")
        recovery.process(before, dueBy = time.nowMillis())
        return true
    }

    /** What Android no longer holds, or null when everything the app armed is still there. */
    internal fun missingTriggers(): String? {
        if (!pending.isScheduled(TriggerKind.WATCHDOG, 0)) return "the watchdog is not registered"
        val lost = runtime.registeredTriggers().keys.count { !pending.isScheduled(TriggerKind.ALARM, it) }
        return if (lost > 0) "$lost armed alarm(s) are not registered" else null
    }
}
