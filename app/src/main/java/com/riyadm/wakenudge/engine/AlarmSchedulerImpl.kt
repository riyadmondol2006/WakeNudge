package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.BedtimeSettings
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.domain.model.WakeCheck
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.repository.TimerRepository
import com.riyadm.wakenudge.domain.repository.WakeCheckRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.NextAlarm
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import java.time.Duration
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns stored alarms into OS triggers. All registration funnels through one mutex so concurrent callers (UI edits,
 * boot, time-zone changes, the watchdog) can never interleave half-written schedules.
 */
@Singleton
class AlarmSchedulerImpl @Inject constructor(
    private val alarms: AlarmRepository,
    private val timers: TimerRepository,
    private val wakeChecks: WakeCheckRepository,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
    private val calculator: NextTriggerCalculator,
    private val contextProvider: SchedulingContextProvider,
    private val pending: PendingAlarms,
    private val time: TimeSource,
    private val runtime: RuntimeState,
    private val surfaces: SurfaceRefresher,
    private val diagnostics: DiagnosticsLog,
    private val wakeCheckController: WakeCheckController,
    private val sessions: RingSessionStore,
    private val notifier: Notifier,
) : AlarmScheduler {

    private val lock = Mutex()

    /** The global heads-up lead time, read once per scheduling pass so the hot loop never suspends on settings. */
    private var noticeDefault = 0

    override suspend fun rescheduleAll(reason: String) = lock.withLock {
        val now = time.now()
        val nowMs = now.toInstant().toEpochMilli()
        val today = now.toLocalDate().toEpochDay()
        expireVacation(today)
        noticeDefault = settings.current().upcomingNoticeMinutes

        val ctx = contextProvider.load()
        val all = alarms.getAlarms()

        // Anything we registered for an alarm that no longer exists must go, follow-ups included.
        val known = all.map { it.id }.toSet()
        for (gone in runtime.registeredTriggers().keys.filter { it !in known }) {
            cancelAlarmTriggers(gone)
            cancelFollowUpsLocked(gone)
        }

        // Plan first, register second: one broken alarm must not stop the others, and when the OS's 500-alarm cap
        // is near, the soonest alarms are the ones that must win.
        val plans = ArrayList<Pair<Alarm, ZonedDateTime>>()
        var failures = 0
        for (alarm in all) {
            try {
                cancelAlarmTriggers(alarm.id)
                val live = alarm.copy(skipEpochDay = alarm.skipEpochDay?.takeIf { it >= today })
                if (alarm.skipEpochDay != null && live.skipEpochDay == null) alarms.setSkipDay(alarm.id, null)
                calculator.next(live, now, ctx.forAlarm(alarm))?.let { plans += alarm to it }
            } catch (t: Throwable) {
                failures++
                diagnostics.error("Scheduler", "alarm ${alarm.id} could not be planned and was skipped", t)
            }
        }
        plans.sortBy { it.second.toInstant() }

        val registered = HashMap<Long, Long>()
        var used = 0
        var overflow = 0
        for ((alarm, next) in plans) {
            if (used >= MAX_ARMED_ALARMS) { overflow++; continue }
            val at = next.toInstant().toEpochMilli()
            if (pending.setAlarmClock(TriggerKind.ALARM, alarm.id, at)) {
                registered[alarm.id] = at
                used++
            } else {
                failures++
                diagnostics.error("Scheduler", "OS refused alarm ${alarm.id}: ${pending.lastFailure}")
            }
        }
        // Lead-in and heads-up triggers are a bonus: only for alarms in the next day and a half, budget permitting.
        for ((alarm, _) in plans) {
            val at = registered[alarm.id] ?: continue
            if (at - nowMs > AUX_WINDOW_MS || used >= AUX_BUDGET) break
            used += registerAuxiliary(alarm, at, nowMs)
        }
        if (overflow > 0) diagnostics.warn("Scheduler", "$overflow alarms are beyond the OS limit of concurrent alarms and not armed")
        runtime.setRegisteredTriggers(registered)

        restoreFollowUps(nowMs)
        restoreWakeChecks(nowMs)
        restoreTimers(nowMs)
        scheduleBedtime(settings.current().bedtime, now)
        scheduleWatchdog(nowMs)

        runtime.lastRescheduleAt = nowMs
        runtime.lastRescheduleReason = reason
        diagnostics.info("Scheduler", "rescheduleAll($reason): ${all.size} alarms, ${registered.size} armed" + if (failures > 0) ", $failures problems" else "")
        surfaces.refreshAll()
    }

    override suspend fun schedule(alarmId: Long, afterMillis: Long) = lock.withLock {
        val alarm = alarms.getAlarm(alarmId)
        if (alarm == null) {
            cancelAlarmTriggers(alarmId)
        } else {
            val now = time.now()
            val from = if (afterMillis > now.toInstant().toEpochMilli()) {
                java.time.Instant.ofEpochMilli(afterMillis).atZone(now.zone)
            } else {
                now
            }
            val at = try {
                scheduleOne(alarm, contextProvider.load(), from)
            } catch (t: Throwable) {
                diagnostics.error("Scheduler", "alarm ${alarm.id} could not be scheduled", t)
                null
            }
            val map = runtime.registeredTriggers().toMutableMap()
            if (at == null) map.remove(alarmId) else map[alarmId] = at
            runtime.setRegisteredTriggers(map)
        }
        surfaces.refreshAll()
    }

    override suspend fun cancel(alarmId: Long) = lock.withLock {
        cancelAlarmTriggers(alarmId)
        cancelFollowUpsLocked(alarmId)
        runtime.setRegisteredTriggers(runtime.registeredTriggers() - alarmId)
        surfaces.refreshAll()
    }

    override suspend fun cancelFollowUps(alarmId: Long) = lock.withLock {
        cancelFollowUpsLocked(alarmId)
        surfaces.refreshAll()
    }

    /** Snooze, backups, still-awake check and their notifications; closes the events they belonged to. */
    private suspend fun cancelFollowUpsLocked(alarmId: Long) {
        pending.cancel(TriggerKind.SNOOZE, alarmId)
        for (i in 1..RingIds.MAX_BACKUPS) pending.cancel(TriggerKind.BACKUP, alarmId, i)
        wakeCheckController.abandon(alarmId, WakeCheckStatus.CANCELLED)
        notifier.cancelSnoozed(alarmId)
        notifier.cancelUpcoming(alarmId)
        val ringingNow = sessions.current?.takeIf { it.alarm.id == alarmId }
        val nowMs = time.nowMillis()
        for (event in history.withFollowUps().filter { it.alarmId == alarmId }) {
            val keepOpen = ringingNow != null && ringingNow.eventId == event.id
            history.update(
                event.copy(
                    snoozeUntil = 0,
                    pendingBackups = emptyList(),
                    outcome = if (!keepOpen && event.outcome == AlarmOutcome.RINGING) AlarmOutcome.DISMISSED else event.outcome,
                    endedAt = if (!keepOpen) event.endedAt ?: nowMs else event.endedAt,
                ),
            )
        }
    }

    /** A vacation whose resume day has arrived is over; clear the flag so nothing keeps claiming alarms are paused. */
    private suspend fun expireVacation(todayEpochDay: Long) {
        val v = settings.current().vacation
        if (v.enabled && v.resumeEpochDay != null && !v.activeOn(todayEpochDay)) {
            settings.update { it.copy(vacation = VacationMode()) }
            diagnostics.info("Scheduler", "vacation ended; alarms are back on")
        }
    }

    /** Registers the sunrise lead-in and heads-up for [alarm]; returns how many triggers that used. */
    private fun registerAuxiliary(alarm: Alarm, at: Long, nowMs: Long): Int {
        var used = 0
        if (alarm.sunrise.enabled && alarm.sunrise.leadMinutes > 0) {
            val sunriseAt = at - alarm.sunrise.leadMinutes * 60_000L
            if (sunriseAt > nowMs && pending.setExact(TriggerKind.SUNRISE, alarm.id, sunriseAt, payloadAt = at)) used++
        }
        val notice = alarm.upcomingNoticeMinutes.takeIf { it > 0 } ?: noticeDefault
        if (notice > 0) {
            val noticeAt = at - notice * 60_000L
            if (noticeAt > nowMs && pending.setExact(TriggerKind.UPCOMING, alarm.id, noticeAt, payloadAt = at)) used++
        }
        return used
    }

    override suspend fun nextAlarm(): NextAlarm? {
        val ctx = contextProvider.load()
        val now = time.now()
        return alarms.getAlarms()
            .mapNotNull { a -> calculator.next(a, now, ctx.forAlarm(a))?.let { NextAlarm(a, it) } }
            .minByOrNull { it.at.toInstant() }
    }

    override suspend fun nextTriggerOf(alarmId: Long): Long? {
        val alarm = alarms.getAlarm(alarmId) ?: return null
        val now = time.now()
        return calculator.next(alarm, now, contextProvider.load().forAlarm(alarm))?.toInstant()?.toEpochMilli()
    }

    override suspend fun refreshBedtime() = lock.withLock {
        scheduleBedtime(settings.current().bedtime, time.now())
    }

    override suspend fun scheduleTestAlarm(delaySeconds: Int): Long {
        val at = time.nowMillis() + delaySeconds * 1000L
        val test = Alarm(
            id = TEST_ALARM_ID,
            label = "Test alarm",
            hour = time.now().hour,
            minute = time.now().minute,
            antiOversleep = com.riyadm.wakenudge.domain.model.AntiOversleepSettings(backupEnabled = false, ringTimeoutMinutes = 2),
            snooze = com.riyadm.wakenudge.domain.model.SnoozeSettings(enabled = true, minutes = 1, maxCount = 0),
        ).let { base ->
            val d = settings.current().defaults
            base.copy(sound = d.sound, volume = d.volume, vibration = d.vibration)
        }
        runtime.testAlarmJson = WakeJson.instance.encodeToString(Alarm.serializer(), test)
        pending.setAlarmClock(TriggerKind.ALARM, TEST_ALARM_ID, at)
        diagnostics.info("Scheduler", "test alarm armed for +${delaySeconds}s")
        return at
    }

    // ---- internals -----------------------------------------------------------------------------------------

    /** Registers the alarm's triggers; returns the ring time (epoch millis) or null when it will not ring. */
    private suspend fun scheduleOne(alarm: Alarm, ctx: LoadedSchedulingContext, now: ZonedDateTime): Long? {
        cancelAlarmTriggers(alarm.id)

        val today = now.toLocalDate().toEpochDay()
        if (alarm.skipEpochDay != null && alarm.skipEpochDay < today) alarms.setSkipDay(alarm.id, null)

        val next = calculator.next(alarm.copy(skipEpochDay = alarm.skipEpochDay?.takeIf { it >= today }), now, ctx.forAlarm(alarm))
            ?: return null
        val at = next.toInstant().toEpochMilli()
        if (!pending.setAlarmClock(TriggerKind.ALARM, alarm.id, at)) {
            diagnostics.error("Scheduler", "OS refused alarm ${alarm.id}: ${pending.lastFailure}")
            return null
        }
        noticeDefault = settings.current().upcomingNoticeMinutes
        registerAuxiliary(alarm, at, now.toInstant().toEpochMilli())
        return at
    }

    private fun cancelAlarmTriggers(alarmId: Long) {
        pending.cancel(TriggerKind.ALARM, alarmId)
        pending.cancel(TriggerKind.SUNRISE, alarmId)
        pending.cancel(TriggerKind.UPCOMING, alarmId)
    }

    /**
     * Snoozes and backup alarms live only in AlarmManager, which forgets them on reboot, app update and force-stop.
     * They are also persisted on the history event; re-arm them here. One that came due while the phone was off rings
     * right away (within an hour), since the user asked to be woken.
     */
    private suspend fun restoreFollowUps(nowMs: Long) {
        val known = alarms.getAlarms().map { it.id }.toSet()
        for (event in history.withFollowUps()) {
            // The alarm was deleted (or replaced by a restore) after this ring: there is nothing left to wake.
            if (event.alarmId !in known && event.alarmId >= 0) {
                cancelFollowUpsLocked(event.alarmId)
                continue
            }
            if (event.snoozeUntil > 0) {
                val at = if (event.snoozeUntil <= nowMs) nowMs + FOLLOW_UP_DELAY_MS else event.snoozeUntil
                if (nowMs - event.snoozeUntil <= STALE_FOLLOW_UP_MS) pending.setAlarmClock(TriggerKind.SNOOZE, event.alarmId, at, eventId = event.id)
            }
            event.pendingBackups.forEachIndexed { i, backupAt ->
                val index = i + 1
                if (nowMs - backupAt > STALE_FOLLOW_UP_MS) return@forEachIndexed
                val at = if (backupAt <= nowMs) nowMs + FOLLOW_UP_DELAY_MS * index else backupAt
                pending.setAlarmClock(TriggerKind.BACKUP, event.alarmId, at, index = index, eventId = event.id)
            }
        }
    }

    private suspend fun restoreWakeChecks(nowMs: Long) {
        for (check in wakeChecks.getAll()) {
            when (check.phase) {
                WakeCheck.Phase.WAITING ->
                    pending.setExact(TriggerKind.WAKE_CHECK_PROMPT, check.alarmId, maxOf(check.promptAt, nowMs + 1_000))
                WakeCheck.Phase.PROMPTED ->
                    pending.setAlarmClock(TriggerKind.WAKE_CHECK_DEADLINE, check.alarmId, maxOf(check.deadlineAt, nowMs + 1_000))
            }
        }
    }

    private suspend fun restoreTimers(nowMs: Long) {
        for (timer in timers.getTimers()) {
            if (timer.state == TimerState.RUNNING) {
                pending.setExact(TriggerKind.TIMER, timer.id, maxOf(timer.endsAtMillis, nowMs + 500))
            }
        }
    }

    private fun scheduleBedtime(bedtime: BedtimeSettings, now: ZonedDateTime) {
        pending.cancel(TriggerKind.BEDTIME, 0)
        if (!bedtime.enabled || bedtime.daysMask == 0) return
        val reminder = nextBedtimeReminder(bedtime, now) ?: return
        pending.setExact(TriggerKind.BEDTIME, 0, reminder.toInstant().toEpochMilli())
    }

    private fun scheduleWatchdog(nowMs: Long) {
        pending.setInexact(TriggerKind.WATCHDOG, 0, nowMs + WATCHDOG_INTERVAL.toMillis())
    }

    companion object {
        const val TEST_ALARM_ID = -1L

        /** Android allows 500 concurrent alarms per app; keep headroom for snoozes, backups, timers and the watchdog. */
        private const val MAX_ARMED_ALARMS = 440
        private const val AUX_BUDGET = 470
        private const val AUX_WINDOW_MS = 36 * 3_600_000L
        private const val FOLLOW_UP_DELAY_MS = 2_000L
        private const val STALE_FOLLOW_UP_MS = 60 * 60_000L
        private val WATCHDOG_INTERVAL: Duration = Duration.ofHours(6)

        /** Next moment (after [now]) at which the "time to wind down" reminder is due. Exposed for tests and the UI. */
        fun nextBedtimeReminder(bedtime: BedtimeSettings, now: ZonedDateTime): ZonedDateTime? {
            for (offset in 0..7) {
                val date = now.toLocalDate().plusDays(offset.toLong())
                val bedtimeAt = ZonedDateTime.of(date, java.time.LocalTime.of(bedtime.hour, bedtime.minute), now.zone)
                // The day bit refers to the evening the user goes to bed.
                if (bedtime.daysMask and (1 shl (date.dayOfWeek.value - 1)) == 0) continue
                val reminder = bedtimeAt.minusMinutes(bedtime.reminderLeadMinutes.toLong())
                if (reminder.isAfter(now)) return reminder
            }
            return null
        }
    }
}
