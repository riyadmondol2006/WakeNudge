package com.riyadm.wakenudge.domain.usecase

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.ExceptionDate
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.model.withGlobalsOf
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.EditLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Changes that reach beyond one alarm: vacation, groups and profiles, exception days, calendar exceptions and the
 * ringing settings written onto every alarm. Each one that could stop or soften an alarm asks
 * [AlarmActions.guardGlobalChange] first, so a locked alarm cannot be defused through a side door. Changes that only
 * bring alarms back (ending a vacation, switching a group on, removing an exception day) are never refused.
 */
@Singleton
class GlobalChanges @Inject constructor(
    private val actions: AlarmActions,
    private val alarms: AlarmRepository,
    private val groups: GroupRepository,
    private val exceptions: ExceptionRepository,
    private val settings: SettingsRepository,
    private val scheduler: AlarmScheduler,
    private val time: TimeSource,
) {
    private fun today(): Long = time.now().toLocalDate().toEpochDay()

    /** Vacation pauses every alarm that does not ignore it, so starting one, or making one longer, is guarded. */
    suspend fun setVacation(enabled: Boolean, resumeEpochDay: Long?): ActionResult<VacationMode> {
        val before = settings.current().vacation
        val after = before.copy(enabled = enabled, resumeEpochDay = if (enabled) resumeEpochDay else null)
        return applyVacation(before, after)
    }

    /** Puts back a vacation exactly as it was (Undo, or the tile's toggle). */
    suspend fun replaceVacation(after: VacationMode): ActionResult<VacationMode> = applyVacation(settings.current().vacation, after)

    private suspend fun applyVacation(before: VacationMode, after: VacationMode): ActionResult<VacationMode> {
        if (pausesMore(before, after)) guard { a, _ -> a.enabled && !a.ignoreVacation }?.let { return ActionResult.Locked(it) }
        settings.update { it.copy(vacation = after) }
        scheduler.rescheduleAll("vacation")
        return ActionResult.Done(before)
    }

    private fun pausesMore(before: VacationMode, after: VacationMode): Boolean {
        val day = today()
        if (!after.activeOn(day)) return false
        if (!before.activeOn(day)) return true
        val beforeEnd = before.resumeEpochDay ?: Long.MAX_VALUE
        val afterEnd = after.resumeEpochDay ?: Long.MAX_VALUE
        return afterEnd > beforeEnd
    }

    /** Switching a group off silences its alarms; switching it on is never refused. */
    suspend fun setGroupEnabled(groupId: Long, enabled: Boolean): ActionResult<Unit> {
        if (!enabled) guard { a, _ -> a.groupId == groupId }?.let { return ActionResult.Locked(it) }
        groups.setEnabled(groupId, enabled)
        scheduler.rescheduleAll("group-toggle")
        return ActionResult.Done(Unit)
    }

    /** Switches to a profile: this group on, every other off. Returns each group's state before, for Undo. */
    suspend fun activateProfileOnly(groupId: Long): ActionResult<Pair<String, Map<Long, Boolean>>> {
        val all = groups.getGroups()
        val target = all.firstOrNull { it.id == groupId } ?: return ActionResult.NotFound
        val turnedOff = all.filter { it.enabled && it.id != groupId }.map { it.id }.toSet()
        guard { a, _ -> a.groupId in turnedOff }?.let { return ActionResult.Locked(it) }
        all.forEach { groups.setEnabled(it.id, it.id == groupId) }
        scheduler.rescheduleAll("profile-switch")
        return ActionResult.Done(target.name to all.associate { it.id to it.enabled })
    }

    /** Undo of [activateProfileOnly]; it switches groups off again, so it is guarded like any other switch-off. */
    suspend fun restoreGroupStates(previous: Map<Long, Boolean>): ActionResult<Unit> {
        val current = groups.getGroups().associate { it.id to it.enabled }
        val turnedOff = previous.filter { (id, on) -> !on && current[id] == true }.keys
        guard { a, _ -> a.groupId in turnedOff }?.let { return ActionResult.Locked(it) }
        previous.forEach { (id, enabled) -> if (id in current) groups.setEnabled(id, enabled) }
        scheduler.rescheduleAll("profile-switch undone")
        return ActionResult.Done(Unit)
    }

    /** A deleted group's alarms stay as they were in effect; refused anyway while one of them is locked. */
    suspend fun deleteGroup(groupId: Long): ActionResult<Unit> {
        guard { a, _ -> a.groupId == groupId }?.let { return ActionResult.Locked(it) }
        groups.delete(groupId)
        scheduler.rescheduleAll("group-delete")
        return ActionResult.Done(Unit)
    }

    /** An exception day silences the alarms that would ring that day, so it is refused for a locked one. */
    suspend fun addExceptionDate(epochDay: Long, label: String): ActionResult<Unit> {
        val zone = time.zone()
        guard { a, lock ->
            a.respectExceptions && (lock.whileRinging ||
                scheduler.nextTriggerOf(a.id)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() == epochDay } == true)
        }?.let { return ActionResult.Locked(it) }
        exceptions.add(ExceptionDate(epochDay, label.trim().take(40)))
        scheduler.rescheduleAll("exceptions")
        return ActionResult.Done(Unit)
    }

    suspend fun removeExceptionDate(epochDay: Long) {
        exceptions.remove(epochDay)
        scheduler.rescheduleAll("exceptions")
    }

    /** Calendar holidays can silence any alarm that respects exceptions: switching them on is guarded, off is not. */
    suspend fun setCalendarExceptions(enabled: Boolean): ActionResult<Unit> {
        if (enabled) guard { a, _ -> a.respectExceptions }?.let { return ActionResult.Locked(it) }
        settings.update { it.copy(calendarExceptions = it.calendarExceptions.copy(enabled = enabled)) }
        scheduler.rescheduleAll("calendar-exceptions")
        return ActionResult.Done(Unit)
    }

    suspend fun selectCalendar(id: Long, selected: Boolean): ActionResult<Unit> {
        if (selected && settings.current().calendarExceptions.enabled) guard { a, _ -> a.respectExceptions }?.let { return ActionResult.Locked(it) }
        settings.update {
            val ids = it.calendarExceptions.calendarIds
            it.copy(calendarExceptions = it.calendarExceptions.copy(calendarIds = if (selected) ids + id else ids - id))
        }
        scheduler.rescheduleAll("calendar-exceptions")
        return ActionResult.Done(Unit)
    }

    /**
     * The "While an alarm rings" choices, written onto every alarm and the defaults. Refused as a whole while an alarm
     * it would change is locked: its volume keys or coming back must not be changed the night before.
     */
    suspend fun applyRingingToAll(block: (AppSettings) -> AppSettings): ActionResult<Unit> {
        val next = block(settings.current())
        guard { a, _ -> a.antiOversleep.withGlobalsOf(next) != a.antiOversleep }?.let { return ActionResult.Locked(it) }
        settings.update { s -> block(s).let { n -> n.copy(defaults = n.defaults.copy(antiOversleep = n.defaults.antiOversleep.withGlobalsOf(n))) } }
        val now = settings.current()
        for (alarm in alarms.getAlarms()) {
            val copied = alarm.antiOversleep.withGlobalsOf(now)
            if (copied != alarm.antiOversleep) alarms.save(alarm.copy(antiOversleep = copied))
        }
        return ActionResult.Done(Unit)
    }

    /** A restore replaces every alarm, so it waits until no alarm is locked. */
    suspend fun restoreLock(): EditLock? = guard { _, _ -> true }

    private suspend fun guard(affects: suspend (com.riyadm.wakenudge.domain.model.Alarm, EditLock) -> Boolean): EditLock? =
        actions.guardGlobalChange(affects)
}
