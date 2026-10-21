package com.riyadm.wakenudge.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.AudioRoute
import com.riyadm.wakenudge.domain.model.ThemeMode
import com.riyadm.wakenudge.domain.model.TimeFormat
import com.riyadm.wakenudge.domain.model.VolumeKeyAction
import com.riyadm.wakenudge.domain.model.WeekStart
import com.riyadm.wakenudge.ui.components.ChipChoiceRow
import com.riyadm.wakenudge.ui.components.SectionTitle
import com.riyadm.wakenudge.ui.components.SettingsGroup
import com.riyadm.wakenudge.ui.components.SliderRow
import com.riyadm.wakenudge.ui.components.StepperRow
import com.riyadm.wakenudge.ui.components.SwitchRow
import com.riyadm.wakenudge.ui.components.ValueRow
import com.riyadm.wakenudge.ui.edit.SoundPickerSheet
import com.riyadm.wakenudge.ui.edit.rememberSoundTitle
import com.riyadm.wakenudge.ui.util.Formatters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

class SettingsNav(
    val back: () -> Unit,
    val defaults: () -> Unit,
    val groups: () -> Unit,
    val exceptions: () -> Unit,
    val sleep: () -> Unit,
    val health: () -> Unit,
    val backup: () -> Unit,
    val about: () -> Unit,
)

@Composable
fun SettingsScreen(nav: SettingsNav, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pickVacationDate by remember { mutableStateOf(false) }
    var pickTimerSound by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                SectionTitle(stringResource(R.string.settings_appearance))
                SettingsGroup {
                    item { shape ->
                        ChipChoiceRow(
                            title = stringResource(R.string.settings_theme), options = ThemeMode.entries.toList(), selected = s.themeMode,
                            onSelect = { m -> viewModel.update { it.copy(themeMode = m) } },
                            optionLabel = { stringResource(themeLabel(it)) }, shape = shape,
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        item { shape ->
                            SwitchRow(
                                title = stringResource(R.string.settings_dynamic), subtitle = stringResource(R.string.settings_dynamic_hint),
                                checked = s.dynamicColor, onCheckedChange = { on -> viewModel.update { it.copy(dynamicColor = on) } }, shape = shape,
                            )
                        }
                    }
                    item { shape ->
                        ChipChoiceRow(
                            title = stringResource(R.string.settings_time_format), options = TimeFormat.entries.toList(), selected = s.timeFormat,
                            onSelect = { f -> viewModel.update { it.copy(timeFormat = f) } },
                            optionLabel = { stringResource(timeFormatLabel(it)) }, shape = shape,
                        )
                    }
                    item { shape ->
                        ChipChoiceRow(
                            title = stringResource(R.string.settings_week_start), options = WeekStart.entries.toList(), selected = s.weekStart,
                            onSelect = { w -> viewModel.update { it.copy(weekStart = w) } },
                            optionLabel = { stringResource(weekStartLabel(it)) }, shape = shape,
                        )
                    }
                    item { shape ->
                        SwitchRow(
                            title = stringResource(R.string.settings_haptics), subtitle = stringResource(R.string.settings_haptics_hint),
                            checked = s.haptics, onCheckedChange = { on -> viewModel.update { it.copy(haptics = on) } }, shape = shape,
                        )
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.settings_ringing))
                SettingsGroup {
                    item { shape ->
                        ChipChoiceRow(
                            title = stringResource(R.string.settings_volume_keys), subtitle = stringResource(R.string.settings_volume_keys_hint),
                            options = VolumeKeyAction.entries.toList(), selected = s.volumeKeyAction,
                            onSelect = { a -> viewModel.update { it.copy(volumeKeyAction = a) } },
                            optionLabel = { stringResource(volumeKeyLabel(it)) }, shape = shape,
                        )
                    }
                    item { shape ->
                        ChipChoiceRow(
                            title = stringResource(R.string.settings_route), subtitle = stringResource(R.string.settings_route_hint),
                            options = AudioRoute.entries.toList(), selected = s.audioRoute,
                            onSelect = { r -> viewModel.update { it.copy(audioRoute = r) } },
                            optionLabel = { stringResource(if (it == AudioRoute.PHONE_SPEAKER) R.string.route_speaker else R.string.route_system) }, shape = shape,
                        )
                    }
                    item { shape ->
                        SwitchRow(
                            title = stringResource(R.string.settings_hearing_cap), subtitle = stringResource(R.string.settings_hearing_cap_hint),
                            checked = s.hearingSafetyCap, onCheckedChange = { on -> viewModel.update { it.copy(hearingSafetyCap = on) } }, shape = shape,
                        )
                    }
                    if (s.hearingSafetyCap) {
                        item { shape ->
                            SliderRow(
                                title = stringResource(R.string.settings_hearing_cap_level), valueText = "${s.headphoneCapPercent}%",
                                value = s.headphoneCapPercent.toFloat(), onValueChange = { v -> viewModel.update { it.copy(headphoneCapPercent = (v / 5).roundToInt() * 5) } },
                                valueRange = 20f..100f, steps = 15, shape = shape,
                            )
                        }
                    }
                    item { shape ->
                        SwitchRow(
                            title = stringResource(R.string.settings_keep_front), subtitle = stringResource(R.string.settings_keep_front_hint),
                            checked = s.keepRingScreenFront, onCheckedChange = { on -> viewModel.update { it.copy(keepRingScreenFront = on) } }, shape = shape,
                        )
                    }
                    item { shape ->
                        StepperRow(
                            title = stringResource(R.string.settings_emergency_hold), subtitle = stringResource(R.string.settings_emergency_hold_hint),
                            valueText = stringResource(R.string.unit_seconds, s.emergencyStopHoldSeconds),
                            onMinus = { viewModel.update { it.copy(emergencyStopHoldSeconds = (it.emergencyStopHoldSeconds - 1).coerceAtLeast(1)) } },
                            onPlus = { viewModel.update { it.copy(emergencyStopHoldSeconds = (it.emergencyStopHoldSeconds + 1).coerceAtMost(10)) } },
                            canMinus = s.emergencyStopHoldSeconds > 1, canPlus = s.emergencyStopHoldSeconds < 10, shape = shape,
                        )
                    }
                    item { shape ->
                        ValueRow(
                            title = stringResource(R.string.settings_defaults), subtitle = stringResource(R.string.settings_defaults_hint), value = "",
                            icon = Icons.Outlined.Tune, shape = shape, onClick = nav.defaults,
                        )
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.settings_schedule))
                SettingsGroup {
                    item { shape ->
                        SwitchRow(
                            title = stringResource(R.string.settings_vacation), subtitle = stringResource(R.string.settings_vacation_hint),
                            icon = Icons.Outlined.BeachAccess, checked = s.vacation.enabled,
                            onCheckedChange = { on -> viewModel.setVacation(on, if (on) s.vacation.resumeEpochDay else null) }, shape = shape,
                        )
                    }
                    if (s.vacation.enabled) {
                        item { shape ->
                            ValueRow(
                                title = stringResource(R.string.settings_vacation_resume),
                                value = s.vacation.resumeEpochDay?.let { Formatters.dateShort(LocalDate.ofEpochDay(it)) } ?: stringResource(R.string.settings_vacation_manual),
                                shape = shape, onClick = { pickVacationDate = true },
                            )
                        }
                    }
                    item { shape ->
                        ValueRow(title = stringResource(R.string.settings_exceptions), subtitle = stringResource(R.string.settings_exceptions_hint), value = "", icon = Icons.Outlined.Event, shape = shape, onClick = nav.exceptions)
                    }
                    item { shape ->
                        ValueRow(title = stringResource(R.string.settings_groups), subtitle = stringResource(R.string.settings_groups_hint), value = "", icon = Icons.Outlined.FolderSpecial, shape = shape, onClick = nav.groups)
                    }
                    item { shape ->
                        ValueRow(title = stringResource(R.string.settings_sleep), subtitle = stringResource(R.string.settings_sleep_hint), value = "", icon = Icons.Outlined.Bedtime, shape = shape, onClick = nav.sleep)
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.settings_timers))
                SettingsGroup {
                    item { shape ->
                        ValueRow(title = stringResource(R.string.settings_timer_sound), value = rememberSoundTitle(s.timerSound), shape = shape, onClick = { pickTimerSound = true })
                    }
                    item { shape ->
                        SwitchRow(
                            title = stringResource(R.string.settings_timer_vibrate), checked = s.timerVibrate,
                            onCheckedChange = { on -> viewModel.update { it.copy(timerVibrate = on) } }, shape = shape,
                        )
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.settings_data))
                SettingsGroup {
                    item { shape -> ValueRow(title = stringResource(R.string.settings_health), subtitle = stringResource(R.string.settings_health_hint), value = "", icon = Icons.Outlined.MonitorHeart, shape = shape, onClick = nav.health) }
                    item { shape -> ValueRow(title = stringResource(R.string.settings_backup), subtitle = stringResource(R.string.settings_backup_hint), value = "", icon = Icons.Outlined.Backup, shape = shape, onClick = nav.backup) }
                    item { shape -> ValueRow(title = stringResource(R.string.settings_about), value = "", icon = Icons.Outlined.Info, shape = shape, onClick = nav.about) }
                }
            }
        }
    }

    if (pickVacationDate) {
        val initial = (s.vacation.resumeEpochDay?.let(LocalDate::ofEpochDay) ?: LocalDate.now().plusDays(7)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickVacationDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { viewModel.setVacation(true, Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()) }
                    pickVacationDate = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setVacation(true, null); pickVacationDate = false }) { Text(stringResource(R.string.settings_vacation_manual)) }
            },
        ) { DatePicker(state) }
    }
    if (pickTimerSound) {
        SoundPickerSheet(
            sound = s.timerSound, onChange = { snd -> viewModel.update { it.copy(timerSound = snd) } },
            onDismiss = { pickTimerSound = false }, previewer = viewModel.soundPreviewer,
        )
    }
}

private fun themeLabel(m: ThemeMode) = when (m) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.AMOLED -> R.string.theme_amoled
}

private fun timeFormatLabel(f: TimeFormat) = when (f) {
    TimeFormat.SYSTEM -> R.string.time_format_system
    TimeFormat.H12 -> R.string.time_format_12
    TimeFormat.H24 -> R.string.time_format_24
}

private fun weekStartLabel(w: WeekStart) = when (w) {
    WeekStart.SYSTEM -> R.string.week_system
    WeekStart.MONDAY -> R.string.week_monday
    WeekStart.SATURDAY -> R.string.week_saturday
    WeekStart.SUNDAY -> R.string.week_sunday
}

private fun volumeKeyLabel(a: VolumeKeyAction) = when (a) {
    VolumeKeyAction.VOLUME -> R.string.vk_volume
    VolumeKeyAction.SNOOZE -> R.string.vk_snooze
    VolumeKeyAction.DISMISS -> R.string.vk_dismiss
    VolumeKeyAction.SILENCE_TEMPORARILY -> R.string.vk_silence
    VolumeKeyAction.NOTHING -> R.string.vk_nothing
}
