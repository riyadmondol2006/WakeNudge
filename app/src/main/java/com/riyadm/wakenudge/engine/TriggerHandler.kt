package com.riyadm.wakenudge.engine

import android.content.Intent
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.engine.notify.Notifier
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** Routes every AlarmManager trigger to the component that owns it. */
@Singleton
class TriggerHandler @Inject constructor(
    private val ring: RingController,
    private val wakeChecks: WakeCheckController,
    private val launcher: RingServiceLauncher,
    private val scheduler: AlarmScheduler,
    private val notifier: Notifier,
    private val settings: SettingsRepository,
    private val runtime: RuntimeState,
    private val time: TimeSource,
    private val diagnostics: DiagnosticsLog,
) {
    suspend fun handle(intent: Intent) {
        val kind = Triggers.kindOf(intent.action) ?: return
        val id = intent.getLongExtra(Triggers.EXTRA_ID, 0)
        val at = intent.getLongExtra(Triggers.EXTRA_AT, time.nowMillis())
        val eventId = intent.getLongExtra(Triggers.EXTRA_EVENT_ID, -1)
        val index = intent.getIntExtra(Triggers.EXTRA_INDEX, 0)
        diagnostics.info("Trigger", "$kind id=$id index=$index")

        when (kind) {
            TriggerKind.ALARM -> ring.onAlarmFired(id, at)?.let(launcher::start)
            TriggerKind.SUNRISE -> ring.onSunrise(id, at)?.let(launcher::start)
            TriggerKind.SNOOZE -> ring.onSnoozeExpired(id, eventId)?.let(launcher::start)
            TriggerKind.BACKUP -> ring.onBackupFired(id, eventId, index)?.let(launcher::start)
            TriggerKind.UPCOMING -> ring.onUpcoming(id, at)
            TriggerKind.WAKE_CHECK_PROMPT -> wakeChecks.onPrompt(id)
            TriggerKind.WAKE_CHECK_DEADLINE -> ring.onWakeCheckDeadline(id)?.let(launcher::start)
            TriggerKind.TIMER -> ring.onTimerFired(id)?.let(launcher::start)
            TriggerKind.BEDTIME -> onBedtime()
            TriggerKind.WATCHDOG -> {
                runtime.lastWatchdogAt = time.nowMillis()
                scheduler.rescheduleAll("watchdog")
                ring.recoverStaleState()
            }
        }
    }

    private suspend fun onBedtime() {
        val bedtime = settings.current().bedtime
        if (bedtime.enabled) {
            val wake = scheduler.nextAlarm()?.at?.toLocalTime()
            notifier.showBedtime(LocalTime.of(bedtime.hour, bedtime.minute), wake)
        }
        scheduler.refreshBedtime()
    }
}
