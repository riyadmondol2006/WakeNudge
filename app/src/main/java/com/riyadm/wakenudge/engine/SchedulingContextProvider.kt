package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.SchedulingContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A snapshot of vacation, exception dates and group switches, taken once per scheduling pass. */
class LoadedSchedulingContext(
    private val vacation: VacationMode,
    private val exceptionDays: Set<Long>,
    private val groupEnabled: Map<Long, Boolean>,
) {
    fun forAlarm(alarm: Alarm) = SchedulingContext(
        vacation = vacation,
        exceptionDays = exceptionDays,
        groupEnabled = alarm.groupId?.let { groupEnabled[it] } ?: true,
    )
}

@Singleton
class SchedulingContextProvider @Inject constructor(
    private val settings: SettingsRepository,
    private val groups: GroupRepository,
    private val exceptions: ExceptionRepository,
    private val calendar: CalendarHolidayProvider,
    private val time: TimeSource,
) {
    suspend fun load(): LoadedSchedulingContext {
        val s = settings.current()
        val manual = exceptions.getDates().map { it.epochDay }.toSet()
        val holidays = if (s.calendarExceptions.enabled) {
            withContext(Dispatchers.IO) {
                calendar.holidayDays(s.calendarExceptions.calendarIds, time.now().toLocalDate(), HORIZON_DAYS)
            }
        } else {
            emptySet()
        }
        return LoadedSchedulingContext(
            vacation = s.vacation,
            exceptionDays = manual + holidays,
            groupEnabled = groups.getGroups().associate { it.id to it.enabled },
        )
    }

    private companion object {
        const val HORIZON_DAYS = 400
    }
}
