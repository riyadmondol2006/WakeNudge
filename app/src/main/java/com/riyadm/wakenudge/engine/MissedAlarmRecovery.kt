package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.engine.notify.Notifier
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Alarms that should have gone off while the phone was powered down. Shortly after the scheduled time the alarm
 * simply rings (a reboot at 06:58 must not eat the 07:00 alarm); later than that it is recorded as missed and the
 * user is told, instead of silently re-arming a one-time alarm for tomorrow.
 */
@Singleton
class MissedAlarmRecovery @Inject constructor(
    private val alarms: AlarmRepository,
    private val history: HistoryRepository,
    private val ring: RingController,
    private val launcher: RingStarter,
    private val scheduler: AlarmScheduler,
    private val notifier: Notifier,
    private val time: TimeSource,
    private val diagnostics: DiagnosticsLog,
) {
    /** [before] is the map of registered trigger times captured before the reschedule that followed the boot. */
    suspend fun process(before: Map<Long, Long>) {
        val now = time.nowMillis()
        for ((alarmId, at) in before) {
            if (at >= now - GRACE_MS || alarmId < 0) continue
            // One alarm that cannot be examined (database trouble) must not hide the others that are overdue.
            try {
                processOne(alarmId, at, now)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                diagnostics.error("Recovery", "could not examine overdue alarm $alarmId", t)
            }
        }
    }

    private suspend fun processOne(alarmId: Long, at: Long, now: Long) {
        val alarm = alarms.getAlarm(alarmId) ?: return
        if (!alarm.enabled && alarm.isRepeating) return
        if (history.findOccurrence(alarmId, at) != null) return

        val lateBy = now - at
        if (lateBy <= LATE_RING_WINDOW_MS) {
            diagnostics.warn("Recovery", "alarm $alarmId was due ${lateBy / 1000}s ago while off: ringing now")
            ring.onAlarmFired(alarmId, at)?.let(launcher::start)
        } else {
            diagnostics.warn("Recovery", "alarm $alarmId was due ${lateBy / 60_000} min ago while off: marking missed")
            history.start(
                AlarmEvent(
                    alarmId = alarmId, label = alarm.label, scheduledAt = at, firedAt = at, endedAt = now,
                    outcome = AlarmOutcome.MISSED, missionsTotal = alarm.missions.size,
                ),
            )
            notifier.showMissed(alarm, at)
            if (!alarm.isRepeating) {
                alarms.setEnabled(alarmId, false)
                scheduler.schedule(alarmId)
            }
        }
    }

    private companion object {
        const val GRACE_MS = 2_000L
        const val LATE_RING_WINDOW_MS = 10 * 60_000L
    }
}
