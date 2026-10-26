package com.riyadm.wakenudge.domain.scheduling

import com.riyadm.wakenudge.domain.model.Alarm
import java.time.ZonedDateTime

data class NextAlarm(val alarm: Alarm, val at: ZonedDateTime)

/** Registers alarm triggers with the OS. Implementations must be idempotent and safe to call from any context. */
interface AlarmScheduler {
    /** Rebuilds every pending trigger (alarms, follow-ups, timers, bedtime, watchdog) from stored state. */
    suspend fun rescheduleAll(reason: String)

    /**
     * Recomputes and registers the triggers of one alarm, or removes them if it will never ring.
     * Occurrences at or before [afterMillis] are ignored, so an alarm that fires a hair early cannot ring twice.
     */
    suspend fun schedule(alarmId: Long, afterMillis: Long = 0)

    /** Removes every pending trigger belonging to the alarm, including snooze, backups and still-awake checks. */
    suspend fun cancel(alarmId: Long)

    /**
     * Silences what a ring left pending (snooze, backups, still-awake check and their notifications) without touching
     * the alarm's next regular ring. Used when the user switches the alarm off or removes it mid-flight.
     */
    suspend fun cancelFollowUps(alarmId: Long)

    /** The soonest ring across all enabled alarms, honouring vacation, exceptions and skipped occurrences. */
    suspend fun nextAlarm(): NextAlarm?

    /** Epoch millis of the alarm's next ring, or null if it will not ring. */
    suspend fun nextTriggerOf(alarmId: Long): Long?

    /** Removes the "alarm soon" heads-up of [alarmId] once it no longer says what will happen (the ring was skipped). */
    fun dropUpcomingNotice(alarmId: Long) {}

    /** Re-arms only the bedtime reminder (after the bedtime settings changed). */
    suspend fun refreshBedtime()

    /** Fires a throw-away alarm through the real pipeline after [delaySeconds]; returns the trigger time. */
    suspend fun scheduleTestAlarm(delaySeconds: Int): Long
}

/** Lets the engine ask the home-screen widgets and the quick-settings tile to redraw. */
interface SurfaceRefresher {
    fun refreshAll()
}
