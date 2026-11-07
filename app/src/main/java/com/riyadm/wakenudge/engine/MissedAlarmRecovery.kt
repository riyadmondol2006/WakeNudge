package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Alarms that should have gone off while the phone was powered down. Shortly after the scheduled time the alarm
 * simply rings (a reboot at 06:58 must not eat the 07:00 alarm), and an alarm that comes back from restarts for longer
 * rings for that long too. Later than that it is recorded as missed and the user is told, instead of silently re-arming
 * a one-time alarm for tomorrow.
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
    /**
     * [before] is the map of registered trigger times captured before the reschedule that followed the boot.
     * [dueBy] is when that reschedule finished: it moved everything due by then on to its next ring, so all of that is
     * overdue here, even an alarm that fell due a moment ago. Without it, a short grace applies to "now".
     */
    suspend fun process(before: Map<Long, Long>, dueBy: Long? = null) = lock.withLock {
        val now = time.nowMillis()
        val cutoff = dueBy ?: (now - GRACE_MS)
        for ((alarmId, at) in before) {
            if (at > cutoff || alarmId < 0) continue
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
        val lateBy = now - at
        val alarm = try {
            alarms.getAlarm(alarmId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Storage never decides whether an alarm rings. Whether it may still ring is all that is needed from the alarm,
            // and its saved copy can say; ringing it then copes with the database being gone.
            diagnostics.error("Recovery", "could not read overdue alarm $alarmId; going by its saved copy", t)
            if (lateBy <= lateRingWindow(ring.savedCopy(alarmId))) ring.onAlarmFired(alarmId, at)?.let(launcher::start)
            return
        }
        if (alarm == null) return
        if (!alarm.enabled && alarm.isRepeating) return
        // When the history cannot say whether this one rang, it did not: a spurious ring beats a missed one.
        if (guarded("looking up alarm $alarmId's history", null) { history.findOccurrence(alarmId, at) } != null) return

        if (lateBy <= lateRingWindow(alarm)) {
            diagnostics.warn("Recovery", "alarm $alarmId was due ${lateBy / 1000}s ago while off: ringing now")
            ring.onAlarmFired(alarmId, at)?.let(launcher::start)
        } else {
            diagnostics.warn("Recovery", "alarm $alarmId was due ${lateBy / 60_000} min ago while off: marking missed")
            guarded("recording alarm $alarmId as missed", Unit) {
                history.start(
                    AlarmEvent(
                        alarmId = alarmId, label = alarm.label, scheduledAt = at, firedAt = at, endedAt = now,
                        outcome = AlarmOutcome.MISSED, missionsTotal = alarm.missions.size,
                    ),
                )
            }
            notifier.showMissed(alarm, at)
            if (!alarm.isRepeating) {
                alarms.setEnabled(alarmId, false)
                scheduler.schedule(alarmId)
            }
        }
    }

    /** Switching the phone off through the alarm only works for as long as the alarm has given up on coming back. */
    private fun lateRingWindow(alarm: Alarm?): Long =
        maxOf(LATE_RING_WINDOW_MS, (alarm?.antiOversleep?.resumeWindowMinutes ?: 0) * 60_000L)

    private inline fun <T> guarded(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        diagnostics.error("Recovery", "$what failed; carrying on", t)
        fallback
    }

    /** The boot broadcast and the app-start repair can both look at once; one after the other, the second sees the first's records. */
    private val lock = Mutex()

    private companion object {
        const val GRACE_MS = 2_000L
        const val LATE_RING_WINDOW_MS = 10 * 60_000L
    }
}
