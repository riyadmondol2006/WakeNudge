package com.riyadm.wakenudge.domain.usecase

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.EditLockPolicy
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ActionResult<out T> {
    data class Done<T>(val value: T) : ActionResult<T>
    /** Refused by last-minute edit protection; [minutes] is the remaining lock. */
    data class Locked(val minutes: Int) : ActionResult<Nothing>
    data object NotFound : ActionResult<Nothing>
}

/** Every user-initiated change to an alarm goes through here so persistence, scheduling and surfaces stay in sync. */
@Singleton
class AlarmActions @Inject constructor(
    private val alarms: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val surfaces: SurfaceRefresher,
    private val time: TimeSource,
    private val ringState: ActiveRingQuery,
    private val diagnostics: DiagnosticsLog,
) {
    /** Remaining edit-lock minutes for [alarm], or null when it may be changed. */
    suspend fun lockedMinutes(alarm: Alarm): Int? {
        if (alarm.id == 0L) return null
        val stored = alarms.getAlarm(alarm.id) ?: return null
        return EditLockPolicy.lockedMinutes(
            alarm = stored,
            nextTriggerAt = scheduler.nextTriggerOf(stored.id),
            nowMillis = time.nowMillis(),
            activeOrSnoozed = ringState.isActiveOrSnoozed(stored.id),
        )
    }

    suspend fun save(alarm: Alarm, overrideLock: Boolean = false): ActionResult<Long> {
        if (!overrideLock) lockedMinutes(alarm)?.let { return ActionResult.Locked(it) }
        val normalized = alarm.copy(
            hour = alarm.hour.coerceIn(0, 23),
            minute = alarm.minute.coerceIn(0, 59),
            label = alarm.label.trim().take(MAX_LABEL),
            // Editing the schedule invalidates a pending "skip next".
            skipEpochDay = alarm.skipEpochDay,
        )
        val id = alarms.save(normalized)
        scheduler.schedule(id)
        surfaces.refreshAll()
        diagnostics.info("Alarms", "saved alarm $id")
        return ActionResult.Done(id)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean, overrideLock: Boolean = false): ActionResult<Unit> {
        val alarm = alarms.getAlarm(id) ?: return ActionResult.NotFound
        if (!enabled && !overrideLock) lockedMinutes(alarm)?.let { return ActionResult.Locked(it) }
        alarms.setEnabled(id, enabled)
        if (enabled) alarms.setSkipDay(id, null)
        scheduler.schedule(id)
        surfaces.refreshAll()
        return ActionResult.Done(Unit)
    }

    suspend fun delete(id: Long, overrideLock: Boolean = false): ActionResult<Unit> {
        val alarm = alarms.getAlarm(id) ?: return ActionResult.NotFound
        if (!overrideLock) lockedMinutes(alarm)?.let { return ActionResult.Locked(it) }
        alarms.delete(id)
        scheduler.cancel(id)
        surfaces.refreshAll()
        return ActionResult.Done(Unit)
    }

    /** Suppresses just the next occurrence of a repeating alarm; for a one-time alarm this switches it off. */
    suspend fun skipNext(id: Long, overrideLock: Boolean = false): ActionResult<Long?> {
        val alarm = alarms.getAlarm(id) ?: return ActionResult.NotFound
        if (!overrideLock) lockedMinutes(alarm)?.let { return ActionResult.Locked(it) }
        if (!alarm.isRepeating) {
            alarms.setEnabled(id, false)
            scheduler.schedule(id)
            return ActionResult.Done(null)
        }
        val next = scheduler.nextTriggerOf(id) ?: return ActionResult.Done(null)
        val day = java.time.Instant.ofEpochMilli(next).atZone(time.zone()).toLocalDate().toEpochDay()
        alarms.setSkipDay(id, day)
        scheduler.schedule(id)
        surfaces.refreshAll()
        return ActionResult.Done(day)
    }

    suspend fun clearSkip(id: Long) {
        alarms.setSkipDay(id, null)
        scheduler.schedule(id)
        surfaces.refreshAll()
    }

    suspend fun duplicate(id: Long): Long? {
        val original = alarms.getAlarm(id) ?: return null
        val copy = original.copy(id = 0, createdAt = 0, skipEpochDay = null, enabled = true)
        val newId = alarms.save(copy)
        scheduler.schedule(newId)
        surfaces.refreshAll()
        return newId
    }

    private companion object {
        const val MAX_LABEL = 60
    }
}

/** Lets the domain layer ask whether an alarm is ringing or snoozed without depending on the engine. */
interface ActiveRingQuery {
    suspend fun isActiveOrSnoozed(alarmId: Long): Boolean
}
