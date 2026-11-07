package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.BuildConfig
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
import com.riyadm.wakenudge.domain.scheduling.MissionRotation
import com.riyadm.wakenudge.domain.scheduling.SnoozePolicy
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.engine.policy.DenyReason
import com.riyadm.wakenudge.engine.policy.RingCommand
import com.riyadm.wakenudge.engine.policy.RingDecision
import com.riyadm.wakenudge.engine.policy.RingPolicy
import com.riyadm.wakenudge.engine.policy.RingProof
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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

    /** The sentinel trigger: a ring killed without warning is brought back this soon, plus the time to start again. */
    const val SENTINEL_MS = 30_000L
    /** Refreshed this often while the ring runs, so it only fires when the process is really gone. */
    const val SENTINEL_REFRESH_MS = 20_000L
    /** How soon a ring left behind by a process that is gone comes back, once something notices it. */
    const val RESUME_DELAY_MS = 2_000L
    const val SENTINEL_TRIGGER_ID = 0L
}

/**
 * The state machine of a ringing alarm. The service plays sound and shows UI in reaction to [RingSessionStore];
 * everything that changes history, schedules follow-ups or ends a session lives here so it can be reasoned about
 * (and tested) in one place. All mutations are serialized by a mutex, so a double tap on Dismiss or a snooze racing
 * a timeout cannot corrupt state.
 *
 * One rule runs through it: **persistence never decides whether an alarm rings or stops.** A full disk, a locked or
 * corrupt database or a broken settings file is logged and the ring carries on with safe defaults; history, follow-up
 * bookkeeping and settings are best-effort around the sound, never in front of it.
 *
 * What may end or quiet a ring is decided in one place, [RingPolicy]: every command below asks it before acting.
 *
 * A ring also lasts longer than the process it started in: [DurableRingRecord] holds what it must not lose, and a
 * [TriggerKind.RING_SENTINEL] trigger brings it back if the process dies without warning. The same rule applies to that
 * state as to any other storage: a failed read of it never stops or prevents a ring.
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

    private val durable = DurableRingStore(runtime, diagnostics)
    private val armedCopies = AlarmSnapshots(runtime, diagnostics)

    /** Keeps "the session ended" and "its record is gone" one step, against a mission step arriving from the screen. */
    private val recordLock = Any()

    /** On the elapsed clock; when the sentinel was last pushed forward. */
    @Volatile private var sentinelArmedAt = 0L

    // ---- triggers (from AlarmManager) ----------------------------------------------------------------------

    suspend fun onAlarmFired(alarmId: Long, scheduledAt: Long): RingRequest? = mutex.withLock {
        val now = time.nowMillis()
        runtime.lastFireAt = now
        if (alarmId == RingIds.TEST_ALARM_ID) {
            return@withLock RingRequest(alarmId, -1, scheduledAt, RingKind.TEST)
        }
        val alarm = try {
            alarms.getAlarm(alarmId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // The OS only fires triggers this app registered, so a trigger is a real alarm even if the database can't be read.
            diagnostics.error("Ring", "could not read alarm $alarmId; ringing with safe defaults", t)
            return@withLock RingRequest(alarmId, -1, scheduledAt, RingKind.PRIMARY)
        }
        if (alarm == null) {
            if (alarmId in runtime.registeredTriggers()) {
                // Armed by us and never cancelled, yet gone from the database: it was lost (reset or corrupted), not deleted.
                diagnostics.error("Ring", "alarm $alarmId is armed but missing from the database; ringing with safe defaults")
                runtime.setRegisteredTriggers(runtime.registeredTriggers() - alarmId)
                return@withLock RingRequest(alarmId, -1, scheduledAt, RingKind.PRIMARY)
            }
            diagnostics.warn("Ring", "trigger for unknown alarm $alarmId ignored")
            scheduler.cancel(alarmId)
            return@withLock null
        }
        if (!alarm.enabled) {
            diagnostics.warn("Ring", "trigger for disabled alarm $alarmId ignored")
            return@withLock null
        }
        snapshots[alarmId] = alarm
        tryDb("re-arming after alarm $alarmId fired", Unit) { afterFire(alarm, scheduledAt) }

        val existing = tryDb("looking up alarm $alarmId's history", null) { history.findOccurrence(alarmId, scheduledAt) }
        if (existing != null) {
            // Already handled, already on screen, or deliberately snoozed: a replayed trigger must not ring again.
            val onScreen = store.current?.alarm?.id == alarmId
            val snoozed = existing.snoozeUntil > now
            if (existing.outcome != AlarmOutcome.RINGING || onScreen || snoozed) {
                diagnostics.warn("Ring", "duplicate trigger for $alarmId@$scheduledAt ignored")
                return@withLock null
            }
        }
        val eventId = existing?.id ?: tryDb("recording the ring of alarm $alarmId", -1L) {
            history.start(
                AlarmEvent(alarmId = alarmId, label = alarm.label, scheduledAt = scheduledAt, firedAt = now, missionsTotal = alarm.missions.size),
            )
        }
        notifier.cancelUpcoming(alarmId)
        tryDb("arming backups for alarm $alarmId", Unit) { scheduleBackups(alarm, eventId, now) }
        diagnostics.info("Ring", "alarm $alarmId fired (scheduled $scheduledAt, late ${now - scheduledAt} ms)")
        RingRequest(alarmId, eventId, scheduledAt, RingKind.PRIMARY)
    }

    suspend fun onSunrise(alarmId: Long, scheduledAt: Long): RingRequest? = mutex.withLock {
        val alarm = alarms.getAlarm(alarmId) ?: return@withLock null
        if (!alarm.enabled || store.current?.phase == RingPhase.RINGING) return@withLock null
        RingRequest(alarmId, -1, scheduledAt, RingKind.PRIMARY, phase = RingPhase.SUNRISE)
    }

    suspend fun onSnoozeExpired(alarmId: Long, eventId: Long): RingRequest? = mutex.withLock {
        // A fired one-shot PendingIntent can linger and still be "found": drop it, so nothing (the edit lock, the
        // missed-alarm notice) goes on believing a snooze is pending.
        pending.cancel(TriggerKind.SNOOZE, alarmId)
        notifier.cancelSnoozed(alarmId)
        if (alarmId == RingIds.TEST_ALARM_ID) {
            return@withLock RingRequest(alarmId, -1, time.nowMillis(), RingKind.TEST)
        }
        val event = if (eventId > 0) history.get(eventId) else null
        val alarm = alarms.getAlarm(alarmId)
        // No event id at all means the snooze was set while the history could not be written: the user still asked
        // to be woken, so that rings. A positive id must still point at an unresolved event.
        if (alarm == null || (eventId > 0 && (event == null || event.outcome != AlarmOutcome.RINGING))) {
            diagnostics.warn("Ring", "snooze expiry for $alarmId has nothing to ring")
            return@withLock null
        }
        snapshots[alarmId] = alarm
        if (event != null) tryDb("clearing the snooze of alarm $alarmId", Unit) { history.update(event.copy(snoozeUntil = 0)) }
        tryDb("arming backups for alarm $alarmId", Unit) { scheduleBackups(alarm, eventId, time.nowMillis()) }
        RingRequest(alarmId, eventId, event?.scheduledAt ?: time.nowMillis(), RingKind.SNOOZE_RETURN, snoozeCount = event?.snoozeCount ?: 0)
    }

    suspend fun onBackupFired(alarmId: Long, eventId: Long, index: Int): RingRequest? = mutex.withLock {
        // As for snoozes: this backup has fired, so it must stop counting as one still to come.
        pending.cancel(TriggerKind.BACKUP, alarmId, index)
        val event = if (eventId > 0) history.get(eventId) else null
        if (eventId > 0 && event == null) return@withLock null
        // Anything but "still unresolved" means the user already handled it.
        if (event != null && event.outcome != AlarmOutcome.RINGING && event.outcome != AlarmOutcome.MISSED) return@withLock null
        val alarm = alarms.getAlarm(alarmId) ?: return@withLock null
        snapshots[alarmId] = alarm
        if (event != null) {
            tryDb("recording the backup ring of alarm $alarmId", Unit) {
                history.update(
                    event.copy(
                        outcome = AlarmOutcome.RINGING, endedAt = null, backupFired = true,
                        pendingBackups = event.pendingBackups.drop(1),
                    ),
                )
            }
        }
        diagnostics.warn("Ring", "backup #$index fired for alarm $alarmId — primary was not handled")
        RingRequest(alarmId, eventId, event?.scheduledAt ?: time.nowMillis(), RingKind.BACKUP, snoozeCount = event?.snoozeCount ?: 0)
    }

    /**
     * Rings again an alarm that was cut off: the ring screen crashed, the service died or the OS refused to start it.
     * Nothing rings if the alarm was handled in the meantime (dismissed, snoozed) or something is already ringing.
     */
    suspend fun onRetry(alarmId: Long, eventId: Long, scheduledAt: Long, kind: RingKind, attempt: Int): RingRequest? = mutex.withLock {
        if (store.current != null) return@withLock null
        val event = if (eventId > 0) tryDb("loading the event to retry", null) { history.get(eventId) } else null
        if (event != null) {
            val unresolved = event.outcome == AlarmOutcome.RINGING || event.outcome == AlarmOutcome.MISSED
            if (!unresolved || event.snoozeUntil > time.nowMillis()) {
                diagnostics.info("Ring", "retry for alarm $alarmId skipped: it was already handled")
                return@withLock null
            }
        }
        diagnostics.warn("Ring", "ringing alarm $alarmId again (retry ${attempt + 1}, $kind)")
        RingRequest(alarmId, eventId, scheduledAt, kind, snoozeCount = event?.snoozeCount ?: 0, attempt = attempt + 1)
    }

    /**
     * Whether a start that Android redelivered after the process died should still ring. Android redelivers every
     * start the service had, including the ring of an alarm the user dismissed or snoozed before the crash (two alarms
     * at once, the second one ringing when the process went down), so an event that is closed, or snoozed into the
     * future, is not rung again. When the history cannot say, it rings: a spurious ring beats a missed one.
     */
    suspend fun stillUnhandled(request: RingRequest): Boolean = mutex.withLock {
        if (request.kind == RingKind.TIMER) {
            // Only a timer the user started (or paused) again is settled. An idle one is what the start-up housekeeping
            // leaves of a timer that was ringing when the process died, and that one must ring again.
            val timer = tryDb("loading timer ${request.timerId}", null) { timers.getTimer(request.timerId) }
            return@withLock timer == null || timer.state == TimerState.RINGING || timer.state == TimerState.IDLE
        }
        if (request.eventId <= 0) return@withLock true
        val event = tryDb("loading the event of a redelivered ring", null) { history.get(request.eventId) } ?: return@withLock true
        val unresolved = event.outcome == AlarmOutcome.RINGING || event.outcome == AlarmOutcome.MISSED
        unresolved && event.snoozeUntil <= time.nowMillis()
    }

    /**
     * A timer whose ring ended goes back to idle, unless the user started it again while it rang (Play on the Timers
     * tab): resetting that one would leave a countdown on screen whose trigger then finds it idle and stays silent.
     */
    private suspend fun resetRungTimer(timerId: Long) {
        timers.getTimer(timerId)?.takeIf { it.state == TimerState.RINGING }?.let { timers.save(it.copy(state = TimerState.IDLE, remainingMs = it.durationMs)) }
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
        // As for alarms: storage trouble never decides whether it rings. Only a timer known to be gone or stopped stays quiet.
        val timer = tryDb("loading timer $timerId", CountdownTimer(id = timerId, durationMs = 0, state = TimerState.RUNNING)) { timers.getTimer(timerId) }
            ?: return@withLock null
        if (timer.state != TimerState.RUNNING) return@withLock null
        tryDb("marking timer $timerId as ringing", Unit) { timers.save(timer.copy(state = TimerState.RINGING, remainingMs = 0)) }
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

    /**
     * Rings that arrived while another alarm was already on screen. Two alarms in the same minute (or a timer ending
     * mid-alarm) must each get their turn instead of the newcomer hijacking the one the user is dealing with.
     */
    private val queued by lazy { java.util.concurrent.ConcurrentLinkedQueue(durable.queue()) }

    /**
     * The next ring waiting for its turn; the service asks for it when the current session ends. It stays in the saved
     * queue until it has begun, so a process dying in between does not lose it.
     */
    fun takeQueued(): RingRequest? = queued.poll()

    private fun enqueue(request: RingRequest) {
        if (queued.none { it.alarmId == request.alarmId && it.eventId == request.eventId }) queued.add(request)
        durable.writeQueue(queued.toList())
    }

    /** The copy of this alarm saved outside the database, if it has one. For when the database cannot be read. Never throws. */
    fun savedCopy(alarmId: Long): Alarm? = armedCopies.get(alarmId)

    /** A ring that begins is no longer waiting, whichever way it got here (its turn came, or Android redelivered it). */
    private fun forgetQueued(request: RingRequest) {
        queued.removeIf { it.alarmId == request.alarmId && it.eventId == request.eventId && it.timerId == request.timerId }
        durable.writeQueue(queued.toList())
    }

    // ---- session lifecycle (from the service) --------------------------------------------------------------

    /** Never throws: if anything inside fails, the ring starts anyway, from the alarm's saved copy or as a plain default. */
    suspend fun begin(request: RingRequest): RingSession? = try {
        beginInternal(request)
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        diagnostics.error("Ring", "starting the ring session failed; ringing from what is left", t)
        emergencySession(request)
    }

    /**
     * A session built from the request and whatever was saved, for when loading the real alarm is impossible. The saved ring
     * still speaks for itself here: it is about to be written over, and with it what it has counted, restarts included.
     */
    private fun emergencySession(request: RingRequest): RingSession {
        store.current?.let { return it }
        val restored = durable.ring()?.takeIf { it.matches(request) && request.phase == RingPhase.RINGING }
        val alarm = restored?.alarm() ?: (armedCopies.get(request.alarmId) ?: Alarm(id = request.alarmId, label = "Alarm")).let { forRingOf(it, request, 0) }
        val session = RingSession(
            alarm = alarm,
            kind = request.kind,
            phase = request.phase,
            eventId = request.eventId.takeIf { it > 0 } ?: restored?.eventId ?: request.eventId,
            scheduledAt = request.scheduledAt,
            startedAt = restored?.startedAtWall ?: time.nowMillis(),
            snoozeCount = maxOf(request.snoozeCount, restored?.snoozeCount ?: 0),
            intensified = request.kind == RingKind.BACKUP || (restored?.resumes ?: 0) > 0,
            lastInteractionAt = time.elapsedMillis(),
            missionsDone = (restored?.missionsDone ?: 0).coerceIn(0, alarm.missions.size),
            timerId = request.timerId,
            mutesUsed = restored?.mutesUsed ?: 0,
            fallback = restored?.fallback ?: false,
            wayOutProgress = restored?.wayOutProgress ?: 0,
            pauses = restored?.pauses ?: 0,
            resumes = restored?.resumes ?: 0,
            pullBacks = restored?.pullBacks ?: 0,
        )
        store.set(session)
        rememberRing(session)
        return session
    }

    private suspend fun beginInternal(incoming: RingRequest): RingSession? = mutex.withLock {
        val now = time.nowMillis()
        val elapsed = time.elapsedMillis()
        val appSettings = tryDb("reading settings", AppSettings()) { settings.current() }
        forgetQueued(incoming)
        val saved = durable.ring()
        // The process died during another alarm's ring: that ring was first, so it goes on and the newcomer waits its turn,
        // as it would have if the process had lived. Otherwise its saved progress would be overwritten by this one.
        val ringing = incoming.phase == RingPhase.RINGING
        // A silent glow is only a lead-in: it does not count as something being on.
        val nothingOn = store.current.let { it == null || it.phase == RingPhase.SUNRISE }
        val request = if (ringing && nothingOn && saved != null && saved.alarmId != incoming.alarmId && ringStillOpen(saved)) {
            enqueue(incoming)
            diagnostics.info("Ring", "alarm ${incoming.alarmId} queued behind ${saved.alarmId}, which was ringing when the process died")
            saved.toRequest().also { forgetQueued(it) }
        } else {
            incoming
        }
        val restored = saved?.takeIf { ringing && nothingOn && it.matches(request) }
        // A later occurrence of the same alarm takes over from one that never ended, which would otherwise stay open for good.
        if (ringing && saved != null && restored == null && nothingOn && saved.alarmId == request.alarmId) closeOccurrenceAsMissed(saved, now)
        // A ring back from the dead asks for what it asked for; a new one picks today's mission if the alarm changes it each morning.
        val alarm = loadForRing(request, appSettings, restored)?.let { if (restored != null) it else forRing(it, request) } ?: return@withLock null

        val existing = store.current
        if (existing != null && existing.alarm.id != alarm.id) {
            when {
                // A sunrise glow is only a lead-in; a real ring simply takes over.
                existing.phase == RingPhase.SUNRISE && request.phase == RingPhase.RINGING -> releaseSession(existing)
                // An alarm outranks a timer: silence the timer and make sure it is not left "ringing" forever.
                existing.isTimer && request.kind != RingKind.TIMER -> {
                    resetRungTimer(existing.timerId)
                    releaseSession(existing)
                }
                // Otherwise whoever is ringing keeps the screen; the newcomer waits its turn.
                request.phase == RingPhase.RINGING -> {
                    enqueue(request)
                    syncSentinel(existing)
                    diagnostics.info("Ring", "alarm ${alarm.id} queued behind ${existing.alarm.id}")
                    return@withLock existing
                }
                else -> return@withLock existing
            }
        }
        val current = store.current
        if (current != null && current.alarm.id == alarm.id && !current.isTimer) {
            // Same alarm already on screen: sunrise turning into the ring, or a backup upgrading a live ring.
            val ringing = current.phase == RingPhase.RINGING || request.phase == RingPhase.RINGING
            val upgraded = current.copy(
                phase = if (ringing) RingPhase.RINGING else RingPhase.SUNRISE,
                kind = if (request.kind == RingKind.BACKUP) RingKind.BACKUP else current.kind,
                intensified = current.intensified || request.kind == RingKind.BACKUP,
                eventId = if (request.eventId > 0) request.eventId else current.eventId,
                scheduledAt = if (request.phase == RingPhase.RINGING) request.scheduledAt else current.scheduledAt,
                startedAt = if (current.phase == RingPhase.SUNRISE && ringing) now else current.startedAt,
                lastInteractionAt = elapsed,
            )
            store.set(upgraded)
            rememberRing(upgraded)
            syncSentinel(upgraded)
            // Two events of one alarm merged into one ring (a snooze coming back after the alarm was moved earlier and
            // rang again): the one handed over must be closed with its follow-ups, or a later reschedule re-arms its
            // backup and it rings after the user has dismissed.
            val replaced = current.eventId.takeIf { it > 0 && it != upgraded.eventId }
            if (replaced != null) {
                tryDb("closing event $replaced merged into ${upgraded.eventId}", Unit) {
                    history.get(replaced)?.let {
                        history.update(
                            it.copy(
                                outcome = if (it.outcome == AlarmOutcome.RINGING) AlarmOutcome.MISSED else it.outcome,
                                endedAt = it.endedAt ?: now, snoozeUntil = 0, pendingBackups = emptyList(),
                            ),
                        )
                    }
                }
            }
            return@withLock upgraded
        }
        store.current?.let { endAsMissed(it, now) }

        val session = RingSession(
            alarm = alarm,
            kind = request.kind,
            phase = request.phase,
            eventId = request.eventId.takeIf { it > 0 } ?: restored?.eventId ?: request.eventId,
            scheduledAt = request.scheduledAt,
            startedAt = restored?.startedAtWall ?: now,
            snoozeCount = maxOf(request.snoozeCount, restored?.snoozeCount ?: 0),
            // Back from a restart: straight to full volume, so that restarting the phone never buys a quieter alarm.
            intensified = request.kind == RingKind.BACKUP || (restored?.resumes ?: 0) > 0,
            lastInteractionAt = elapsed,
            missionsDone = (restored?.missionsDone ?: 0).coerceIn(0, alarm.missions.size),
            timerId = request.timerId,
            safeMode = CrashRescuePolicy.safeMode(runtime.ringCrashTimes(), now),
            mutesUsed = restored?.mutesUsed ?: 0,
            fallback = restored?.fallback ?: false,
            wayOutProgress = restored?.wayOutProgress ?: 0,
            pauses = restored?.pauses ?: 0,
            resumes = restored?.resumes ?: 0,
            pullBacks = restored?.pullBacks ?: 0,
        )
        store.set(session)
        runtime.activeAlarmId = alarm.id
        runtime.activeEventId = session.eventId
        rememberRing(session)
        syncSentinel(session)
        surfaces.refreshAll()
        session
    }

    // ---- what outlives the process -------------------------------------------------------------------------

    /** Saves [s] if it is a real ring still on. Called when it begins and when something countable happens, not on every touch. */
    private fun rememberRing(s: RingSession) {
        if (s.isTimer || s.isTest || s.phase != RingPhase.RINGING) return
        synchronized(recordLock) {
            val live = store.current ?: return
            if (live.alarm.id == s.alarm.id && live.eventId == s.eventId) {
                val boot = try { runtime.bootCount() } catch (_: Throwable) { -1 }
                durable.writeRing(DurableRingRecord.of(s, boot, time.elapsedMillis()))
            }
        }
    }

    /** Whether a ring left behind has not been dealt with. When the history cannot say, it has not: a spurious ring beats a missed one. */
    private suspend fun ringStillOpen(record: DurableRingRecord): Boolean {
        if (record.eventId <= 0) return true
        val event = tryDb("looking up a saved ring", null) { history.get(record.eventId) } ?: return true
        val unresolved = event.outcome == AlarmOutcome.RINGING || event.outcome == AlarmOutcome.MISSED
        return unresolved && event.snoozeUntil <= time.nowMillis()
    }

    private fun needsSentinel(s: RingSession?): Boolean {
        if (s == null || s.phase != RingPhase.RINGING) return false
        return queued.isNotEmpty() || (!s.isTimer && !s.isTest && s.alarm.antiOversleep.keepsComingBack)
    }

    private fun syncSentinel(s: RingSession?) {
        if (needsSentinel(s)) armSentinel(RingIds.SENTINEL_MS) else cancelSentinel()
    }

    private fun armSentinel(inMillis: Long) {
        // Deliberately no redraw of the widgets: this moves every few seconds and is not an alarm anyone set.
        sentinelArmedAt = time.elapsedMillis()
        if (!pending.setAlarmClock(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID, time.nowMillis() + inMillis)) {
            diagnostics.warn("Ring", "the sentinel could not be armed (${pending.lastFailure}); trying again shortly")
        }
    }

    private fun cancelSentinel() = pending.cancel(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID)

    /**
     * The sentinel fired, so the process that armed it may be gone. A ring that is still on just pushes it forward; a ring
     * left behind starts again from what was saved, then whatever was waiting behind it.
     */
    suspend fun onSentinelFired(): RingRequest? = mutex.withLock {
        val live = store.current
        if (live?.phase == RingPhase.RINGING) {
            syncSentinel(live)
            return@withLock null
        }
        val saved = durable.ring()
        if (saved != null) {
            if (ringStillOpen(saved)) {
                diagnostics.warn("Ring", "alarm ${saved.alarmId} is ringing again from its saved state")
                armSentinel(RingIds.SENTINEL_MS)
                return@withLock saved.toRequest()
            }
            // It was dealt with just before the process went: an old record is not a reason to ring.
            durable.clearRing()
        }
        val next = nextQueuedStillDue()
        if (next == null) cancelSentinel() else armSentinel(RingIds.SENTINEL_MS)
        next
    }

    /** A ring that waited behind another through something long, such as a restart, can have become pointless. */
    private suspend fun nextQueuedStillDue(): RingRequest? {
        while (true) {
            val next = queued.poll() ?: return null
            if (next.kind == RingKind.TIMER || next.kind == RingKind.TEST) return next
            val alarm = tryDb("loading a queued alarm", null) { alarms.getAlarm(next.alarmId) } ?: armedCopies.get(next.alarmId)
            val stale = maxOf(STALE_RING_MS, (alarm?.antiOversleep?.resumeWindowMinutes ?: 0) * 60_000L)
            if (time.nowMillis() - next.scheduledAt <= stale) return next
            diagnostics.warn("Ring", "queued alarm ${next.alarmId} waited too long; recording it as missed")
            durable.writeQueue(queued.toList())
            if (next.eventId > 0) {
                tryDb("recording a queued alarm as missed", Unit) {
                    history.get(next.eventId)?.let {
                        history.update(it.copy(outcome = AlarmOutcome.MISSED, endedAt = it.endedAt ?: time.nowMillis(), snoozeUntil = 0, pendingBackups = emptyList()))
                    }
                }
            }
            alarm?.let { notifier.showMissed(it, next.scheduledAt) }
        }
    }

    /**
     * Something started this process while nothing rings (the phone restarted, the app was updated, the user opened it, or
     * the watchdog looked): a ring the last process left behind comes back shortly, from an alarm trigger, or is reported
     * as missed if it may not. [triggersWiped] says Android has forgotten every trigger of this app.
     */
    suspend fun reviveDurableRing(triggersWiped: Boolean = false): Boolean = mutex.withLock {
        if (store.current?.phase == RingPhase.RINGING) return@withLock false
        val saved = durable.ring()
        if (saved == null) {
            if (queued.isEmpty()) return@withLock false
            armSentinel(RingIds.RESUME_DELAY_MS)
            return@withLock true
        }
        if (!ringStillOpen(saved)) {
            durable.clearRing()
            if (queued.isEmpty()) cancelSentinel()
            return@withLock false
        }
        val alarm = saved.alarm()
        val decision = ResumePolicy.afterProcessStart(
            saved, alarm?.antiOversleep?.resumeWindowMinutes ?: 0, alarm?.antiOversleep?.maxResumes ?: AntiOversleepSettings.DEFAULT_MAX_RESUMES,
            alarm?.antiOversleep?.keepsComingBack ?: false,
            time.nowMillis(), time.elapsedMillis(), runtime.bootCount(), BuildConfig.VERSION_CODE, triggersWiped,
        )
        when (decision) {
            is ResumeDecision.Resume -> {
                if (decision.record != saved) durable.writeRing(decision.record)
                if (decision.record != saved || !pending.isScheduled(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID)) {
                    diagnostics.warn("Ring", "alarm ${saved.alarmId} was cut off; ringing it again (restart ${decision.record.resumes} of ${alarm?.antiOversleep?.maxResumes})")
                    armSentinel(RingIds.RESUME_DELAY_MS)
                }
                true
            }
            is ResumeDecision.GiveUp -> {
                diagnostics.warn("Ring", "alarm ${saved.alarmId} was cut off and is not coming back: ${decision.reason}")
                closeAsMissed(saved, alarm)
                if (queued.isNotEmpty()) armSentinel(RingIds.RESUME_DELAY_MS)
                false
            }
        }
    }

    /** The end of a ring left behind: no follow-up may bring it back, and the user is told. */
    private suspend fun closeAsMissed(record: DurableRingRecord, alarm: Alarm?) {
        cancelSentinel()
        cancelBackups(record.alarmId)
        closeOccurrenceAsMissed(record, time.nowMillis())
        durable.clearRing()
        if (runtime.activeAlarmId == record.alarmId) {
            runtime.activeAlarmId = -1
            runtime.activeEventId = -1
        }
        alarm?.let { notifier.showMissed(it, record.scheduledAt) }
        tryDb("ending the check of alarm ${record.alarmId}", Unit) {
            if (wakeChecks.reRingsFor(record.alarmId, record.eventId) > 0) wakeChecks.abandon(record.alarmId, WakeCheckStatus.FAILED)
        }
        tryDb("refreshing the widgets", Unit) { surfaces.refreshAll() }
    }

    private suspend fun closeOccurrenceAsMissed(record: DurableRingRecord, now: Long) {
        if (record.eventId <= 0) return
        tryDb("closing the saved ring of alarm ${record.alarmId}", Unit) {
            history.get(record.eventId)?.let {
                history.update(
                    it.copy(
                        outcome = if (it.outcome == AlarmOutcome.RINGING) AlarmOutcome.MISSED else it.outcome,
                        endedAt = it.endedAt ?: now, snoozeUntil = 0, pendingBackups = emptyList(), snoozeCount = record.snoozeCount,
                        missionsDone = record.missionsDone,
                        fallbackUsed = it.fallbackUsed || record.fallback, pauses = it.pauses + record.pauses, resumes = it.resumes + record.resumes,
                        pullBacks = it.pullBacks + record.pullBacks, mutesUsed = it.mutesUsed + record.mutesUsed,
                    ),
                )
            }
        }
    }

    /** Called for every touch/key/mission step on the ring screen; calms an escalated alarm down. */
    fun noteInteraction() {
        val now = time.elapsedMillis()
        store.update { s ->
            if (now - s.lastInteractionAt < 1_000 && !s.intensified) return@update s
            s.copy(lastInteractionAt = now, intensified = s.kind == RingKind.BACKUP || s.resumes > 0)
        }
    }

    fun silenceTemporarily() {
        val now = time.elapsedMillis()
        store.update { s ->
            if (decide(s, RingCommand.KeySilence, RingProof.None) is RingDecision.Allow) {
                s.copy(silencedUntil = now + RingIds.SILENCE_MS, lastInteractionAt = now)
            } else {
                s
            }
        }
    }

    /**
     * A mission of this ring was finished; [count] is how many are done now according to the screen. The count here only
     * ever moves one step at a time, so what ends the ring is what really happened and not what a caller says: a repeat
     * or a jump changes nothing.
     */
    fun noteMissionDone(count: Int) {
        val advanced = store.current?.takeIf { count == it.missionsDone + 1 && count <= it.alarm.missions.size } != null
        store.update { s ->
            if (count == s.missionsDone + 1 && count <= s.alarm.missions.size) {
                s.copy(missionsDone = count)
            } else {
                diagnostics.warn("Ring", "mission progress $count ignored: ${s.missionsDone} of ${s.alarm.missions.size} done")
                s
            }
        }
        if (advanced) store.current?.let(::rememberRing)
    }

    /** Called about once a second by the service while a session is active. */
    suspend fun tick() {
        val s = store.current ?: return
        val now = time.elapsedMillis()
        if (s.silencedUntil in 1..now) store.update { it.copy(silencedUntil = 0) }

        if (s.phase == RingPhase.SUNRISE) {
            val limit = (s.alarm.sunrise.leadMinutes + SUNRISE_GRACE_MINUTES) * 60_000L
            // Re-checked under the lock: the alarm may have started ringing (or another taken over) since the snapshot.
            if (time.nowMillis() - s.startedAt > limit) {
                mutex.withLock {
                    store.current?.takeIf { it.phase == RingPhase.SUNRISE && it.alarm.id == s.alarm.id && it.eventId == s.eventId }?.let { endSilently(it) }
                }
            }
            return
        }
        if (!s.intensified && s.alarm.volume.escalateWhenIgnored && !s.isTimer &&
            now - s.lastInteractionAt >= RingIds.INACTIVITY_ESCALATION_MS
        ) {
            store.update { it.copy(intensified = true) }
            diagnostics.info("Ring", "alarm ${s.alarm.id} ignored for 40 s: intensifying")
        }
        if (needsSentinel(s) && now - sentinelArmedAt >= RingIds.SENTINEL_REFRESH_MS) armSentinel(RingIds.SENTINEL_MS)
        val timeout = timeoutOf(s) ?: return
        if (now - s.lastInteractionAt >= timeout) onRingTimeout(s)
    }

    /** How long this ring may go ignored before it ends; null for an alarm set to ring until it is dealt with. */
    private fun timeoutOf(s: RingSession): Long? {
        val minutes = s.alarm.antiOversleep.ringTimeoutMinutes
        return if (minutes == AntiOversleepSettings.RING_TIMEOUT_NEVER) null else minutes.coerceAtLeast(1) * 60_000L
    }

    // ---- user decisions ------------------------------------------------------------------------------------

    /**
     * [target] is the ring the snooze button was shown for; null means whatever rings (tests, internal callers). [proof]
     * is [RingProof.None] from anything away from the ring screen, which can snooze only an alarm with no mission at all.
     */
    suspend fun snooze(target: RingTarget? = null, proof: RingProof = RingProof.None): SnoozeOutcome = mutex.withLock {
        val s = store.current ?: return@withLock SnoozeOutcome.NotRinging
        if (target != null && !target.matches(s)) return@withLock SnoozeOutcome.NotRinging
        when (val decision = decide(s, RingCommand.Snooze, proof)) {
            is RingDecision.Allow -> Unit
            is RingDecision.Deny -> {
                // The silent glow before an alarm has not rung yet: a stray volume key there must not arm a snooze.
                diagnostics.info("Ring", "snooze of alarm ${s.alarm.id} refused: ${decision.reason}")
                return@withLock if (decision.reason == DenyReason.NotRinging) SnoozeOutcome.NotRinging else SnoozeOutcome.NotAllowed
            }
        }
        val minutes = SnoozePolicy.nextIntervalMinutes(s.alarm.snooze, s.snoozeCount)
        val now = time.nowMillis()
        val until = now + minutes * 60_000L

        // If the OS will not take the snooze, keep ringing: a silent snooze that never comes back is the worst outcome.
        if (!pending.setAlarmClock(TriggerKind.SNOOZE, s.alarm.id, until, eventId = s.eventId)) {
            diagnostics.error("Ring", "snooze for alarm ${s.alarm.id} could not be armed (${pending.lastFailure}); still ringing")
            return@withLock SnoozeOutcome.NotAllowed
        }
        try {
            cancelBackups(s.alarm.id)
            if (s.eventId > 0) {
                tryDb("recording the snooze of alarm ${s.alarm.id}", Unit) {
                    history.get(s.eventId)?.let {
                        history.update(it.withCountersOf(s).copy(snoozeCount = s.snoozeCount + 1, snoozeUntil = until, pendingBackups = emptyList()))
                    }
                }
            }
        } finally {
            finishSession(s)
        }
        notifier.showSnoozed(s.alarm, until, s.eventId)
        diagnostics.info("Ring", "alarm ${s.alarm.id} snoozed ${s.snoozeCount + 1}x for $minutes min")
        SnoozeOutcome.Snoozed(until, minutes)
    }

    /**
     * The user finished whatever the alarm required (missions, hold) and dismisses it for real. Returns whether a ring
     * was ended. [target] is the ring the button was shown for; a ring in its silent sunrise glow is never dismissed (it
     * has not rung, and dismissing it would count it as handled, or delete a delete-after-use alarm before it ever rang).
     * [proof] is what the caller did to get here; see [RingPolicy].
     */
    suspend fun dismiss(target: RingTarget? = null, proof: RingProof = RingProof.None): Boolean = mutex.withLock {
        val s = store.current ?: return@withLock false
        if (target != null && !target.matches(s)) return@withLock false
        if (refused(s, RingCommand.Dismiss, proof)) return@withLock false
        endSession(s, AlarmOutcome.DISMISSED)
        true
    }

    /** The accessible escape hatch: ends the alarm without missions. Recorded honestly as an emergency stop. */
    suspend fun emergencyStop(target: RingTarget? = null, proof: RingProof = RingProof.None): Boolean = mutex.withLock {
        val s = store.current ?: return@withLock false
        if (target != null && !target.matches(s)) return@withLock false
        if (refused(s, RingCommand.HoldStop, proof)) return@withLock false
        endSession(s, AlarmOutcome.EMERGENCY_STOPPED)
        diagnostics.warn("Ring", "alarm ${s.alarm.id} ended by emergency stop")
        true
    }

    /**
     * Dismissal from a notification while snoozed (no ring session is active). It goes through the same policy as a ring:
     * an alarm that needs a mission or a hold is not ended from a notification. If the alarm cannot be read this does
     * nothing, and the snooze simply rings. [eventId] is the ring the button was shown for, when it was shown for one.
     */
    suspend fun dismissSnoozed(alarmId: Long, eventId: Long = RingTarget.ANY_EVENT) = mutex.withLock {
        // This alarm is ringing, so this is a button left over from before: the ring is ended on its own terms.
        if (store.current?.alarm?.id == alarmId) return@withLock
        val alarm = try {
            alarms.getAlarm(alarmId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            diagnostics.error("Ring", "could not read snoozed alarm $alarmId; leaving its snooze in place", t)
            return@withLock
        }
        val open = tryDb("looking up the snooze of alarm $alarmId", null) {
            history.recent(30).firstOrNull { it.alarmId == alarmId && it.outcome == AlarmOutcome.RINGING && (eventId == RingTarget.ANY_EVENT || it.id == eventId) }
        }
        if (alarm != null) {
            val shape = RingSession(
                alarm = alarm, kind = RingKind.SNOOZE_RETURN, phase = RingPhase.RINGING, eventId = open?.id ?: -1, scheduledAt = open?.scheduledAt ?: 0,
                startedAt = time.nowMillis(), snoozeCount = open?.snoozeCount ?: 0, missionsDone = open?.missionsDone ?: 0,
            )
            if (refused(shape, RingCommand.Dismiss, RingProof.None)) return@withLock
        }
        pending.cancel(TriggerKind.SNOOZE, alarmId)
        cancelBackups(alarmId)
        notifier.cancelSnoozed(alarmId)
        // No open ring means this was a late tap on a Dismiss button of a ring that is already over: nothing more to do.
        if (open != null) {
            tryDb("recording the dismissal of snoozed alarm $alarmId", Unit) {
                history.update(open.copy(outcome = AlarmOutcome.DISMISSED, endedAt = time.nowMillis(), snoozeUntil = 0, pendingBackups = emptyList()))
                if (alarm != null) afterDismissal(alarm, open.id)
            }
        }
        surfaces.refreshAll()
    }

    /**
     * Ring now instead of waiting out the snooze. A snooze without an open event still rings: the test alarm has no
     * history, and neither has a ring whose history could not be written or read. Only a notification that outlived its
     * snooze rings nothing.
     */
    suspend fun wakeNow(alarmId: Long): RingRequest? {
        val armed = pending.isScheduled(TriggerKind.SNOOZE, alarmId)
        val open = tryDb("looking up the snooze of alarm $alarmId", null) {
            history.recent(30).firstOrNull { it.alarmId == alarmId && it.outcome == AlarmOutcome.RINGING }
        }
        if (open == null && !armed) {
            notifier.cancelSnoozed(alarmId)
            return null
        }
        return onSnoozeExpired(alarmId, open?.id ?: -1)
    }

    // ---- timers --------------------------------------------------------------------------------------------

    suspend fun addMinuteToRingingTimer() = mutex.withLock {
        val s = store.current ?: return@withLock
        if (!s.isTimer) return@withLock
        try {
            val timer = timers.getTimer(s.timerId)
            if (timer != null) {
                val endsAt = time.nowMillis() + 60_000
                timers.save(timer.copy(state = TimerState.RUNNING, endsAtMillis = endsAt, remainingMs = 60_000))
                pending.setExact(TriggerKind.TIMER, timer.id, endsAt)
                timers.getTimer(timer.id)?.let { notifier.showTimerRunning(it) }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            diagnostics.error("Ring", "could not extend timer ${s.timerId}", t)
        } finally {
            finishSession(s)
        }
    }

    // ---- ending sessions -----------------------------------------------------------------------------------

    /**
     * [seen] is the snapshot the tick judged. By default the ring timeout and the backup alarm fall due in the same
     * second, and the backup upgrades the live ring (resetting its timeout) under the lock: so the decision is taken
     * again here, against the ring as it is now, or the freshly upgraded backup would be ended at once.
     */
    private suspend fun onRingTimeout(seen: RingSession) = mutex.withLock {
        val s = store.current ?: return@withLock
        if (!RingTarget.of(seen).matches(s) || refused(s, RingCommand.Timeout, RingProof.None)) return@withLock
        val now = time.nowMillis()
        val timeout = timeoutOf(s) ?: return@withLock
        if (time.elapsedMillis() - s.lastInteractionAt < timeout) return@withLock
        try {
            if (s.isTimer) {
                resetRungTimer(s.timerId)
                return@withLock
            }
            diagnostics.warn("Ring", "alarm ${s.alarm.id} ignored until the ring timeout")
            if (s.eventId > 0) {
                history.get(s.eventId)?.let {
                    history.update(it.withCountersOf(s).copy(outcome = AlarmOutcome.MISSED, endedAt = now, snoozeCount = s.snoozeCount, missionsDone = s.missionsDone))
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            diagnostics.error("Ring", "could not record the ring timeout of alarm ${s.alarm.id}", t)
        } finally {
            val backupsLeft = !s.isTimer && (1..RingIds.MAX_BACKUPS).any { pending.isScheduled(TriggerKind.BACKUP, s.alarm.id, it) }
            finishSession(s)
            if (!s.isTimer && !backupsLeft && !s.isTest) {
                notifier.showMissed(s.alarm, s.scheduledAt)
                // Nothing will ring again for this "Still awake?" check. Left behind, it stays pending on the morning
                // screen for good and a delete-after-use alarm waiting for it is never deleted.
                tryDb("ending the check of alarm ${s.alarm.id}", Unit) {
                    if (wakeChecks.reRingsFor(s.alarm.id, s.eventId) > 0) wakeChecks.abandon(s.alarm.id, WakeCheckStatus.FAILED)
                }
            }
        }
    }

    /** Ends the ring: whatever the history or follow-up bookkeeping did, the sound must stop when the user says so. */
    private suspend fun endSession(s: RingSession, outcome: AlarmOutcome) {
        try {
            persistEnd(s, outcome)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            diagnostics.error("Ring", "bookkeeping for the end of alarm ${s.alarm.id} failed", t)
        } finally {
            finishSession(s)
        }
    }

    private suspend fun persistEnd(s: RingSession, outcome: AlarmOutcome) {
        val now = time.nowMillis()
        if (s.isTimer) {
            resetRungTimer(s.timerId)
            return
        }
        // Cancelling the OS triggers comes first and needs no database: the backup must not ring after a dismissal.
        cancelBackups(s.alarm.id)
        pending.cancel(TriggerKind.SNOOZE, s.alarm.id)
        notifier.cancelSnoozed(s.alarm.id)

        if (s.eventId > 0) {
            history.get(s.eventId)?.let { e ->
                history.update(
                    e.withCountersOf(s).copy(
                        outcome = outcome,
                        endedAt = now,
                        snoozeUntil = 0,
                        pendingBackups = emptyList(),
                        snoozeCount = s.snoozeCount,
                        missionsDone = s.missionsDone,
                        missionsTotal = s.alarm.missions.size,
                        wakeCheck = if (outcome == AlarmOutcome.DISMISSED && s.alarm.wakeCheck.enabled) WakeCheckStatus.PENDING else e.wakeCheck,
                    ),
                )
            }
        }
        if (outcome == AlarmOutcome.DISMISSED && !s.isTest) {
            afterDismissal(s.alarm, s.eventId)
        } else if (outcome == AlarmOutcome.EMERGENCY_STOPPED) {
            wakeChecks.abandon(s.alarm.id, WakeCheckStatus.NONE)
        }
    }

    /** What a dismissal sets off, whether it came from the ring screen or from the notification of a snoozed alarm. */
    private suspend fun afterDismissal(alarm: Alarm, eventId: Long) {
        if (alarm.wakeCheck.enabled) {
            // Carried over from earlier unanswered checks however this ring came back (a snoozed re-ring returns as
            // a snooze, and counting from zero again would let it re-ring past its limit).
            wakeChecks.startAfterDismissal(alarm, eventId, wakeChecks.reRingsFor(alarm.id, eventId))
        }
        if (alarm.deleteAfterUse && !alarm.isRepeating) {
            if (alarm.wakeCheck.enabled) {
                // The "Still awake?" check needs the alarm to ask about and to ring again: it is kept, switched off,
                // and the check deletes it when it is over.
                alarms.setEnabled(alarm.id, false)
                scheduler.schedule(alarm.id)
            } else {
                alarms.delete(alarm.id)
                scheduler.cancel(alarm.id)
            }
        }
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

    /** What this ring counted, added to what its event has from earlier rings (a snooze ends one ring and starts another). */
    private fun AlarmEvent.withCountersOf(s: RingSession) = copy(
        fallbackUsed = fallbackUsed || s.fallback,
        pauses = pauses + s.pauses,
        resumes = resumes + s.resumes,
        pullBacks = pullBacks + s.pullBacks,
        mutesUsed = mutesUsed + s.mutesUsed,
    )

    /**
     * Cleans up after a session that is being *replaced* by another. The store is deliberately not set to null: a
     * momentary "nothing ringing" would let the service shut itself down just as the next ring starts.
     */
    private fun releaseSession(s: RingSession) {
        snapshots.remove(s.alarm.id)
        notifier.cancelRing()
    }

    private fun finishSession(s: RingSession) {
        snapshots.remove(s.alarm.id)
        synchronized(recordLock) {
            store.set(null)
            // Only a real ring has a record. A timer or a sunrise glow ending says nothing about a ring left behind.
            if (!s.isTimer && !s.isTest && s.phase == RingPhase.RINGING) durable.clearRing()
        }
        // Whatever is still waiting keeps the sentinel: the process could die before the next one begins.
        if (queued.isEmpty() && durable.ring() == null) cancelSentinel()
        // The ring is over whatever the disk says: nothing below may be skipped because this could not be written.
        tryDb("recording that nothing is ringing", Unit) {
            runtime.activeAlarmId = -1
            runtime.activeEventId = -1
        }
        notifier.cancelRing()
        surfaces.refreshAll()
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private fun decide(s: RingSession, command: RingCommand, proof: RingProof): RingDecision = RingPolicy.decide(s, command, proof)

    private fun refused(s: RingSession, command: RingCommand, proof: RingProof): Boolean {
        val decision = decide(s, command, proof) as? RingDecision.Deny ?: return false
        diagnostics.info("Ring", "$command of alarm ${s.alarm.id} refused: ${decision.reason}")
        return true
    }

    private suspend fun afterFire(alarm: Alarm, scheduledAt: Long) {
        if (alarm.isRepeating) {
            scheduler.schedule(alarm.id, afterMillis = scheduledAt + 1_000)
        } else {
            // A one-time alarm switches itself off; scheduling a now-disabled alarm just removes its main trigger.
            alarms.setEnabled(alarm.id, false)
            scheduler.schedule(alarm.id)
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

    private suspend fun loadForRing(request: RingRequest, appSettings: AppSettings, restored: DurableRingRecord?): Alarm? {
        return when (request.kind) {
            RingKind.TIMER -> {
                // A timer that cannot be read still rings (a plain one); only one that is known to be gone does not.
                val timer = tryDb("loading timer ${request.timerId}", CountdownTimer(id = request.timerId, durationMs = 0)) { timers.getTimer(request.timerId) }
                timer?.let { timerAlarm(it, appSettings) }
            }
            RingKind.TEST -> runtime.testAlarmJson?.let {
                runCatching { WakeJson.instance.decodeFromString(Alarm.serializer(), it) }.getOrNull()
            } ?: Alarm(id = RingIds.TEST_ALARM_ID, label = "Test alarm")
            else -> restored?.alarm() ?: snapshots[request.alarmId] ?: tryDb("loading alarm ${request.alarmId}", null) { alarms.getAlarm(request.alarmId) } ?: run {
                // The database cannot say, or the alarm vanished (e.g. deleted mid-ring). Ring anyway rather than stay
                // silent, as strictly as the saved copy says if there is one.
                diagnostics.warn("Ring", "alarm ${request.alarmId} missing; ringing from what was saved or a default alarm")
                armedCopies.get(request.alarmId) ?: Alarm(id = request.alarmId, label = "Alarm")
            }
        }
    }

    /** One of the alarm's missions for this occurrence if it changes them each morning; each still-awake re-ring moves on to the next. */
    private suspend fun forRing(alarm: Alarm, request: RingRequest): Alarm {
        if (!alarm.antiOversleep.rotateMissions || request.kind == RingKind.TIMER || request.kind == RingKind.TEST) return alarm
        val reRings = if (request.eventId > 0) tryDb("looking up the re-rings of alarm ${alarm.id}", 0) { wakeChecks.reRingsFor(alarm.id, request.eventId) } else 0
        return forRingOf(alarm, request, reRings)
    }

    private fun forRingOf(alarm: Alarm, request: RingRequest, reRings: Int): Alarm =
        if (request.kind == RingKind.TIMER || request.kind == RingKind.TEST) alarm else MissionRotation.forRing(alarm, request.scheduledAt, reRings, time.zone())

    private fun timerAlarm(timer: CountdownTimer, s: AppSettings) = Alarm(
        id = RingIds.TIMER_ALARM_BASE - timer.id,
        label = timer.label,
        sound = s.timerSound,
        vibration = VibrationSettings(enabled = s.timerVibrate),
        volume = VolumeSettings(level = 0, rampSeconds = 4, extraLoud = false, escalateWhenIgnored = false),
        snooze = SnoozeSettings(enabled = false),
        antiOversleep = AntiOversleepSettings(backupEnabled = false, ringTimeoutMinutes = 3),
    )

    /** Runs a database or settings step; if it fails the failure is logged and [fallback] is used so the ring goes on. */
    private inline fun <T> tryDb(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        diagnostics.error("Ring", "$what failed; carrying on", t)
        fallback
    }

    // ---- recovery ------------------------------------------------------------------------------------------

    /** After a crash/reboot: closes events that can no longer be ringing and restores the system volume. */
    suspend fun recoverStaleState() = mutex.withLock {
        if (store.current != null) return@withLock
        val now = time.nowMillis()
        val saved = durable.ring()
        // Housekeeping runs at every process start, including the one an alarm wakes: it must never be able to crash it.
        tryDb("closing stale ring events", Unit) {
            history.openEvents().forEach { e ->
                // Its saved ring speaks for it; reviveDurableRing either brings it back or closes it.
                if (saved != null && saved.eventId == e.id) return@forEach
                val stale = maxOf(STALE_RING_MS, (tryDb("reading alarm ${e.alarmId}", null) { alarms.getAlarm(e.alarmId) }?.antiOversleep?.resumeWindowMinutes ?: 0) * 60_000L)
                val staleBefore = now - stale
                val hasFollowUp = pending.isScheduled(TriggerKind.SNOOZE, e.alarmId) ||
                    (1..RingIds.MAX_BACKUPS).any { pending.isScheduled(TriggerKind.BACKUP, e.alarmId, it) }
                // At process start after a reboot or force-stop Android holds no triggers yet, and this runs before the
                // reschedule that restores them. Closing the event then would make the restored snooze find nothing to ring.
                val restorable = (e.snoozeUntil > 0 && now - e.snoozeUntil <= stale) || e.pendingBackups.any { now - it <= stale }
                if (e.firedAt < staleBefore && !hasFollowUp && !restorable) {
                    history.update(e.copy(outcome = AlarmOutcome.MISSED, endedAt = e.endedAt ?: now))
                }
            }
        }
        // A timer can only be "ringing" while a session says so; after a crash nothing is, so reset it.
        tryDb("resetting stuck timers", Unit) {
            timers.getTimers().filter { it.state == TimerState.RINGING }.forEach {
                timers.save(it.copy(state = TimerState.IDLE, remainingMs = it.durationMs, endsAtMillis = 0))
            }
        }
        // Years of daily alarms would otherwise grow the history (and a backup that includes it) without end.
        tryDb("pruning old history", Unit) { history.deleteOlderThan(now - HISTORY_KEEP_MS) }
        if (saved == null && runtime.activeAlarmId != -1L) {
            diagnostics.warn("Ring", "recovering from a ring session that did not end cleanly")
            runtime.activeAlarmId = -1
            runtime.activeEventId = -1
        }
    }

    private companion object {
        const val SUNRISE_GRACE_MINUTES = 10
        const val STALE_RING_MS = 60 * 60_000L

        /** Two years of wake-ups: far more than the morning dashboard shows, and a backup stays well under its size limit. */
        const val HISTORY_KEEP_MS = 730L * 24 * 3_600_000L
    }
}
