package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.domain.model.VibrationSettings
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.repository.TimerRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.SnoozePolicy
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import com.riyadm.wakenudge.engine.notify.Notifier
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SnoozeOutcome {
    data class Snoozed(val untilMillis: Long, val minutes: Int) : SnoozeOutcome
    data object NotAllowed : SnoozeOutcome
    data object NotRinging : SnoozeOutcome
}

object RingIds {
    const val TEST_ALARM_ID = -1L
    /** Timers ring through the alarm pipeline under id TIMER_ALARM_BASE - timerId. */
    const val TIMER_ALARM_BASE = -1000L
    const val MAX_BACKUPS = 3
    const val INACTIVITY_ESCALATION_MS = 40_000L
    const val SILENCE_MS = 30_000L
}

/**
 * The state machine of a ringing alarm. The service plays sound and shows UI in reaction to [RingSessionStore];
 * everything that changes history, schedules follow-ups or ends a session lives here so it can be reasoned about
 * (and tested) in one place. All mutations are serialized by a mutex, so a double tap on Dismiss or a snooze racing
 * a timeout cannot corrupt state.
 */
@Singleton
class RingController @Inject constructor(
    private val store: RingSessionStore,
    private val alarms: AlarmRepository,
    private val history: HistoryRepository,
    private val timers: TimerRepository,
    private val settings: SettingsRepository,
    private val scheduler: AlarmScheduler,
    private val wakeChecks: WakeCheckController,
    private val pending: PendingAlarms,
    private val runtime: RuntimeState,
    private val time: TimeSource,
    private val notifier: Notifier,
    private val surfaces: SurfaceRefresher,
    private val diagnostics: DiagnosticsLog,
) {
    private val mutex = Mutex()

    /** Alarm as it was when it fired, in case a one-time alarm is edited or removed while it rings. */
    private val snapshots = ConcurrentHashMap<Long, Alarm>()

    // ---- triggers (from AlarmManager) ----------------------------------------------------------------------

    suspend fun onAlarmFired(alarmId: Long, scheduledAt: Long): RingRequest? = mutex.withLock {
        val now = time.nowMillis()
        runtime.lastFireAt = now
        if (alarmId == RingIds.TEST_ALARM_ID) {
            return@withLock RingRequest(alarmId, -1, scheduledAt, RingKind.TEST)
        }
        val alarm = alarms.getAlarm(alarmId)
        if (alarm == null) {
            diagnostics.warn("Ring", "trigger for unknown alarm $alarmId ignored")
            scheduler.cancel(alarmId)
            return@withLock null
        }
        if (!alarm.enabled) {
            diagnostics.warn("Ring", "trigger for disabled alarm $alarmId ignored")
            return@withLock null
        }
        snapshots[alarmId] = alarm
        afterFire(alarm, scheduledAt)

        val existing = history.findOccurrence(alarmId, scheduledAt)
        if (existing != null && existing.outcome != AlarmOutcome.RINGING) {
            diagnostics.warn("Ring", "duplicate trigger for $alarmId@$scheduledAt ignored")
            return@withLock null
        }
        val eventId = existing?.id ?: history.start(
            AlarmEvent(alarmId = alarmId, label = alarm.label, scheduledAt = scheduledAt, firedAt = now, missionsTotal = alarm.missions.size),
        )
        notifier.cancelUpcoming(alarmId)
        scheduleBackups(alarm, eventId, now)
        diagnostics.info("Ring", "alarm $alarmId fired (scheduled $scheduledAt, late ${now - scheduledAt} ms)")
        RingRequest(alarmId, eventId, scheduledAt, RingKind.PRIMARY)
    }

    suspend fun onSunrise(alarmId: Long, scheduledAt: Long): RingRequest? = mutex.withLock {
        val alarm = alarms.getAlarm(alarmId) ?: return@withLock null
        if (!alarm.enabled || store.current?.phase == RingPhase.RINGING) return@withLock null
        RingRequest(alarmId, -1, scheduledAt, RingKind.PRIMARY, phase = RingPhase.SUNRISE)
    }

    suspend fun onSnoozeExpired(alarmId: Long, eventId: Long): RingRequest? = mutex.withLock {
        notifier.cancelSnoozed(alarmId)
        if (alarmId == RingIds.TEST_ALARM_ID) {
            return@withLock RingRequest(alarmId, -1, time.nowMillis(), RingKind.TEST)
        }
        val event = history.get(eventId)
        val alarm = alarms.getAlarm(alarmId)
        if (event == null || alarm == null || event.outcome != AlarmOutcome.RINGING) {
            diagnostics.warn("Ring", "snooze expiry for $alarmId has nothing to ring")
            return@withLock null
        }
        snapshots[alarmId] = alarm
        history.update(event.copy(snoozeUntil = 0))
        scheduleBackups(alarm, eventId, time.nowMillis())
        RingRequest(alarmId, eventId, event.scheduledAt, RingKind.SNOOZE_RETURN, snoozeCount = event.snoozeCount)
    }

    suspend fun onBackupFired(alarmId: Long, eventId: Long, index: Int): RingRequest? = mutex.withLock {
        val event = history.get(eventId) ?: return@withLock null
        // Anything but "still unresolved" means the user already handled it.
        if (event.outcome != AlarmOutcome.RINGING && event.outcome != AlarmOutcome.MISSED) return@withLock null
        val alarm = alarms.getAlarm(alarmId) ?: return@withLock null
        snapshots[alarmId] = alarm
        history.update(
            event.copy(
                outcome = AlarmOutcome.RINGING, endedAt = null, backupFired = true,
                pendingBackups = event.pendingBackups.drop(1),
            ),
        )
        diagnostics.warn("Ring", "backup #$index fired for alarm $alarmId — primary was not handled")
        RingRequest(alarmId, eventId, event.scheduledAt, RingKind.BACKUP, snoozeCount = event.snoozeCount)
    }

    suspend fun onWakeCheckDeadline(alarmId: Long): RingRequest? {
        val request = wakeChecks.onDeadline(alarmId) ?: return null
        mutex.withLock {
            alarms.getAlarm(request.alarmId)?.let {
                snapshots[alarmId] = it
                scheduleBackups(it, request.eventId, time.nowMillis())
            }
        }
        return request
    }

    suspend fun onTimerFired(timerId: Long): RingRequest? = mutex.withLock {
        val timer = timers.getTimer(timerId) ?: return@withLock null
        if (timer.state != TimerState.RUNNING) return@withLock null
        timers.save(timer.copy(state = TimerState.RINGING, remainingMs = 0))
        notifier.cancelTimer(timerId)
        surfaces.refreshAll()
        RingRequest(RingIds.TIMER_ALARM_BASE - timerId, -1, time.nowMillis(), RingKind.TIMER, timerId = timerId)
    }

    suspend fun onUpcoming(alarmId: Long, scheduledAt: Long) {
        val alarm = alarms.getAlarm(alarmId) ?: return
        if (!alarm.enabled) return
        val lock = alarm.antiOversleep.editLockMinutes
        val locked = lock > 0 && scheduledAt - time.nowMillis() <= lock * 60_000L
        notifier.showUpcoming(alarm, scheduledAt, canSkip = alarm.isRepeating && !locked)
    }

    // ---- session lifecycle (from the service) --------------------------------------------------------------

    suspend fun begin(request: RingRequest): RingSession? = mutex.withLock {
        val now = time.nowMillis()
        val appSettings = settings.current()
        val alarm = loadForRing(request, appSettings) ?: return@withLock null

        val existing = store.current
        if (existing != null && existing.alarm.id == alarm.id && !existing.isTimer) {
            // Same alarm already on screen: sunrise turning into the ring, or a backup upgrading a live ring.
            val ringing = existing.phase == RingPhase.RINGING || request.phase == RingPhase.RINGING
            val upgraded = existing.copy(
                phase = if (ringing) RingPhase.RINGING else RingPhase.SUNRISE,
                kind = if (request.kind == RingKind.BACKUP) RingKind.BACKUP else existing.kind,
                intensified = existing.intensified || request.kind == RingKind.BACKUP,
                eventId = if (request.eventId > 0) request.eventId else existing.eventId,
                scheduledAt = if (request.phase == RingPhase.RINGING) request.scheduledAt else existing.scheduledAt,
                startedAt = if (existing.phase == RingPhase.SUNRISE && ringing) now else existing.startedAt,
                lastInteractionAt = now,
            )
            store.set(upgraded)
            return@withLock upgraded
        }
        if (existing != null) endAsMissed(existing, now)

        val session = RingSession(
            alarm = alarm,
            kind = request.kind,
            phase = request.phase,
            eventId = request.eventId,
            scheduledAt = request.scheduledAt,
            startedAt = now,
            snoozeCount = request.snoozeCount,
            intensified = request.kind == RingKind.BACKUP,
            lastInteractionAt = now,
            timerId = request.timerId,
        )
        store.set(session)
        runtime.activeAlarmId = alarm.id
        runtime.activeEventId = request.eventId
        surfaces.refreshAll()
        session
    }

    /** Called for every touch/key/mission step on the ring screen; calms an escalated alarm down. */
    fun noteInteraction() {
        val now = time.nowMillis()
        store.update { s ->
            if (now - s.lastInteractionAt < 1_000 && !s.intensified) return@update s
            s.copy(lastInteractionAt = now, intensified = s.kind == RingKind.BACKUP)
        }
    }

    fun silenceTemporarily() {
        val now = time.nowMillis()
        store.update { it.copy(silencedUntil = now + RingIds.SILENCE_MS, lastInteractionAt = now) }
    }

    fun noteMissionDone(count: Int) = store.update { it.copy(missionsDone = count) }

    /** Called about once a second by the service while a session is active. */
    suspend fun tick() {
        val s = store.current ?: return
        val now = time.nowMillis()
        if (s.silencedUntil in 1..now) store.update { it.copy(silencedUntil = 0) }

        if (s.phase == RingPhase.SUNRISE) {
            val limit = (s.alarm.sunrise.leadMinutes + SUNRISE_GRACE_MINUTES) * 60_000L
            if (now - s.startedAt > limit) mutex.withLock { store.current?.let { endSilently(it) } }
            return
        }
        if (!s.intensified && s.alarm.volume.escalateWhenIgnored && !s.isTimer &&
            now - s.lastInteractionAt >= RingIds.INACTIVITY_ESCALATION_MS
        ) {
            store.update { it.copy(intensified = true) }
            diagnostics.info("Ring", "alarm ${s.alarm.id} ignored for 40 s: intensifying")
        }
        val timeoutMs = s.alarm.antiOversleep.ringTimeoutMinutes.coerceAtLeast(1) * 60_000L
        if (now - s.lastInteractionAt >= timeoutMs) onRingTimeout()
    }

    // ---- user decisions ------------------------------------------------------------------------------------

    suspend fun snooze(): SnoozeOutcome = mutex.withLock {
        val s = store.current ?: return@withLock SnoozeOutcome.NotRinging
        if (s.isTimer || !SnoozePolicy.canSnooze(s.alarm.snooze, s.snoozeCount)) return@withLock SnoozeOutcome.NotAllowed
        val minutes = SnoozePolicy.nextIntervalMinutes(s.alarm.snooze, s.snoozeCount)
        val now = time.nowMillis()
        val until = now + minutes * 60_000L

        pending.setAlarmClock(TriggerKind.SNOOZE, s.alarm.id, until, eventId = s.eventId)
        cancelBackups(s.alarm.id)
        if (s.eventId > 0) {
            history.get(s.eventId)?.let {
                history.update(it.copy(snoozeCount = s.snoozeCount + 1, snoozeUntil = until, pendingBackups = emptyList()))
            }
        }
        finishSession(s)
        notifier.showSnoozed(s.alarm, until)
        diagnostics.info("Ring", "alarm ${s.alarm.id} snoozed ${s.snoozeCount + 1}x for $minutes min")
        SnoozeOutcome.Snoozed(until, minutes)
    }

    /** The user finished whatever the alarm required (missions, hold) and dismisses it for real. */
    suspend fun dismiss() = mutex.withLock {
        val s = store.current ?: return@withLock
        endSession(s, AlarmOutcome.DISMISSED)
    }

    /** The accessible escape hatch: ends the alarm without missions. Recorded honestly as an emergency stop. */
    suspend fun emergencyStop() = mutex.withLock {
        val s = store.current ?: return@withLock
        endSession(s, AlarmOutcome.EMERGENCY_STOPPED)
        diagnostics.warn("Ring", "alarm ${s.alarm.id} ended by emergency stop")
    }

    /** Dismissal from a notification while snoozed (no ring session is active). */
    suspend fun dismissSnoozed(alarmId: Long) = mutex.withLock {
        pending.cancel(TriggerKind.SNOOZE, alarmId)
        cancelBackups(alarmId)
        notifier.cancelSnoozed(alarmId)
        val open = history.recent(30).firstOrNull { it.alarmId == alarmId && it.outcome == AlarmOutcome.RINGING }
        if (open != null) history.update(open.copy(outcome = AlarmOutcome.DISMISSED, endedAt = time.nowMillis()))
        surfaces.refreshAll()
    }

    /** Ring now instead of waiting out the snooze. */
    suspend fun wakeNow(alarmId: Long): RingRequest? {
        pending.cancel(TriggerKind.SNOOZE, alarmId)
        val open = history.recent(30).firstOrNull { it.alarmId == alarmId && it.outcome == AlarmOutcome.RINGING }
        return onSnoozeExpired(alarmId, open?.id ?: -1)
    }

    // ---- timers --------------------------------------------------------------------------------------------

    suspend fun addMinuteToRingingTimer() = mutex.withLock {
        val s = store.current ?: return@withLock
        if (!s.isTimer) return@withLock
        val timer = timers.getTimer(s.timerId)
        if (timer != null) {
            val endsAt = time.nowMillis() + 60_000
            timers.save(timer.copy(state = TimerState.RUNNING, endsAtMillis = endsAt, remainingMs = 60_000))
            pending.setExact(TriggerKind.TIMER, timer.id, endsAt)
            timers.getTimer(timer.id)?.let { notifier.showTimerRunning(it) }
        }
        finishSession(s)
    }

    // ---- ending sessions -----------------------------------------------------------------------------------

    private suspend fun onRingTimeout() = mutex.withLock {
        val s = store.current ?: return@withLock
        if (s.phase != RingPhase.RINGING) return@withLock
        val now = time.nowMillis()
        if (s.isTimer) {
            timers.getTimer(s.timerId)?.let { timers.save(it.copy(state = TimerState.IDLE, remainingMs = it.durationMs)) }
            finishSession(s)
            return@withLock
        }
        diagnostics.warn("Ring", "alarm ${s.alarm.id} ignored until the ring timeout")
        if (s.eventId > 0) {
            history.get(s.eventId)?.let {
                history.update(it.copy(outcome = AlarmOutcome.MISSED, endedAt = now, snoozeCount = s.snoozeCount))
            }
        }
        val backupsLeft = (1..RingIds.MAX_BACKUPS).any { pending.isScheduled(TriggerKind.BACKUP, s.alarm.id, it) }
        finishSession(s)
        if (!backupsLeft && !s.isTest) notifier.showMissed(s.alarm, s.scheduledAt)
    }

    private suspend fun endSession(s: RingSession, outcome: AlarmOutcome) {
        val now = time.nowMillis()
        if (s.isTimer) {
            timers.getTimer(s.timerId)?.let { timers.save(it.copy(state = TimerState.IDLE, remainingMs = it.durationMs)) }
            finishSession(s)
            return
        }
        cancelBackups(s.alarm.id)
        pending.cancel(TriggerKind.SNOOZE, s.alarm.id)
        notifier.cancelSnoozed(s.alarm.id)

        if (s.eventId > 0) {
            history.get(s.eventId)?.let { e ->
                history.update(
                    e.copy(
                        outcome = outcome,
                        endedAt = now,
                        snoozeUntil = 0,
                        pendingBackups = emptyList(),
                        snoozeCount = s.snoozeCount,
                        missionsDone = if (outcome == AlarmOutcome.DISMISSED) s.alarm.missions.size else s.missionsDone,
                        missionsTotal = s.alarm.missions.size,
                        wakeCheck = if (outcome == AlarmOutcome.DISMISSED && s.alarm.wakeCheck.enabled) WakeCheckStatus.PENDING else e.wakeCheck,
                    ),
                )
            }
        }
        if (outcome == AlarmOutcome.DISMISSED && !s.isTest) {
            if (s.alarm.wakeCheck.enabled) {
                val reRings = if (s.kind == RingKind.WAKE_CHECK_RERING) wakeChecks.reRingsFor(s.alarm.id) else 0
                wakeChecks.startAfterDismissal(s.alarm, s.eventId, reRings)
            }
            if (s.alarm.deleteAfterUse && !s.alarm.isRepeating) {
                alarms.delete(s.alarm.id)
                scheduler.cancel(s.alarm.id)
            }
        } else if (outcome == AlarmOutcome.EMERGENCY_STOPPED) {
            wakeChecks.abandon(s.alarm.id, WakeCheckStatus.NONE)
        }
        finishSession(s)
    }

    private suspend fun endAsMissed(s: RingSession, now: Long) {
        if (s.eventId > 0) {
            history.get(s.eventId)?.let {
                if (it.outcome == AlarmOutcome.RINGING) history.update(it.copy(outcome = AlarmOutcome.MISSED, endedAt = now, snoozeCount = s.snoozeCount))
            }
        }
        diagnostics.warn("Ring", "alarm ${s.alarm.id} replaced by another ring; marked missed")
    }

    private suspend fun endSilently(s: RingSession) = finishSession(s)

    private fun finishSession(s: RingSession) {
        snapshots.remove(s.alarm.id)
        store.set(null)
        runtime.activeAlarmId = -1
        runtime.activeEventId = -1
        notifier.cancelRing()
        surfaces.refreshAll()
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private suspend fun afterFire(alarm: Alarm, scheduledAt: Long) {
        if (alarm.isRepeating) {
            scheduler.schedule(alarm.id, afterMillis = scheduledAt + 1_000)
        } else {
            alarms.setEnabled(alarm.id, false)
            scheduler.cancel(alarm.id)
        }
        surfaces.refreshAll()
    }

    private suspend fun scheduleBackups(alarm: Alarm, eventId: Long, firedAt: Long) {
        cancelBackups(alarm.id)
        val ao = alarm.antiOversleep
        if (!ao.backupEnabled || ao.backupCount <= 0) return
        val times = (1..ao.backupCount.coerceAtMost(RingIds.MAX_BACKUPS)).map { i ->
            val at = firedAt + ao.backupDelayMinutes.coerceAtLeast(1) * 60_000L * i
            pending.setAlarmClock(TriggerKind.BACKUP, alarm.id, at, index = i, eventId = eventId)
            at
        }
        // Remember them: the OS drops all alarms on reboot/update and the scheduler re-arms from this list.
        if (eventId > 0) history.get(eventId)?.let { history.update(it.copy(pendingBackups = times)) }
    }

    private fun cancelBackups(alarmId: Long) {
        for (i in 1..RingIds.MAX_BACKUPS) pending.cancel(TriggerKind.BACKUP, alarmId, i)
    }

    private suspend fun loadForRing(request: RingRequest, appSettings: AppSettings): Alarm? {
        return when (request.kind) {
            RingKind.TIMER -> timers.getTimer(request.timerId)?.let { timerAlarm(it, appSettings) }
            RingKind.TEST -> runtime.testAlarmJson?.let {
                runCatching { WakeJson.instance.decodeFromString(Alarm.serializer(), it) }.getOrNull()
            } ?: Alarm(id = RingIds.TEST_ALARM_ID, label = "Test alarm")
            else -> snapshots[request.alarmId] ?: alarms.getAlarm(request.alarmId) ?: run {
                // The alarm vanished (e.g. deleted mid-ring). Ring anyway rather than stay silent.
                diagnostics.warn("Ring", "alarm ${request.alarmId} missing; ringing a default alarm")
                Alarm(id = request.alarmId, label = "Alarm")
            }
        }
    }

    private fun timerAlarm(timer: CountdownTimer, s: AppSettings) = Alarm(
        id = RingIds.TIMER_ALARM_BASE - timer.id,
        label = timer.label,
        sound = s.timerSound,
        vibration = VibrationSettings(enabled = s.timerVibrate),
        volume = VolumeSettings(level = 0, rampSeconds = 4, extraLoud = false, escalateWhenIgnored = false),
        snooze = SnoozeSettings(enabled = false),
        antiOversleep = AntiOversleepSettings(backupEnabled = false, ringTimeoutMinutes = 3),
    )

    // ---- recovery ------------------------------------------------------------------------------------------

    /** After a crash/reboot: closes events that can no longer be ringing and restores the system volume. */
    suspend fun recoverStaleState() = mutex.withLock {
        if (store.current != null) return@withLock
        val now = time.nowMillis()
        val staleBefore = now - STALE_RING_MS
        history.openEvents().forEach { e ->
            val hasFollowUp = pending.isScheduled(TriggerKind.SNOOZE, e.alarmId) ||
                (1..RingIds.MAX_BACKUPS).any { pending.isScheduled(TriggerKind.BACKUP, e.alarmId, it) }
            if (e.firedAt < staleBefore && !hasFollowUp) {
                history.update(e.copy(outcome = AlarmOutcome.MISSED, endedAt = e.endedAt ?: now))
            }
        }
        if (runtime.activeAlarmId != -1L) {
            diagnostics.warn("Ring", "recovering from a ring session that did not end cleanly")
            runtime.activeAlarmId = -1
            runtime.activeEventId = -1
        }
    }

    private companion object {
        const val SUNRISE_GRACE_MINUTES = 10
        const val STALE_RING_MS = 60 * 60_000L
    }
}
