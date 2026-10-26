package com.riyadm.wakenudge.domain.usecase

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.GroupRepository
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
    private val groups: GroupRepository,
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
        val stored = if (alarm.id != 0L) alarms.getAlarm(alarm.id) else null
        val hour = alarm.hour.coerceIn(0, 23)
        val minute = alarm.minute.coerceIn(0, 59)
        val scheduleChanged = stored == null || stored.hour != hour || stored.minute != minute ||
            stored.repeatDaysMask != alarm.repeatDaysMask || stored.oneTimeEpochDay != alarm.oneTimeEpochDay
        val normalized = alarm.copy(
            hour = hour,
            minute = minute,
            label = alarm.label.trim().truncateCodePoints(MAX_LABEL),
            // A "skip next" belongs to the schedule it was set on: moving 07:00 to 08:00 to sleep in must still ring today.
            skipEpochDay = if (scheduleChanged) null else alarm.skipEpochDay,
        )
        val id = alarms.save(normalized)
        scheduler.schedule(id)
        surfaces.refreshAll()
        diagnostics.info("Alarms", "saved alarm $id")
        return ActionResult.Done(id)
    }

    /** Puts a just-deleted alarm back under its old id ("Undo"). */
    suspend fun restore(alarm: Alarm) {
        val id = alarms.save(alarm)
        scheduler.schedule(id)
        surfaces.refreshAll()
    }

    /**
     * Switches one alarm on or off. Switching on an alarm whose group is off turns the group on too (otherwise nothing
     * would change for the user), and then every alarm of that group is armed again, not just this one. The result
     * carries the group's name in that case, so the caller can say that the group's other alarms are back.
     */
    suspend fun setEnabled(id: Long, enabled: Boolean, overrideLock: Boolean = false): ActionResult<String?> {
        val alarm = alarms.getAlarm(id) ?: return ActionResult.NotFound
        if (!enabled && !overrideLock) lockedMinutes(alarm)?.let { return ActionResult.Locked(it) }
        val group = if (enabled) alarm.groupId?.let { gid -> groups.getGroups().firstOrNull { it.id == gid } } else null
        val groupTurnedOn = group != null && !group.enabled
        if (groupTurnedOn) groups.setEnabled(group!!.id, true)
        alarms.setEnabled(id, enabled)
        if (enabled) alarms.setSkipDay(id, null)
        if (groupTurnedOn) scheduler.rescheduleAll("group switched on with alarm $id") else scheduler.schedule(id)
        // Switching an alarm off means "stop bothering me": a pending snooze or backup must not ring afterwards.
        if (!enabled) scheduler.cancelFollowUps(id)
        surfaces.refreshAll()
        return ActionResult.Done(if (groupTurnedOn) group!!.name else null)
    }

    suspend fun delete(id: Long, overrideLock: Boolean = false): ActionResult<Unit> {
        val alarm = alarms.getAlarm(id) ?: return ActionResult.NotFound
        if (!overrideLock) lockedMinutes(alarm)?.let { return ActionResult.Locked(it) }
        alarms.delete(id)
        scheduler.cancel(id)
        surfaces.refreshAll()
        return ActionResult.Done(Unit)
    }

    /**
     * Suppresses just the next occurrence of a repeating alarm; for a one-time alarm this switches it off.
     *
     * [occurrenceAt] is the ring the button was shown for (a heads-up notification, a widget). Skipping it twice is the
     * same as skipping it once: a double tap, or a heads-up still on screen after the skip was set in the app, must not
     * move the skip on to the following day and let today's ring through.
     */
    suspend fun skipNext(id: Long, occurrenceAt: Long? = null, overrideLock: Boolean = false): ActionResult<Long?> {
        val alarm = alarms.getAlarm(id) ?: return ActionResult.NotFound
        if (!overrideLock) lockedMinutes(alarm)?.let { return ActionResult.Locked(it) }
        if (!alarm.isRepeating) {
            alarms.setEnabled(id, false)
            scheduler.schedule(id)
            scheduler.dropUpcomingNotice(id)
            surfaces.refreshAll()
            return ActionResult.Done(null)
        }
        // A button drawn before the ring it names has passed (a list or widget not yet redrawn) means the next one.
        val target = occurrenceAt?.takeIf { it > time.nowMillis() } ?: scheduler.nextTriggerOf(id) ?: return ActionResult.Done(null)
        val day = java.time.Instant.ofEpochMilli(target).atZone(time.zone()).toLocalDate().toEpochDay()
        if (alarm.skipEpochDay != day) {
            alarms.setSkipDay(id, day)
            scheduler.schedule(id)
        }
        scheduler.dropUpcomingNotice(id)
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

/** Cuts at a code-point boundary so emoji and other supplementary characters are never split into garbage. */
internal fun String.truncateCodePoints(maxCodePoints: Int): String =
    if (codePointCount(0, length) <= maxCodePoints) this else substring(0, offsetByCodePoints(0, maxCodePoints))

/** Lets the domain layer ask whether an alarm is ringing or snoozed without depending on the engine. */
interface ActiveRingQuery {
    suspend fun isActiveOrSnoozed(alarmId: Long): Boolean
}
