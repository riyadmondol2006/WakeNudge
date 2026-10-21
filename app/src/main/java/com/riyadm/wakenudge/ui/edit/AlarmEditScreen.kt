package com.riyadm.wakenudge.ui.edit

import androidx.compose.ui.platform.LocalResources
import com.riyadm.wakenudge.ui.util.currentLocale
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.ui.components.AlarmTimePicker
import com.riyadm.wakenudge.ui.components.ConfirmDialog
import com.riyadm.wakenudge.ui.components.DayChip
import com.riyadm.wakenudge.ui.components.SectionTitle
import com.riyadm.wakenudge.ui.components.SettingsGroup
import com.riyadm.wakenudge.ui.components.TimeEntryDialog
import com.riyadm.wakenudge.ui.components.ValueRow
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.util.Formatters
import com.riyadm.wakenudge.ui.util.rememberIs24Hour
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.launch

/** Slots for the mission editors, supplied by the navigation layer so this screen stays free of mission internals. */
class MissionSlots(
    val missions: @Composable (alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit) -> Unit,
    val snoozeMissions: @Composable (alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit) -> Unit,
)

@Composable
fun AlarmEditScreen(
    onClose: () -> Unit,
    missionSlots: MissionSlots,
    modifier: Modifier = Modifier,
    viewModel: AlarmEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalWakeHaptics.current

    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showOverride by remember { mutableStateOf(false) }
    val expanded = rememberExpandedCategories()

    BackHandler(enabled = state.dirty) { confirmDiscard = true }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.edit_title_new else R.string.edit_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (state.dirty) confirmDiscard = true else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                    }
                    Button(
                        enabled = state.loaded && !state.readOnly,
                        onClick = {
                            scope.launch {
                                when (val r = viewModel.save()) {
                                    SaveOutcome.Saved -> { haptics.confirm(); onClose() }
                                    is SaveOutcome.Locked -> snackbar.showSnackbar(resources.getString(R.string.snackbar_locked, r.minutes))
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 12.dp),
                    ) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val alarm = state.draft
        val enabled = !state.readOnly
        val update = viewModel::update
        val is24 = rememberIs24Hour(settings.timeFormat)
        val firstDay = settings.firstDayOfWeek(WeekFields.of(currentLocale()).firstDayOfWeek)

        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 48.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.lockedMinutes != null && !state.lockOverridden) {
                item { LockBanner(state.lockedMinutes!!, onUnlock = { showOverride = true }) }
            }
            item { TimeCard(alarm, state.upcoming, is24, enabled, update) }
            item { ScheduleCard(alarm, firstDay, enabled, update) }
            item {
                OutlinedTextField(
                    value = alarm.label,
                    onValueChange = { v -> if (v.length <= 60) update { it.copy(label = v) } },
                    label = { Text(stringResource(R.string.edit_label)) },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            if (groups.isNotEmpty()) {
                item {
                    SettingsGroup {
                        item { shape ->
                            val options = listOf<Long?>(null) + groups.map { it.id }
                            com.riyadm.wakenudge.ui.components.ChipChoiceRow(
                                title = stringResource(R.string.edit_group),
                                options = options,
                                selected = alarm.groupId,
                                onSelect = { g -> update { it.copy(groupId = g) } },
                                optionLabel = { id -> if (id == null) stringResource(R.string.group_none) else groups.first { it.id == id }.name },
                                shape = shape, enabled = enabled,
                            )
                        }
                    }
                }
            }
            alarmCategories(
                alarm = alarm, enabled = enabled, expanded = expanded,
                options = CategoryOptions(perAlarm = true, missionSlots = missionSlots),
                previewer = viewModel.soundPreviewer, vibrator = viewModel.vibrator, onChange = update,
            )
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.edit_discard_title),
            text = stringResource(R.string.edit_discard_body),
            confirmLabel = stringResource(R.string.edit_discard),
            onConfirm = onClose,
            onDismiss = { confirmDiscard = false },
            destructive = true,
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.edit_delete_title),
            text = stringResource(R.string.edit_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                scope.launch {
                    when (val r = viewModel.delete()) {
                        SaveOutcome.Saved -> onClose()
                        is SaveOutcome.Locked -> snackbar.showSnackbar(resources.getString(R.string.snackbar_locked, r.minutes))
                    }
                }
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
    if (showOverride) {
        EditLockOverrideDialog(onSolved = viewModel::overrideLock, onDismiss = { showOverride = false })
    }
}

@Composable
private fun LockBanner(minutes: Int, onUnlock: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.lock_banner_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(stringResource(R.string.lock_banner_body, minutes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            TextButton(onClick = onUnlock) { Text(stringResource(R.string.lock_banner_unlock)) }
        }
    }
}

@Composable
private fun TimeCard(alarm: Alarm, upcoming: List<java.time.ZonedDateTime>, is24: Boolean, enabled: Boolean, update: ((Alarm) -> Alarm) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var typing by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(36.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 16.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.then(if (enabled) Modifier else Modifier.padding(0.dp))) {
                AlarmTimePicker(alarm.hour, alarm.minute, is24, onChange = { h, m -> if (enabled) update { it.copy(hour = h, minute = m) } })
            }
            val first = upcoming.firstOrNull()
            val now = java.time.ZonedDateTime.now()
            Text(
                text = if (first != null) {
                    Formatters.untilText(context, now.toInstant().toEpochMilli(), first.toInstant().toEpochMilli()) + "  ·  " + Formatters.relativeDay(context, first, now)
                } else stringResource(R.string.edit_never_rings),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (upcoming.size > 1) {
                Text(
                    upcoming.drop(1).joinToString("  ·  ") { Formatters.dayAndDate(it) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { typing = true }, enabled = enabled) {
                Icon(Icons.Outlined.Edit, null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.picker_type_time))
            }
        }
    }
    if (typing) {
        TimeEntryDialog(alarm.hour, alarm.minute, is24, onConfirm = { h, m -> update { it.copy(hour = h, minute = m) } }, onDismiss = { typing = false })
    }
}

@Composable
private fun ScheduleCard(alarm: Alarm, firstDay: DayOfWeek, enabled: Boolean, update: ((Alarm) -> Alarm) -> Unit) {
    val haptics = LocalWakeHaptics.current
    var picking by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.edit_repeat), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Formatters.orderedDays(firstDay).forEach { day ->
                    DayChip(
                        label = day.getDisplayName(TextStyle.NARROW, currentLocale()),
                        fullName = day.getDisplayName(TextStyle.FULL, currentLocale()),
                        selected = alarm.repeatsOn(day),
                        enabled = enabled,
                        onToggle = { on ->
                            update {
                                val mask = if (on) it.repeatDaysMask or (1 shl (day.value - 1)) else it.repeatDaysMask and (1 shl (day.value - 1)).inv()
                                it.copy(repeatDaysMask = mask, oneTimeEpochDay = if (mask != 0) null else it.oneTimeEpochDay)
                            }
                        },
                    )
                }
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetChip(R.string.repeat_once, alarm.repeatDaysMask == 0, enabled) { update { it.copy(repeatDaysMask = 0) } }
                PresetChip(R.string.repeat_weekdays, alarm.repeatDaysMask == Alarm.WEEKDAYS_MASK, enabled) { update { it.copy(repeatDaysMask = Alarm.WEEKDAYS_MASK, oneTimeEpochDay = null) } }
                PresetChip(R.string.repeat_weekends, alarm.repeatDaysMask == Alarm.WEEKEND_MASK, enabled) { update { it.copy(repeatDaysMask = Alarm.WEEKEND_MASK, oneTimeEpochDay = null) } }
                PresetChip(R.string.repeat_every_day, alarm.repeatDaysMask == Alarm.EVERY_DAY_MASK, enabled) { update { it.copy(repeatDaysMask = Alarm.EVERY_DAY_MASK, oneTimeEpochDay = null) } }
            }
            if (!alarm.isRepeating) {
                SettingsGroup {
                    item { shape ->
                        ValueRow(
                            title = stringResource(R.string.edit_date),
                            value = alarm.oneTimeDate?.let { Formatters.dateShort(it) } ?: stringResource(R.string.edit_date_next),
                            icon = Icons.Outlined.CalendarMonth, shape = shape, enabled = enabled, onClick = { haptics.tick(); picking = true },
                        )
                    }
                }
            }
        }
    }
    if (picking) {
        val initial = (alarm.oneTimeDate ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        update { it.copy(oneTimeEpochDay = date.toEpochDay()) }
                    }
                    picking = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { update { it.copy(oneTimeEpochDay = null) }; picking = false }) { Text(stringResource(R.string.edit_date_clear)) }
                    TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        ) { DatePicker(state) }
    }
}

@Composable
private fun PresetChip(label: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val haptics = LocalWakeHaptics.current
    FilterChip(selected = selected, enabled = enabled, onClick = { haptics.tick(); onClick() }, label = { Text(stringResource(label)) })
}
