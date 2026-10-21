package com.riyadm.wakenudge.engine

import android.app.AlarmManager
import android.content.Intent
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.engine.audio.AlarmAudioEngine
import com.riyadm.wakenudge.engine.notify.Notifier
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * What the app does when the system tells it that registered triggers may be invalid: reboot, app update, the clock or
 * time zone changing, the exact-alarm permission changing. Every step runs on its own, so one failing step (say a
 * full disk when posting a notification) can never keep the alarms from being armed again.
 */
@Singleton
class SystemEventHandler @Inject constructor(
    private val notifier: Notifier,
    private val audio: AlarmAudioEngine,
    private val scheduler: AlarmScheduler,
    private val ring: RingController,
    private val recovery: MissedAlarmRecovery,
    private val runtime: RuntimeState,
    private val time: TimeSource,
    private val diagnostics: DiagnosticsLog,
) {
    suspend fun handle(action: String) {
        step("notification channels") { notifier.ensureChannels() }
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val before = runtime.registeredTriggers()
                step("alarm volume") { audio.restoreSystemVolumeIfStale() }
                step("rescheduling") { scheduler.rescheduleAll("system:$action") }
                step("housekeeping") { ring.recoverStaleState() }
                if (action != Intent.ACTION_MY_PACKAGE_REPLACED) step("alarms due while off") { recovery.process(before) }
            }
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                step("time zone") { time.refreshZone() }
                val before = runtime.registeredTriggers()
                step("rescheduling") { scheduler.rescheduleAll("system:$action") }
                // A clock set forward (by the user or by network time) can make an armed alarm overdue before the OS
                // delivers it, and rescheduling would then have moved it to tomorrow. Anything that fell due is rung or
                // recorded as missed here, exactly as after a reboot; an alarm the OS already delivered is not repeated.
                step("alarms made overdue by the clock change") { recovery.process(before) }
            }
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                step("rescheduling") { scheduler.rescheduleAll("exact-alarm-permission") }
            else -> diagnostics.info("System", "ignored $action")
        }
    }

    private suspend inline fun step(what: String, block: () -> Unit) {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            diagnostics.error("System", "$what failed; continuing with the remaining steps", t)
        }
    }
}
