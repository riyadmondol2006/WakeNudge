package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.BedtimeSettings
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.domain.model.WakeCheck
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.repository.TimerRepository
import com.riyadm.wakenudge.domain.repository.WakeCheckRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.NextAlarm
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
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
    private val settings: SettingsRepository,
    private val calculator: NextTriggerCalculator,
    private val contextProvider: SchedulingContextProvider,
    private val pending: PendingAlarms,
    private val time: TimeSource,
    private val runtime: RuntimeState,
    private val surfaces: SurfaceRefresher,
    private val diagnostics: DiagnosticsLog,
) : AlarmScheduler {

    private val lock = Mutex()

    override suspend fun rescheduleAll(reason: String) = lock.withLock {
        val ctx = contextProvider.load()
        val now = time.now()
        val all = alarms.getAlarms()

        // Anything we registered for an alarm that no longer exists must go.
        val known = all.map { it.id }.toSet()
        runtime.registeredTriggers().keys.filter { it !in known }.forEach { cancelAlarmTriggers(it) }

        val registered = HashMap<Long, Long>()
        for (alarm in all) {
            scheduleOne(alarm, ctx, now)?.let { registered[alarm.id] = it }
        }
        runtime.setRegisteredTriggers(registered)

        restoreWakeChecks(now.toInstant().toEpochMilli())
        restoreTimers(now.toInstant().toEpochMilli())
        scheduleBedtime(settings.current().bedtime, now)
        scheduleWatchdog(now.toInstant().toEpochMilli())

        runtime.lastRescheduleAt = now.toInstant().toEpochMilli()
        runtime.lastRescheduleReason = reason
        diagnostics.info("Scheduler", "rescheduleAll($reason): ${all.size} alarms, ${registered.size} armed")
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
            val at = scheduleOne(alarm, contextProvider.load(), from)
            val map = runtime.registeredTriggers().toMutableMap()
            if (at == null) map.remove(alarmId) else map[alarmId] = at
            runtime.setRegisteredTriggers(map)
        }
        surfaces.refreshAll()
    }

    override suspend fun cancel(alarmId: Long) = lock.withLock {
        cancelAlarmTriggers(alarmId)
        runtime.setRegisteredTriggers(runtime.registeredTriggers() - alarmId)
        surfaces.refreshAll()
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
        val nowMs = now.toInstant().toEpochMilli()

        pending.setAlarmClock(TriggerKind.ALARM, alarm.id, at)

        if (alarm.sunrise.enabled && alarm.sunrise.leadMinutes > 0) {
            val sunriseAt = at - alarm.sunrise.leadMinutes * 60_000L
            if (sunriseAt > nowMs) pending.setExact(TriggerKind.SUNRISE, alarm.id, sunriseAt, payloadAt = at)
        }
        val notice = alarm.upcomingNoticeMinutes.takeIf { it > 0 } ?: settings.current().upcomingNoticeMinutes
        if (notice > 0) {
            val noticeAt = at - notice * 60_000L
            if (noticeAt > nowMs) pending.setExact(TriggerKind.UPCOMING, alarm.id, noticeAt, payloadAt = at)
        }
        return at
    }

    private fun cancelAlarmTriggers(alarmId: Long) {
        pending.cancel(TriggerKind.ALARM, alarmId)
        pending.cancel(TriggerKind.SUNRISE, alarmId)
        pending.cancel(TriggerKind.UPCOMING, alarmId)
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
