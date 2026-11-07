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
import com.riyadm.wakenudge.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The "Still awake?" follow-up. State lives in the database and in AlarmManager triggers, never in memory, so a
 * check survives process death and reboot. Flow:
 *  dismissal -> WAITING (prompt due after the delay) -> PROMPTED (deadline armed) ->
 *     answered   : next check, or PASSED when all checks are done
 *     unanswered : the alarm rings again (up to a limit), then the check is recorded as FAILED.
 *
 * With `requireProof` a sleeper cannot answer it: no tap answers or cancels it, only a round of Focus Tap on its screen
 * ([WakeProof.FOCUS_TAP]), or the hardware step counter having counted [WakeProofPolicy.STEPS_NEEDED] steps since the
 * dismissal, in which case it passes on its own without a prompt. Its prompt also comes up to three minutes later than
 * the delay, at random, so it cannot be waited out to the second.
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
    private val runtime: RuntimeState,
    private val steps: StepTotal,
    @AppScope private val scope: CoroutineScope,
) {
    /** Where the extra delay of a check that needs proof comes from; tests pin it. */
    internal var random: Random = Random.Default

    /** Called right after a real dismissal; arms the first prompt. [reRings] carries over earlier unanswered checks. */
    suspend fun startAfterDismissal(alarm: Alarm, eventId: Long, reRings: Int) {
        if (!alarm.wakeCheck.enabled || alarm.id < 0) return
        val promptAt = time.nowMillis() + delayMs(alarm)
        if (alarm.wakeCheck.requireProof) takeStepBaseline(alarm.id)
        checks.save(WakeCheck(alarm.id, eventId, WakeCheck.Phase.WAITING, promptAt, 0, checksDone = 0, reRings = reRings))
        pending.setExact(TriggerKind.WAKE_CHECK_PROMPT, alarm.id, promptAt)
        setEventStatus(eventId, WakeCheckStatus.PENDING)
        diagnostics.info("WakeCheck", "armed for alarm ${alarm.id}: prompt in ${alarm.wakeCheck.delayMinutes} min")
    }

    private fun delayMs(alarm: Alarm): Long = alarm.wakeCheck.delayMinutes.coerceIn(1, 30) * 60_000L +
        if (alarm.wakeCheck.requireProof) WakeProofPolicy.extraDelayMs(random) else 0L

    /**
     * The step counter's total now, kept for this check; read off the caller's path, since the sensor can take a moment
     * to answer and the caller is a dismissal. No counter, or no answer, means no step proof: Focus Tap still answers.
     */
    private fun takeStepBaseline(alarmId: Long) {
        runtime.setWakeProofBaseline(alarmId, null)
        scope.launch {
            val total = try { steps.read() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { null }
            runtime.setWakeProofBaseline(alarmId, total)
        }
    }

    /** The prompt is due: ask the question and arm the deadline, unless enough steps already answered it. */
    suspend fun onPrompt(alarmId: Long) {
        val check = checks.get(alarmId) ?: return
        val alarm = alarms.getAlarm(alarmId) ?: return abandon(alarmId, WakeCheckStatus.CANCELLED)
        if (alarm.wakeCheck.requireProof && check.phase == WakeCheck.Phase.WAITING) {
            val walked = WakeProofPolicy.stepsAnswer(runtime.wakeProofBaseline(alarmId), steps.read())
            if (walked) {
                diagnostics.info("WakeCheck", "alarm $alarmId: ${WakeProofPolicy.STEPS_NEEDED}+ steps since the dismissal answer the check")
                answer(alarmId, WakeProof.STEPS)
                return
            }
        }
        val deadline = time.nowMillis() + alarm.wakeCheck.responseSeconds.coerceIn(15, 600) * 1000L
        checks.save(check.copy(phase = WakeCheck.Phase.PROMPTED, deadlineAt = deadline))
        pending.setAlarmClock(TriggerKind.WAKE_CHECK_DEADLINE, alarmId, deadline)
        notifier.showWakeCheck(alarm, deadline, check.eventId)
        diagnostics.info("WakeCheck", "prompted alarm $alarmId, answer by $deadline")
    }

    /**
     * The user confirmed they are awake with [proof]. Returns false when that is not enough: an alarm that asks for
     * proof ignores a plain answer (a tap, a notification button left from before), and the check runs its course.
     */
    suspend fun answer(alarmId: Long, proof: WakeProof = WakeProof.NONE): Boolean {
        val check = checks.get(alarmId) ?: return false
        val alarm = alarms.getAlarm(alarmId)
        if (alarm != null && !WakeProofPolicy.accepts(alarm.wakeCheck.requireProof, proof)) {
            diagnostics.warn("WakeCheck", "alarm $alarmId: an answer without proof was ignored")
            return false
        }
        pending.cancel(TriggerKind.WAKE_CHECK_DEADLINE, alarmId)
        notifier.cancelWakeCheck(alarmId)
        val done = check.checksDone + 1
        if (alarm == null || done >= alarm.wakeCheck.maxChecks) {
            runtime.setWakeProofBaseline(alarmId, null)
            checks.delete(alarmId)
            setEventStatus(check.eventId, WakeCheckStatus.PASSED)
            diagnostics.info("WakeCheck", "alarm $alarmId passed all checks")
            deleteIfUsedUp(alarmId)
        } else {
            val nextAt = time.nowMillis() + delayMs(alarm)
            // The next check counts its own steps, from now.
            if (alarm.wakeCheck.requireProof) takeStepBaseline(alarmId)
            checks.save(check.copy(phase = WakeCheck.Phase.WAITING, promptAt = nextAt, deadlineAt = 0, checksDone = done))
            pending.setExact(TriggerKind.WAKE_CHECK_PROMPT, alarmId, nextAt)
        }
        return true
    }

    /**
     * Cancel asked for by the user: the notification, the prompt screen or the morning screen. Refused for an alarm that
     * asks for proof, which would otherwise be answered by its Cancel. Returns whether the check was cancelled.
     */
    suspend fun cancelByUser(alarmId: Long): Boolean {
        val alarm = alarms.getAlarm(alarmId)
        if (alarm?.wakeCheck?.requireProof == true) {
            diagnostics.warn("WakeCheck", "alarm $alarmId: cancel refused, the check needs proof")
            return false
        }
        abandon(alarmId, WakeCheckStatus.CANCELLED)
        return true
    }

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
        runtime.setWakeProofBaseline(alarmId, null)
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
