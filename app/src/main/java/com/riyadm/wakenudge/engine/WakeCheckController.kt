package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.WakeCheck
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.repository.WakeCheckRepository
import com.riyadm.wakenudge.engine.notify.Notifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "Still awake?" follow-up. State lives in the database and in AlarmManager triggers, never in memory, so a
 * check survives process death and reboot. Flow:
 *  dismissal -> WAITING (prompt due after the delay) -> PROMPTED (deadline armed) ->
 *     answered   : next check, or PASSED when all checks are done
 *     unanswered : the alarm rings again (up to a limit), then the check is recorded as FAILED.
 */
@Singleton
class WakeCheckController @Inject constructor(
    private val checks: WakeCheckRepository,
    private val alarms: AlarmRepository,
    private val history: HistoryRepository,
    private val pending: PendingAlarms,
    private val notifier: Notifier,
    private val time: TimeSource,
    private val diagnostics: DiagnosticsLog,
) {
    /** Called right after a real dismissal; arms the first prompt. [reRings] carries over earlier unanswered checks. */
    suspend fun startAfterDismissal(alarm: Alarm, eventId: Long, reRings: Int) {
        if (!alarm.wakeCheck.enabled || alarm.id < 0) return
        val promptAt = time.nowMillis() + alarm.wakeCheck.delayMinutes.coerceIn(1, 30) * 60_000L
        checks.save(WakeCheck(alarm.id, eventId, WakeCheck.Phase.WAITING, promptAt, 0, checksDone = 0, reRings = reRings))
        pending.setExact(TriggerKind.WAKE_CHECK_PROMPT, alarm.id, promptAt)
        setEventStatus(eventId, WakeCheckStatus.PENDING)
        diagnostics.info("WakeCheck", "armed for alarm ${alarm.id}: prompt in ${alarm.wakeCheck.delayMinutes} min")
    }

    /** The prompt is due: ask the question and arm the deadline. */
    suspend fun onPrompt(alarmId: Long) {
        val check = checks.get(alarmId) ?: return
        val alarm = alarms.getAlarm(alarmId) ?: return abandon(alarmId, WakeCheckStatus.CANCELLED)
        val deadline = time.nowMillis() + alarm.wakeCheck.responseSeconds.coerceIn(15, 600) * 1000L
        checks.save(check.copy(phase = WakeCheck.Phase.PROMPTED, deadlineAt = deadline))
        pending.setAlarmClock(TriggerKind.WAKE_CHECK_DEADLINE, alarmId, deadline)
        notifier.showWakeCheck(alarm, deadline, check.eventId)
        diagnostics.info("WakeCheck", "prompted alarm $alarmId, answer by $deadline")
    }

    /** The user confirmed they are awake. */
    suspend fun answer(alarmId: Long) {
        val check = checks.get(alarmId) ?: return
        pending.cancel(TriggerKind.WAKE_CHECK_DEADLINE, alarmId)
        notifier.cancelWakeCheck(alarmId)
        val alarm = alarms.getAlarm(alarmId)
        val done = check.checksDone + 1
        if (alarm == null || done >= alarm.wakeCheck.maxChecks) {
            checks.delete(alarmId)
            setEventStatus(check.eventId, WakeCheckStatus.PASSED)
            diagnostics.info("WakeCheck", "alarm $alarmId passed all checks")
            deleteIfUsedUp(alarmId)
        } else {
            val nextAt = time.nowMillis() + alarm.wakeCheck.delayMinutes.coerceIn(1, 30) * 60_000L
            checks.save(check.copy(phase = WakeCheck.Phase.WAITING, promptAt = nextAt, deadlineAt = 0, checksDone = done))
            pending.setExact(TriggerKind.WAKE_CHECK_PROMPT, alarmId, nextAt)
        }
    }

    /** Explicit cancel from the notification, the prompt screen or the dashboard. */
    suspend fun cancel(alarmId: Long) = abandon(alarmId, WakeCheckStatus.CANCELLED)

    /** Nobody answered. Returns a ring request when the alarm should sound again, or null when the check is over. */
    suspend fun onDeadline(alarmId: Long): RingRequest? {
        val check = checks.get(alarmId) ?: return null
        if (check.phase != WakeCheck.Phase.PROMPTED) return null
        notifier.cancelWakeCheck(alarmId)
        val alarm = alarms.getAlarm(alarmId)
        if (alarm == null || check.reRings >= alarm.wakeCheck.maxReRings) {
            abandon(alarmId, WakeCheckStatus.FAILED)
            diagnostics.warn("WakeCheck", "alarm $alarmId: no answer and re-ring limit reached")
            return null
        }
        checks.save(check.copy(reRings = check.reRings + 1, deadlineAt = 0))
        val event = history.get(check.eventId)
        if (event != null) {
            history.update(event.copy(outcome = com.riyadm.wakenudge.domain.model.AlarmOutcome.RINGING, endedAt = null, wakeCheckReRings = check.reRings + 1))
        }
        diagnostics.warn("WakeCheck", "alarm $alarmId: no answer, ringing again (${check.reRings + 1}/${alarm.wakeCheck.maxReRings})")
        return RingRequest(alarmId, check.eventId, event?.scheduledAt ?: time.nowMillis(), RingKind.WAKE_CHECK_RERING)
    }

    /**
     * Drops any check for the alarm, e.g. because it was deleted or emergency-stopped. [checkOver] says the check ended
     * on its own terms (failed, cancelled from its prompt, emergency stop), which lets a delete-after-use alarm go now;
     * when the user changed the alarm (switched it off or on, edited or deleted it) that is their decision instead.
     */
    suspend fun abandon(alarmId: Long, status: WakeCheckStatus?, checkOver: Boolean = true) {
        val check = checks.get(alarmId)
        pending.cancel(TriggerKind.WAKE_CHECK_PROMPT, alarmId)
        pending.cancel(TriggerKind.WAKE_CHECK_DEADLINE, alarmId)
        notifier.cancelWakeCheck(alarmId)
        checks.delete(alarmId)
        if (check != null && status != null) setEventStatus(check.eventId, status)
        if (check != null && checkOver) deleteIfUsedUp(alarmId)
    }

    /** A delete-after-use alarm waits for its check to end (it may have to ring again); now it can go. */
    private suspend fun deleteIfUsedUp(alarmId: Long) {
        val alarm = alarms.getAlarm(alarmId) ?: return
        if (alarm.deleteAfterUse && !alarm.isRepeating && !alarm.enabled) {
            alarms.delete(alarmId)
            pending.cancel(TriggerKind.ALARM, alarmId)
            diagnostics.info("WakeCheck", "alarm $alarmId deleted after use, now that its check is over")
        }
    }

    /**
     * How often the check of this ring ([eventId]) has already rung the alarm again, so a re-ring that was snoozed and
     * then dismissed keeps counting towards the limit. A check row left over from another ring does not count.
     */
    suspend fun reRingsFor(alarmId: Long, eventId: Long): Int = checks.get(alarmId)?.takeIf { it.eventId == eventId }?.reRings ?: 0

    private suspend fun setEventStatus(eventId: Long, status: WakeCheckStatus) {
        val e = history.get(eventId) ?: return
        history.update(e.copy(wakeCheck = status))
    }
}
