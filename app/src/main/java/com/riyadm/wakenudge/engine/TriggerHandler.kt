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
import kotlinx.coroutines.CancellationException

/** Routes every AlarmManager trigger to the component that owns it. */
@Singleton
class TriggerHandler @Inject constructor(
    private val ring: RingController,
    private val wakeChecks: WakeCheckController,
    private val launcher: RingStarter,
    private val scheduler: AlarmScheduler,
    private val notifier: Notifier,
    private val settings: SettingsRepository,
    private val runtime: RuntimeState,
    private val time: TimeSource,
    private val diagnostics: DiagnosticsLog,
    private val battery: BatteryGauge,
) {
    suspend fun handle(intent: Intent) {
        val kind = Triggers.kindOf(intent.action) ?: return
        val id = intent.getLongExtra(Triggers.EXTRA_ID, 0)
        val at = intent.getLongExtra(Triggers.EXTRA_AT, time.nowMillis())
        val eventId = intent.getLongExtra(Triggers.EXTRA_EVENT_ID, -1)
        val index = intent.getIntExtra(Triggers.EXTRA_INDEX, 0)
        diagnostics.info("Trigger", "$kind id=$id index=$index")

        when (kind) {
            TriggerKind.ALARM -> ringing(RingKind.PRIMARY, id, at, eventId) { ring.onAlarmFired(id, at) }
            TriggerKind.SUNRISE -> ring.onSunrise(id, at)?.let(launcher::start)
            TriggerKind.SNOOZE -> ringing(RingKind.SNOOZE_RETURN, id, at, eventId) { ring.onSnoozeExpired(id, eventId) }
            TriggerKind.BACKUP -> ringing(RingKind.BACKUP, id, at, eventId) { ring.onBackupFired(id, eventId, index) }
            TriggerKind.UPCOMING -> ring.onUpcoming(id, at)
            TriggerKind.WAKE_CHECK_PROMPT -> wakeChecks.onPrompt(id)
            TriggerKind.WAKE_CHECK_DEADLINE -> ringing(RingKind.WAKE_CHECK_RERING, id, at, eventId) { ring.onWakeCheckDeadline(id) }
            TriggerKind.TIMER -> ring.onTimerFired(id)?.let(launcher::start)
            TriggerKind.BEDTIME -> onBedtime()
            TriggerKind.BATTERY_CHECK -> onBatteryCheck()
            TriggerKind.RING_RETRY -> ring.onRetry(id, eventId, at, RingRetry.kindOf(index), RingRetry.attemptOf(index))?.let(launcher::start)
            TriggerKind.WATCHDOG -> {
                runtime.lastWatchdogAt = time.nowMillis()
                scheduler.rescheduleAll("watchdog")
                ring.recoverStaleState()
            }
        }
    }

    /**
     * Starts a ring for a trigger. The engine decides *whether* to ring (duplicates, handled alarms, disabled ones), but
     * if it fails while deciding, the alarm rings anyway with what the trigger itself carries: a trigger that fired is a
     * real alarm, and oversleeping because of an engine error is worse than a spurious ring.
     */
    private suspend inline fun ringing(kind: RingKind, alarmId: Long, at: Long, eventId: Long, decide: () -> RingRequest?) {
        val request = try {
            decide()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            diagnostics.error("Trigger", "$kind for alarm $alarmId failed inside the engine; ringing anyway", t)
            RingRequest(alarmId, eventId, at, if (alarmId == RingIds.TEST_ALARM_ID) RingKind.TEST else kind)
        }
        request?.let(launcher::start)
    }

    /** Some hours before the next alarm: warn once if the battery may not last until it rings. */
    private suspend fun onBatteryCheck() {
        if (!settings.current().lowBatteryWarning) return
        val next = scheduler.nextAlarm() ?: return
        val reading = battery.read()
        if (LowBatteryPolicy.shouldWarn(reading, next.at.toInstant().toEpochMilli(), time.nowMillis()) && reading != null) {
            diagnostics.warn("Battery", "${reading.percent}% and not charging before the alarm at ${next.at.toLocalTime()}")
            notifier.showLowBattery(reading.percent, next.at.toLocalTime(), next.at.toInstant().toEpochMilli())
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
