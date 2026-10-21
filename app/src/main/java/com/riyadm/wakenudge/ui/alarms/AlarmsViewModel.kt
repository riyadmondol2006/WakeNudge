package com.riyadm.wakenudge.ui.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.EditLockPolicy
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.domain.usecase.ActionResult
import com.riyadm.wakenudge.domain.usecase.ActiveRingQuery
import com.riyadm.wakenudge.domain.usecase.AlarmActions
import com.riyadm.wakenudge.engine.SchedulingContextProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AlarmItem(
    val alarm: Alarm,
    val group: AlarmGroup?,
    /** Next ring honouring vacation/exceptions/skip, or null when it will not ring. */
    val nextAt: ZonedDateTime?,
    /** Why the ring that would normally come next is suppressed, if it is. */
    val suppression: NextTriggerCalculator.Suppression?,
    val lockedMinutes: Int?,
) {
    val effectivelyEnabled: Boolean get() = alarm.enabled && (group?.enabled ?: true)
}

data class AlarmsUiState(
    val loaded: Boolean = false,
    val items: List<AlarmItem> = emptyList(),
    val groups: List<AlarmGroup> = emptyList(),
    val filterGroupId: Long? = null,
    val settings: AppSettings = AppSettings(),
    val now: ZonedDateTime = ZonedDateTime.now(),
    /** The soonest ring across all alarms (unfiltered). */
    val next: AlarmItem? = null,
)

sealed interface AlarmsEvent {
    data class Locked(val minutes: Int) : AlarmsEvent
    data class Deleted(val alarm: Alarm) : AlarmsEvent
    data class Skipped(val epochDay: Long?) : AlarmsEvent
}

@HiltViewModel
class AlarmsViewModel @Inject constructor(
    alarms: AlarmRepository,
    groups: GroupRepository,
    exceptions: ExceptionRepository,
    private val settings: SettingsRepository,
    private val groupRepo: GroupRepository,
    private val actions: AlarmActions,
    private val scheduler: AlarmScheduler,
    private val calculator: NextTriggerCalculator,
    private val contextProvider: SchedulingContextProvider,
    private val ringQuery: ActiveRingQuery,
    private val time: TimeSource,
) : ViewModel() {

    private val filter = MutableStateFlow<Long?>(null)
    private val _events = Channel<AlarmsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val ticker = flow {
        while (true) {
            emit(time.nowMillis())
            delay(TICK_MS)
        }
    }

    val state: StateFlow<AlarmsUiState> = combine(
        alarms.observeAlarms(), groups.observeGroups(), settings.settings, exceptions.observeDates(), filter,
    ) { a, g, s, _, f -> Inputs(a, g, s, f) }
        .combine(ticker) { inputs, _ -> inputs }
        .map { build(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmsUiState())

    private data class Inputs(val alarms: List<Alarm>, val groups: List<AlarmGroup>, val settings: AppSettings, val filter: Long?)

    private suspend fun build(i: Inputs): AlarmsUiState {
        val now = time.now()
        val ctx = contextProvider.load()
        val byGroup = i.groups.associateBy { it.id }
        val items = i.alarms.map { alarm ->
            val sc = ctx.forAlarm(alarm)
            val next = calculator.next(alarm, now, sc)
            val suppression = if (alarm.enabled) calculator.suppressionOfNaturalNext(alarm, now, sc) else null
            val locked = EditLockPolicy.lockedMinutes(
                alarm, next?.toInstant()?.toEpochMilli(), now.toInstant().toEpochMilli(), ringQuery.isActiveOrSnoozed(alarm.id),
            )
            AlarmItem(alarm, alarm.groupId?.let(byGroup::get), next, suppression, locked)
        }
        val visible = items.filter { i.filter == null || it.alarm.groupId == i.filter }
        // Soonest first among enabled alarms, then the disabled ones by clock time.
        val sorted = visible.sortedWith(
            compareBy<AlarmItem> { it.nextAt == null }
                .thenBy { it.nextAt?.toInstant() }
                .thenBy { it.alarm.hour * 60 + it.alarm.minute },
        )
        return AlarmsUiState(
            loaded = true, items = sorted, groups = i.groups, filterGroupId = i.filter, settings = i.settings, now = now,
            next = items.filter { it.nextAt != null }.minByOrNull { it.nextAt!!.toInstant() },
        )
    }

    fun setFilter(groupId: Long?) {
        filter.value = groupId
    }

    fun toggle(item: AlarmItem, enabled: Boolean) = viewModelScope.launch {
        // Turning on an alarm whose group is off also needs the group on, or nothing would change for the user.
        if (enabled && item.group?.enabled == false) groupRepo.setEnabled(item.group.id, true)
        when (val r = actions.setEnabled(item.alarm.id, enabled)) {
            is ActionResult.Locked -> _events.send(AlarmsEvent.Locked(r.minutes))
            else -> Unit
        }
    }

    fun delete(item: AlarmItem) = viewModelScope.launch {
        when (val r = actions.delete(item.alarm.id)) {
            is ActionResult.Locked -> _events.send(AlarmsEvent.Locked(r.minutes))
            is ActionResult.Done -> _events.send(AlarmsEvent.Deleted(item.alarm))
            ActionResult.NotFound -> Unit
        }
    }

    fun undoDelete(alarm: Alarm) = viewModelScope.launch { actions.restore(alarm) }

    fun skipNext(item: AlarmItem) = viewModelScope.launch {
        when (val r = actions.skipNext(item.alarm.id)) {
            is ActionResult.Locked -> _events.send(AlarmsEvent.Locked(r.minutes))
            is ActionResult.Done -> _events.send(AlarmsEvent.Skipped(r.value))
            ActionResult.NotFound -> Unit
        }
    }

    fun clearSkip(item: AlarmItem) = viewModelScope.launch { actions.clearSkip(item.alarm.id) }

    fun duplicate(item: AlarmItem) = viewModelScope.launch { actions.duplicate(item.alarm.id) }

    fun setGroupEnabled(groupId: Long, enabled: Boolean) = viewModelScope.launch {
        groupRepo.setEnabled(groupId, enabled)
        scheduler.rescheduleAll("group-toggle")
    }

    /** Switches to a profile: turns this group on and every other group off. */
    fun activateProfileOnly(groupId: Long) = viewModelScope.launch {
        groupRepo.getGroups().forEach { groupRepo.setEnabled(it.id, it.id == groupId) }
        scheduler.rescheduleAll("profile-switch")
    }

    fun setVacation(enabled: Boolean, resumeEpochDay: Long? = null) = viewModelScope.launch {
        settings.update { it.copy(vacation = it.vacation.copy(enabled = enabled, resumeEpochDay = if (enabled) resumeEpochDay else null)) }
        scheduler.rescheduleAll("vacation")
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
