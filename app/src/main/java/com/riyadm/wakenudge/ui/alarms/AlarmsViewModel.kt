package com.riyadm.wakenudge.ui.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.EditLock
import com.riyadm.wakenudge.domain.scheduling.EditLockPolicy
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.domain.usecase.ActionResult
import com.riyadm.wakenudge.domain.usecase.ActiveRingQuery
import com.riyadm.wakenudge.domain.usecase.AlarmActions
import com.riyadm.wakenudge.domain.usecase.GlobalChanges
import com.riyadm.wakenudge.engine.SchedulingContextProvider
import com.riyadm.wakenudge.ui.util.persist
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
    val lock: EditLock?,
) {
    val lockedMinutes: Int? get() = lock?.minutes
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
    data class Locked(val lock: EditLock) : AlarmsEvent
    data class Deleted(val alarm: Alarm) : AlarmsEvent
    data class Skipped(val epochDay: Long?) : AlarmsEvent

    /**
     * An alarm was switched on: when it will ring (so 7:00 in the evening instead of the morning is caught at once), and
     * the name of its group when the group had to be switched on with it, which brings the group's other alarms back.
     */
    data class TurnedOn(val ringsAt: Long?, val groupTurnedOn: String?) : AlarmsEvent

    /** Every alarm was paused with one tap; [previous] is what Undo puts back. */
    data class Paused(val previous: VacationMode) : AlarmsEvent

    /** "Only this" switched the other profiles off; [previous] is each profile's on/off state before, for Undo. */
    data class ProfileOnly(val name: String, val previous: Map<Long, Boolean>) : AlarmsEvent

    /** A copy was added; the snackbar offers to open it, since it looks exactly like the original. */
    data class Duplicated(val id: Long) : AlarmsEvent
}

@HiltViewModel
class AlarmsViewModel @Inject constructor(
    alarms: AlarmRepository,
    groups: GroupRepository,
    exceptions: ExceptionRepository,
    private val settings: SettingsRepository,
    private val actions: AlarmActions,
    private val global: GlobalChanges,
    private val scheduler: AlarmScheduler,
    private val calculator: NextTriggerCalculator,
    private val contextProvider: SchedulingContextProvider,
    private val ringQuery: ActiveRingQuery,
    private val time: TimeSource,
    deletedInEditor: DeletedAlarmRelay,
) : ViewModel() {

    private val filter = MutableStateFlow<Long?>(null)
    private val _events = Channel<AlarmsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Deleted from the editor: the list shows the same Undo as for a delete made here.
        viewModelScope.launch { deletedInEditor.alarms.collect { _events.send(AlarmsEvent.Deleted(it)) } }
    }

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
            val locked = EditLockPolicy.lockOf(
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
        // AlarmActions also switches the alarm's group on when it is off, and re-arms the whole group.
        when (val r = actions.setEnabled(item.alarm.id, enabled)) {
            is ActionResult.Locked -> _events.send(AlarmsEvent.Locked(r.lock))
            is ActionResult.Done -> if (enabled) _events.send(AlarmsEvent.TurnedOn(scheduler.nextTriggerOf(item.alarm.id), r.value))
            ActionResult.NotFound -> Unit
        }
    }

    fun delete(item: AlarmItem) = viewModelScope.launch {
        when (val r = actions.delete(item.alarm.id)) {
            is ActionResult.Locked -> _events.send(AlarmsEvent.Locked(r.lock))
            is ActionResult.Done -> _events.send(AlarmsEvent.Deleted(item.alarm))
            ActionResult.NotFound -> Unit
        }
    }

    fun undoDelete(alarm: Alarm) = viewModelScope.launch { actions.restore(alarm) }

    fun skipNext(item: AlarmItem) = viewModelScope.launch {
        when (val r = actions.skipNext(item.alarm.id, occurrenceAt = item.nextAt?.toInstant()?.toEpochMilli())) {
            is ActionResult.Locked -> _events.send(AlarmsEvent.Locked(r.lock))
            is ActionResult.Done -> _events.send(AlarmsEvent.Skipped(r.value))
            ActionResult.NotFound -> Unit
        }
    }

    fun clearSkip(item: AlarmItem) = viewModelScope.launch { actions.clearSkip(item.alarm.id) }

    fun duplicate(item: AlarmItem) = viewModelScope.launch {
        actions.duplicate(item.alarm.id)?.let { _events.send(AlarmsEvent.Duplicated(it)) }
    }

    // Each of these can silence alarms other than the one on a card, so each goes through the same lock as a card's switch.

    fun setGroupEnabled(groupId: Long, enabled: Boolean) = persist {
        (global.setGroupEnabled(groupId, enabled) as? ActionResult.Locked)?.let { _events.send(AlarmsEvent.Locked(it.lock)) }
    }

    /** Switches to a profile: turns this group on and every other group off. */
    fun activateProfileOnly(groupId: Long) = persist {
        when (val r = global.activateProfileOnly(groupId)) {
            is ActionResult.Locked -> _events.send(AlarmsEvent.Locked(r.lock))
            // One tap can silence whole profiles of alarms, so it is said out loud and can be taken back.
            is ActionResult.Done -> _events.send(AlarmsEvent.ProfileOnly(r.value.first, r.value.second))
            ActionResult.NotFound -> Unit
        }
    }

    fun undoProfileOnly(previous: Map<Long, Boolean>) = persist {
        (global.restoreGroupStates(previous) as? ActionResult.Locked)?.let { _events.send(AlarmsEvent.Locked(it.lock)) }
    }

    fun setVacation(enabled: Boolean, resumeEpochDay: Long? = null) = persist {
        when (val r = global.setVacation(enabled, resumeEpochDay)) {
            is ActionResult.Locked -> _events.send(AlarmsEvent.Locked(r.lock))
            // One tap silences every alarm, so it can be taken back as easily.
            is ActionResult.Done -> if (enabled && !r.value.enabled) _events.send(AlarmsEvent.Paused(r.value))
            ActionResult.NotFound -> Unit
        }
    }

    fun undoPause(previous: VacationMode) = persist {
        (global.replaceVacation(previous) as? ActionResult.Locked)?.let { _events.send(AlarmsEvent.Locked(it.lock)) }
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
