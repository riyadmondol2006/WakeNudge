package com.riyadm.wakenudge.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import kotlin.math.roundToInt

/** A settings sub-screen: back arrow, title, and a list of grouped rows. */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    intro: String? = null,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (intro != null) {
                item { Text(intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) }
            }
            content()
        }
    }
}

/** Theme, colours, clock format and haptics: how the app looks and reads, nothing about how alarms behave. */
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.settings_appearance_page), onBack, modifier) {
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
                    SwitchRow(
                        title = stringResource(R.string.settings_haptics), subtitle = stringResource(R.string.settings_haptics_hint),
                        checked = s.haptics, onCheckedChange = { on -> viewModel.update { it.copy(haptics = on) } }, shape = shape,
                    )
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.settings_format))
            SettingsGroup {
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
            }
        }
    }
}

/** What the phone does while an alarm is on screen: buttons, screen and the way out. */
@Composable
fun RingingSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.settings_ringing), onBack, modifier, intro = stringResource(R.string.settings_ringing_intro)) {
        item {
            SectionTitle(stringResource(R.string.settings_ringing_controls))
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
                    SwitchRow(
                        title = stringResource(R.string.settings_keep_front), subtitle = stringResource(R.string.settings_keep_front_hint),
                        checked = s.keepRingScreenFront, onCheckedChange = { on -> viewModel.update { it.copy(keepRingScreenFront = on) } }, shape = shape,
                    )
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.settings_ringing_safety))
            SettingsGroup {
                item { shape ->
                    StepperRow(
                        title = stringResource(R.string.settings_emergency_hold), subtitle = stringResource(R.string.settings_emergency_hold_hint),
                        valueText = stringResource(R.string.unit_seconds, s.emergencyStopHoldSeconds),
                        onMinus = { viewModel.update { it.copy(emergencyStopHoldSeconds = (it.emergencyStopHoldSeconds - 1).coerceAtLeast(1)) } },
                        onPlus = { viewModel.update { it.copy(emergencyStopHoldSeconds = (it.emergencyStopHoldSeconds + 1).coerceAtMost(10)) } },
                        canMinus = s.emergencyStopHoldSeconds > 1, canPlus = s.emergencyStopHoldSeconds < 10, shape = shape,
                    )
                }
            }
        }
    }
}

/** Where the alarm is played and how loud it may be when it would play into someone's ears. */
@Composable
fun AudioSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.settings_audio_page), onBack, modifier) {
        item {
            SectionTitle(stringResource(R.string.settings_route))
            SettingsGroup {
                item { shape ->
                    ChipChoiceRow(
                        title = stringResource(R.string.settings_route), subtitle = stringResource(R.string.settings_route_hint),
                        options = AudioRoute.entries.toList(), selected = s.audioRoute,
                        onSelect = { r -> viewModel.update { it.copy(audioRoute = r) } },
                        optionLabel = { stringResource(if (it == AudioRoute.PHONE_SPEAKER) R.string.route_speaker else R.string.route_system) }, shape = shape,
                    )
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.settings_hearing))
            SettingsGroup {
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
            }
        }
    }
}

/** Countdown timers use their own sound so a finished timer is never mistaken for a wake-up alarm. */
@Composable
fun TimerSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    var pickSound by remember { mutableStateOf(false) }
    SettingsPage(stringResource(R.string.settings_timers), onBack, modifier) {
        item {
            SectionTitle(stringResource(R.string.settings_timers_when_done))
            SettingsGroup {
                item { shape ->
                    ValueRow(title = stringResource(R.string.settings_timer_sound), value = rememberSoundTitle(s.timerSound), shape = shape, onClick = { pickSound = true })
                }
                item { shape ->
                    SwitchRow(
                        title = stringResource(R.string.settings_timer_vibrate), checked = s.timerVibrate,
                        onCheckedChange = { on -> viewModel.update { it.copy(timerVibrate = on) } }, shape = shape,
                    )
                }
            }
        }
    }
    if (pickSound) {
        SoundPickerSheet(
            sound = s.timerSound, onChange = { snd -> viewModel.update { it.copy(timerSound = snd) } },
            onDismiss = { pickSound = false }, previewer = viewModel.soundPreviewer,
        )
    }
}

internal fun themeLabel(m: ThemeMode) = when (m) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.AMOLED -> R.string.theme_amoled
}

internal fun timeFormatLabel(f: TimeFormat) = when (f) {
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
