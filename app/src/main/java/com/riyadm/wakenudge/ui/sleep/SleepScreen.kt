package com.riyadm.wakenudge.ui.sleep

import androidx.compose.material.icons.outlined.CheckCircle
import com.riyadm.wakenudge.ui.util.currentLocale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.scheduling.SleepOption
import com.riyadm.wakenudge.domain.scheduling.SleepPlanner
import com.riyadm.wakenudge.ui.components.AlarmTimePicker
import com.riyadm.wakenudge.ui.components.DayChip
import com.riyadm.wakenudge.ui.components.SectionTitle
import com.riyadm.wakenudge.ui.components.SettingsGroup
import com.riyadm.wakenudge.ui.components.StepperRow
import com.riyadm.wakenudge.ui.components.SwitchRow
import com.riyadm.wakenudge.ui.components.ValueRow
import com.riyadm.wakenudge.ui.components.TimeEntryDialog
import com.riyadm.wakenudge.ui.util.Formatters
import com.riyadm.wakenudge.ui.util.minutesLabel
import com.riyadm.wakenudge.ui.util.rememberIs24Hour
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private enum class PlannerMode { WAKE_AT, BED_AT }

@Composable
fun SleepScreen(
    onBack: () -> Unit,
    onCreateAlarm: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SleepViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val nextWake by viewModel.nextWake.collectAsStateWithLifecycle()
    val is24 = rememberIs24Hour(settings.timeFormat)
    val bed = settings.bedtime
    var mode by rememberSaveable { mutableStateOf(PlannerMode.WAKE_AT) }
    var hour by rememberSaveable { mutableIntStateOf(7) }
    var minute by rememberSaveable { mutableIntStateOf(0) }
    var seeded by rememberSaveable { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    // Start from the user's real next alarm, once.
    LaunchedEffect(nextWake) {
        val wake = nextWake
        if (!seeded && wake != null) {
            hour = wake.hour; minute = wake.minute; seeded = true
        }
    }
    val time = LocalTime.of(hour, minute)
    val options = if (mode == PlannerMode.WAKE_AT) SleepPlanner.bedtimesFor(time, bed.fallAsleepMinutes) else SleepPlanner.wakeTimesFor(time, bed.fallAsleepMinutes)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sleep_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PlannerMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = mode == m, onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(i, PlannerMode.entries.size),
                        ) { Text(stringResource(if (m == PlannerMode.WAKE_AT) R.string.sleep_mode_wake else R.string.sleep_mode_bed)) }
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AlarmTimePicker(hour, minute, is24, onChange = { h, m -> hour = h; minute = m })
                        androidx.compose.material3.TextButton(onClick = { typing = true }) { Text(stringResource(R.string.picker_type_time)) }
                    }
                }
            }
            item {
                SettingsGroup {
                    item { shape ->
                        StepperRow(
                            title = stringResource(R.string.sleep_fall_asleep),
                            subtitle = stringResource(R.string.sleep_fall_asleep_hint),
                            valueText = minutesLabel(bed.fallAsleepMinutes),
                            onMinus = { viewModel.updateBedtime { it.copy(fallAsleepMinutes = (it.fallAsleepMinutes - 5).coerceAtLeast(0)) } },
                            onPlus = { viewModel.updateBedtime { it.copy(fallAsleepMinutes = (it.fallAsleepMinutes + 5).coerceAtMost(60)) } },
                            canMinus = bed.fallAsleepMinutes > 0, canPlus = bed.fallAsleepMinutes < 60, shape = shape,
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(if (mode == PlannerMode.WAKE_AT) R.string.sleep_results_bed else R.string.sleep_results_wake),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                )
            }
            items(options.size) { i ->
                OptionCard(
                    option = options[i], recommended = options[i].cycles == 5, is24 = is24, mode = mode,
                    reminderSet = bed.enabled && bed.hour == options[i].time.hour && bed.minute == options[i].time.minute,
                    onRemind = { viewModel.updateBedtime { it.copy(enabled = true, hour = options[i].time.hour, minute = options[i].time.minute) } },
                    onAlarm = { onCreateAlarm(options[i].time.hour, options[i].time.minute) },
                )
            }
            item { BedtimeSettingsCard(viewModel, is24) }
        }
    }
    if (typing) {
        TimeEntryDialog(hour, minute, is24, onConfirm = { h, m -> hour = h; minute = m }, onDismiss = { typing = false })
    }
}

@Composable
private fun OptionCard(
    option: SleepOption,
    recommended: Boolean,
    is24: Boolean,
    mode: PlannerMode,
    reminderSet: Boolean,
    onRemind: () -> Unit,
    onAlarm: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = if (recommended) scheme.primaryContainer else scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(TimeText.format(option.time, is24), style = MaterialTheme.typography.displaySmall)
                    Text(
                        stringResource(R.string.sleep_option_summary, option.cycles, "%.1f".format(option.sleepMinutes / 60f)),
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    )
                }
                if (recommended) Text(stringResource(R.string.sleep_recommended), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == PlannerMode.WAKE_AT) {
                    // The bedtime setting it changes is far down the page, so the card itself shows that it took.
                    if (reminderSet) {
                        OutlinedButton(onClick = {}, enabled = false) {
                            Icon(Icons.Outlined.CheckCircle, null, Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.sleep_reminder_set))
                        }
                    } else {
                        Button(onClick = onRemind) {
                            Icon(Icons.Outlined.Bedtime, null, Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.sleep_remind_me))
                        }
                    }
                } else {
                    OutlinedButton(onClick = onAlarm) { Text(stringResource(R.string.sleep_set_alarm)) }
                }
            }
        }
    }
}

@Composable
private fun BedtimeSettingsCard(viewModel: SleepViewModel, is24: Boolean) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val bed = settings.bedtime
    var typing by remember { mutableStateOf(false) }
    val firstDay = settings.firstDayOfWeek(java.time.temporal.WeekFields.of(currentLocale()).firstDayOfWeek)
    SectionTitle(stringResource(R.string.sleep_reminder_section))
    SettingsGroup {
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.sleep_reminder_title), subtitle = stringResource(R.string.sleep_reminder_hint),
                checked = bed.enabled, onCheckedChange = { on -> viewModel.updateBedtime { it.copy(enabled = on) } }, shape = shape,
            )
        }
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.sleep_low_battery_title), subtitle = stringResource(R.string.sleep_low_battery_hint),
                checked = settings.lowBatteryWarning, onCheckedChange = viewModel::setLowBatteryWarning, shape = shape,
            )
        }
        item { shape ->
            ValueRow(
                title = stringResource(R.string.sleep_bedtime), value = TimeText.format(bed.hour, bed.minute, is24),
                shape = shape, onClick = { typing = true },
            )
        }
        item { shape ->
            StepperRow(
                title = stringResource(R.string.sleep_lead), valueText = minutesLabel(bed.reminderLeadMinutes),
                onMinus = { viewModel.updateBedtime { it.copy(reminderLeadMinutes = (it.reminderLeadMinutes - 5).coerceAtLeast(5)) } },
                onPlus = { viewModel.updateBedtime { it.copy(reminderLeadMinutes = (it.reminderLeadMinutes + 5).coerceAtMost(120)) } },
                canMinus = bed.reminderLeadMinutes > 5, canPlus = bed.reminderLeadMinutes < 120, shape = shape,
            )
        }
        item { shape ->
            Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Formatters.orderedDays(firstDay).forEach { day ->
                        DayChip(
                            label = day.getDisplayName(TextStyle.NARROW, currentLocale()),
                            fullName = day.getDisplayName(TextStyle.FULL, currentLocale()),
                            selected = bed.daysMask and Alarm.run { day.bit } != 0,
                            modifier = Modifier.weight(1f),
                            onToggle = { on ->
                                viewModel.updateBedtime { b ->
                                    val bit = Alarm.run { day.bit }
                                    b.copy(daysMask = if (on) b.daysMask or bit else b.daysMask and bit.inv())
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    if (typing) {
        TimeEntryDialog(bed.hour, bed.minute, is24, onConfirm = { h, m -> viewModel.updateBedtime { it.copy(hour = h, minute = m) } }, onDismiss = { typing = false })
    }
}
