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

    /**
     * Registers the alarm's main trigger again for [atMillis], the time it is already due, and changes nothing else: not the
     * alarm, not its other triggers. Returns false if the OS refused.
     */
    suspend fun rearm(alarmId: Long, atMillis: Long): Boolean

    /**
     * Removes every pending trigger belonging to the alarm, including snooze, backups and still-awake checks. With
     * [byUser] (a delete from the app or a widget) a ring or snooze of an alarm with a mission is left as it is: it ends
     * on the ring screen, never by being closed as dismissed from outside.
     */
    suspend fun cancel(alarmId: Long, byUser: Boolean = false)

    /**
     * Silences what a ring left pending (snooze, backups, still-awake check and their notifications) without touching
     * the alarm's next regular ring. Used when the user switches the alarm off or removes it mid-flight; [byUser] as for
     * [cancel].
     */
    suspend fun cancelFollowUps(alarmId: Long, byUser: Boolean = false)

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
