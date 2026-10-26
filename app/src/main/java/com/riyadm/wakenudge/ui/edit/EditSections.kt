package com.riyadm.wakenudge.ui.edit

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.VibrationPattern
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.engine.audio.AlarmVibrator
import com.riyadm.wakenudge.engine.audio.SoundPreviewer
import com.riyadm.wakenudge.ui.components.ChipChoiceRow
import com.riyadm.wakenudge.ui.components.SectionTitle
import com.riyadm.wakenudge.ui.components.SettingRow
import com.riyadm.wakenudge.ui.components.SettingsGroup
import com.riyadm.wakenudge.ui.components.SliderRow
import com.riyadm.wakenudge.ui.components.StepperRow
import com.riyadm.wakenudge.ui.components.SwitchRow
import com.riyadm.wakenudge.ui.components.ValueRow
import com.riyadm.wakenudge.ui.util.FadeInSteps
import com.riyadm.wakenudge.ui.util.fadeLabel
import com.riyadm.wakenudge.ui.util.minutesLabel
import kotlin.math.roundToInt

/** Alarm sound, volume and vibration: shared by the alarm editor and the "new alarm defaults" screen. */
@Composable
fun SoundSection(alarm: Alarm, enabled: Boolean, previewer: SoundPreviewer, onChange: ((Alarm) -> Alarm) -> Unit, showVoice: Boolean = true) {
    var showPicker by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.section_sound))
    SettingsGroup {
        item { shape ->
            ValueRow(
                title = stringResource(R.string.sound_title),
                value = rememberSoundTitle(alarm.sound),
                icon = Icons.Outlined.MusicNote,
                shape = shape,
                enabled = enabled,
                onClick = { showPicker = true },
            )
        }
        if (showVoice) item { shape ->
            val voice = alarm.voice
            SwitchRow(
                title = stringResource(R.string.voice_title),
                subtitle = stringResource(R.string.voice_subtitle),
                icon = Icons.Outlined.Campaign,
                checked = voice.enabled,
                onCheckedChange = { on -> onChange { it.copy(voice = it.voice.copy(enabled = on)) } },
                shape = shape,
                enabled = enabled,
            )
        }
        if (showVoice && alarm.voice.enabled) {
            item { shape ->
                SwitchRow(
                    title = stringResource(R.string.voice_announce_time),
                    checked = alarm.voice.announceTime,
                    onCheckedChange = { on -> onChange { it.copy(voice = it.voice.copy(announceTime = on)) } },
                    shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                SwitchRow(
                    title = stringResource(R.string.voice_announce_label),
                    checked = alarm.voice.announceLabel,
                    onCheckedChange = { on -> onChange { it.copy(voice = it.voice.copy(announceLabel = on)) } },
                    shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                SliderRow(
                    title = stringResource(R.string.voice_repeat),
                    valueText = stringResource(R.string.unit_seconds, alarm.voice.repeatSeconds),
                    value = alarm.voice.repeatSeconds.toFloat(),
                    onValueChange = { v -> onChange { it.copy(voice = it.voice.copy(repeatSeconds = (v / 5).roundToInt() * 5)) } },
                    valueRange = 15f..120f, steps = 20, shape = shape, enabled = enabled,
                )
            }
        }
    }
    if (showPicker) {
        SoundPickerSheet(
            sound = alarm.sound,
            onChange = { s -> onChange { it.copy(sound = s) } },
            onDismiss = { showPicker = false },
            previewer = previewer,
        )
    }
}

@Composable
fun VolumeSection(alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit) {
    val v = alarm.volume
    fun set(block: (VolumeSettings) -> VolumeSettings) = onChange { it.copy(volume = block(it.volume)) }
    SectionTitle(stringResource(R.string.section_volume))
    SettingsGroup {
        item { shape ->
            SliderRow(
                title = stringResource(R.string.volume_level),
                subtitle = stringResource(R.string.volume_level_hint),
                valueText = if (v.level == 0) stringResource(R.string.volume_system) else "${v.level}%",
                value = v.level.toFloat(),
                onValueChange = { x -> set { it.copy(level = if (x < 8f) 0 else (x / 5).roundToInt() * 5) } },
                valueRange = 0f..100f, steps = 19, shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            val index = FadeInSteps.indexOfFirst { it >= v.rampSeconds }.coerceAtLeast(0)
            SliderRow(
                title = stringResource(R.string.volume_fade_in),
                subtitle = stringResource(R.string.volume_fade_in_hint),
                valueText = fadeLabel(FadeInSteps[index]),
                value = index.toFloat(),
                onValueChange = { x -> set { it.copy(rampSeconds = FadeInSteps[x.roundToInt().coerceIn(0, FadeInSteps.lastIndex)]) } },
                valueRange = 0f..FadeInSteps.lastIndex.toFloat(), steps = FadeInSteps.size - 2, shape = shape, enabled = enabled,
            )
        }
        if (v.rampSeconds > 0) {
            item { shape ->
                SliderRow(
                    title = stringResource(R.string.volume_start),
                    valueText = "${v.rampStartPercent}%",
                    value = v.rampStartPercent.toFloat(),
                    onValueChange = { x -> set { it.copy(rampStartPercent = (x / 5).roundToInt() * 5) } },
                    valueRange = 5f..80f, steps = 14, shape = shape, enabled = enabled,
                )
            }
        }
        item { shape ->
            // One control for on/off and strength: all the way left is off, otherwise how much of the boost to add.
            val strength = v.extraLoudStrength
            SliderRow(
                title = stringResource(R.string.volume_extra_loud),
                subtitle = stringResource(R.string.volume_extra_loud_hint),
                valueText = if (strength == 0) stringResource(R.string.state_off) else "$strength%",
                value = strength.toFloat(),
                onValueChange = { x ->
                    val percent = (x / 5).roundToInt() * 5
                    set { if (percent <= 0) it.copy(extraLoud = false) else it.copy(extraLoud = true, extraLoudPercent = percent) }
                },
                valueRange = 0f..100f, steps = 19, shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.volume_use_max),
                subtitle = stringResource(R.string.volume_use_max_hint),
                checked = v.useMaxSystemVolume,
                onCheckedChange = { on -> set { it.copy(useMaxSystemVolume = on) } },
                shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.volume_escalate),
                subtitle = stringResource(R.string.volume_escalate_hint),
                icon = Icons.Outlined.GraphicEq,
                checked = v.escalateWhenIgnored,
                onCheckedChange = { on -> set { it.copy(escalateWhenIgnored = on) } },
                shape = shape, enabled = enabled,
            )
        }
    }
    Text(
        stringResource(R.string.volume_safety_note),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
fun VibrationSection(alarm: Alarm, enabled: Boolean, vibrator: AlarmVibrator, onChange: ((Alarm) -> Alarm) -> Unit) {
    val vib = alarm.vibration
    var recording by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.section_vibration))
    SettingsGroup {
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.vibration_title),
                subtitle = if (vibrator.isAvailable) null else stringResource(R.string.vibration_unavailable),
                icon = Icons.Outlined.Vibration,
                checked = vib.enabled,
                onCheckedChange = { on -> onChange { it.copy(vibration = it.vibration.copy(enabled = on)) } },
                shape = shape, enabled = enabled && vibrator.isAvailable,
            )
        }
        if (vib.enabled) {
            item { shape ->
                ChipChoiceRow(
                    title = stringResource(R.string.vibration_pattern),
                    options = VibrationPattern.entries.toList(),
                    selected = vib.pattern,
                    onSelect = { p ->
                        onChange { it.copy(vibration = it.vibration.copy(pattern = p)) }
                        vibrator.stop()
                        if (p != VibrationPattern.CUSTOM || vib.customTimings.isNotEmpty()) vibrator.preview(vib.copy(pattern = p))
                    },
                    optionLabel = { stringResource(vibrationLabel(it)) },
                    shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                SliderRow(
                    title = stringResource(R.string.vibration_strength),
                    subtitle = if (vibrator.hasAmplitudeControl) null else stringResource(R.string.vibration_no_amplitude),
                    valueText = "${vib.strength}%",
                    value = vib.strength.toFloat(),
                    onValueChange = { x -> onChange { it.copy(vibration = it.vibration.copy(strength = (x / 10).roundToInt() * 10)) } },
                    onValueChangeFinished = { vibrator.stop(); vibrator.preview(vib) },
                    valueRange = 10f..100f, steps = 8, shape = shape, enabled = enabled && vibrator.hasAmplitudeControl,
                )
            }
            if (vib.pattern == VibrationPattern.CUSTOM) {
                item { shape ->
                    SettingRow(
                        title = stringResource(R.string.vibration_custom),
                        subtitle = if (vib.customTimings.isEmpty()) stringResource(R.string.vibration_custom_empty)
                        else (vib.customTimings.size / 2).let { pluralStringResource(R.plurals.vibration_custom_set, it, it) },
                        shape = shape, enabled = enabled, onClick = { recording = true },
                    )
                }
            }
            item { shape ->
                SettingRow(
                    title = stringResource(R.string.vibration_try),
                    shape = shape, enabled = true,
                    onClick = { vibrator.stop(); vibrator.preview(vib) },
                )
            }
        }
    }
    if (recording) {
        RhythmRecorderDialog(
            initial = emptyList(),
            onSave = { timings -> onChange { it.copy(vibration = it.vibration.copy(customTimings = timings, pattern = VibrationPattern.CUSTOM)) } },
            onDismiss = { recording = false },
        )
    }
}

internal fun vibrationLabel(p: VibrationPattern) = when (p) {
    VibrationPattern.CLASSIC -> R.string.vib_classic
    VibrationPattern.PULSE -> R.string.vib_pulse
    VibrationPattern.HEARTBEAT -> R.string.vib_heartbeat
    VibrationPattern.SOS -> R.string.vib_sos
    VibrationPattern.WAVE -> R.string.vib_wave
    VibrationPattern.STRONG_CONTINUOUS -> R.string.vib_strong
    VibrationPattern.ESCALATING -> R.string.vib_escalating
    VibrationPattern.CUSTOM -> R.string.vib_custom
}

@Composable
fun SnoozeSection(alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit, snoozeMissions: @Composable () -> Unit, showTitle: Boolean = true) {
    val s = alarm.snooze
    if (showTitle) SectionTitle(stringResource(R.string.section_snooze))
    SettingsGroup {
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.snooze_title),
                icon = Icons.Outlined.Alarm,
                checked = s.enabled,
                onCheckedChange = { on -> onChange { it.copy(snooze = it.snooze.copy(enabled = on)) } },
                shape = shape, enabled = enabled,
            )
        }
        if (s.enabled) {
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.snooze_length),
                    valueText = minutesLabel(s.minutes),
                    onMinus = { onChange { it.copy(snooze = it.snooze.copy(minutes = (s.minutes - 1).coerceAtLeast(1))) } },
                    onPlus = { onChange { it.copy(snooze = it.snooze.copy(minutes = (s.minutes + 1).coerceAtMost(60))) } },
                    canMinus = s.minutes > 1, canPlus = s.minutes < 60,
                    shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.snooze_limit),
                    subtitle = stringResource(R.string.snooze_limit_hint),
                    valueText = if (s.maxCount == 0) stringResource(R.string.snooze_unlimited) else s.maxCount.toString(),
                    onMinus = { onChange { it.copy(snooze = it.snooze.copy(maxCount = (s.maxCount - 1).coerceAtLeast(0))) } },
                    onPlus = { onChange { it.copy(snooze = it.snooze.copy(maxCount = (s.maxCount + 1).coerceAtMost(10))) } },
                    canMinus = s.maxCount > 0, canPlus = s.maxCount < 10,
                    shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                SwitchRow(
                    title = stringResource(R.string.snooze_decreasing),
                    subtitle = stringResource(R.string.snooze_decreasing_hint),
                    checked = s.decreasing,
                    onCheckedChange = { on -> onChange { it.copy(snooze = it.snooze.copy(decreasing = on)) } },
                    shape = shape, enabled = enabled,
                )
            }
            if (s.decreasing) {
                item { shape ->
                    StepperRow(
                        title = stringResource(R.string.snooze_decrease_step),
                        valueText = minutesLabel(s.decreaseStepMinutes),
                        onMinus = { onChange { it.copy(snooze = it.snooze.copy(decreaseStepMinutes = (s.decreaseStepMinutes - 1).coerceAtLeast(1))) } },
                        onPlus = { onChange { it.copy(snooze = it.snooze.copy(decreaseStepMinutes = (s.decreaseStepMinutes + 1).coerceAtMost(10))) } },
                        canMinus = s.decreaseStepMinutes > 1, canPlus = s.decreaseStepMinutes < 10,
                        shape = shape, enabled = enabled,
                    )
                }
            }
        }
    }
    if (s.enabled) {
        Column(Modifier.padding(top = 4.dp)) { snoozeMissions() }
    }
}

@Composable
fun WakeCheckSection(alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit) {
    val w = alarm.wakeCheck
    fun set(block: (com.riyadm.wakenudge.domain.model.WakeCheckSettings) -> com.riyadm.wakenudge.domain.model.WakeCheckSettings) =
        onChange { it.copy(wakeCheck = block(it.wakeCheck)) }
    SectionTitle(stringResource(R.string.section_wake_check))
    SettingsGroup {
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.wake_check_title),
                subtitle = stringResource(R.string.wake_check_hint),
                icon = Icons.Outlined.NotificationsActive,
                checked = w.enabled,
                onCheckedChange = { on -> set { it.copy(enabled = on) } },
                shape = shape, enabled = enabled,
            )
        }
        if (w.enabled) {
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.wake_check_delay),
                    valueText = minutesLabel(w.delayMinutes),
                    onMinus = { set { it.copy(delayMinutes = (w.delayMinutes - 1).coerceAtLeast(1)) } },
                    onPlus = { set { it.copy(delayMinutes = (w.delayMinutes + 1).coerceAtMost(30)) } },
                    canMinus = w.delayMinutes > 1, canPlus = w.delayMinutes < 30, shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                SliderRow(
                    title = stringResource(R.string.wake_check_response),
                    subtitle = stringResource(R.string.wake_check_response_hint),
                    valueText = stringResource(R.string.unit_seconds, w.responseSeconds),
                    value = w.responseSeconds.toFloat(),
                    onValueChange = { x -> set { it.copy(responseSeconds = (x / 15).roundToInt() * 15) } },
                    valueRange = 15f..300f, steps = 18, shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.wake_check_count),
                    subtitle = stringResource(R.string.wake_check_count_hint),
                    valueText = w.maxChecks.toString(),
                    onMinus = { set { it.copy(maxChecks = (w.maxChecks - 1).coerceAtLeast(1)) } },
                    onPlus = { set { it.copy(maxChecks = (w.maxChecks + 1).coerceAtMost(5)) } },
                    canMinus = w.maxChecks > 1, canPlus = w.maxChecks < 5, shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.wake_check_rerings),
                    subtitle = stringResource(R.string.wake_check_rerings_hint),
                    valueText = w.maxReRings.toString(),
                    onMinus = { set { it.copy(maxReRings = (w.maxReRings - 1).coerceAtLeast(1)) } },
                    onPlus = { set { it.copy(maxReRings = (w.maxReRings + 1).coerceAtMost(5)) } },
                    canMinus = w.maxReRings > 1, canPlus = w.maxReRings < 5, shape = shape, enabled = enabled,
                )
            }
        }
    }
}

@Composable
fun AntiOversleepSection(alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit, showTitle: Boolean = true) {
    val a = alarm.antiOversleep
    fun set(block: (com.riyadm.wakenudge.domain.model.AntiOversleepSettings) -> com.riyadm.wakenudge.domain.model.AntiOversleepSettings) =
        onChange { it.copy(antiOversleep = block(it.antiOversleep)) }
    var confirmEmergencyOff by remember { mutableStateOf(false) }
    if (showTitle) SectionTitle(stringResource(R.string.section_anti_oversleep))
    SettingsGroup {
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.ao_emergency_stop),
                subtitle = stringResource(if (a.emergencyStopEnabled) R.string.ao_emergency_stop_on else R.string.ao_emergency_stop_off),
                icon = Icons.Outlined.Shield,
                checked = a.emergencyStopEnabled,
                // Switching it ON is always safe and immediate; switching it OFF makes the alarm strict, so ask first.
                onCheckedChange = { on -> if (on) set { it.copy(emergencyStopEnabled = true) } else confirmEmergencyOff = true },
                shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            StepperRow(
                title = stringResource(R.string.ao_hold),
                subtitle = stringResource(R.string.ao_hold_hint),
                valueText = if (a.holdToDismissSeconds == 0) stringResource(R.string.state_off) else stringResource(R.string.unit_seconds, a.holdToDismissSeconds),
                onMinus = { set { it.copy(holdToDismissSeconds = (a.holdToDismissSeconds - 1).coerceAtLeast(0)) } },
                onPlus = { set { it.copy(holdToDismissSeconds = (a.holdToDismissSeconds + 1).coerceAtMost(10)) } },
                canMinus = a.holdToDismissSeconds > 0, canPlus = a.holdToDismissSeconds < 10, shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.ao_backup),
                subtitle = stringResource(R.string.ao_backup_hint),
                checked = a.backupEnabled,
                onCheckedChange = { on -> set { it.copy(backupEnabled = on) } },
                shape = shape, enabled = enabled,
            )
        }
        if (a.backupEnabled) {
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.ao_backup_delay),
                    valueText = minutesLabel(a.backupDelayMinutes),
                    onMinus = { set { it.copy(backupDelayMinutes = (a.backupDelayMinutes - 1).coerceAtLeast(1)) } },
                    onPlus = { set { it.copy(backupDelayMinutes = (a.backupDelayMinutes + 1).coerceAtMost(30)) } },
                    canMinus = a.backupDelayMinutes > 1, canPlus = a.backupDelayMinutes < 30, shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.ao_backup_count),
                    valueText = a.backupCount.toString(),
                    onMinus = { set { it.copy(backupCount = (a.backupCount - 1).coerceAtLeast(1)) } },
                    onPlus = { set { it.copy(backupCount = (a.backupCount + 1).coerceAtMost(3)) } },
                    canMinus = a.backupCount > 1, canPlus = a.backupCount < 3, shape = shape, enabled = enabled,
                )
            }
        }
        item { shape ->
            StepperRow(
                title = stringResource(R.string.ao_timeout),
                subtitle = stringResource(R.string.ao_timeout_hint),
                valueText = minutesLabel(a.ringTimeoutMinutes),
                onMinus = { set { it.copy(ringTimeoutMinutes = (a.ringTimeoutMinutes - 1).coerceAtLeast(1)) } },
                onPlus = { set { it.copy(ringTimeoutMinutes = (a.ringTimeoutMinutes + 1).coerceAtMost(30)) } },
                canMinus = a.ringTimeoutMinutes > 1, canPlus = a.ringTimeoutMinutes < 30, shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            val steps = listOf(0, 5, 10, 15, 30, 45, 60)
            ChipChoiceRow(
                title = stringResource(R.string.ao_edit_lock),
                subtitle = stringResource(R.string.ao_edit_lock_hint),
                options = steps,
                selected = steps.minByOrNull { kotlin.math.abs(it - a.editLockMinutes) } ?: 0,
                onSelect = { m -> set { it.copy(editLockMinutes = m) } },
                optionLabel = { if (it == 0) stringResource(R.string.state_off) else minutesLabel(it) },
                shape = shape, enabled = enabled,
            )
        }
    }
    Text(
        stringResource(if (a.emergencyStopEnabled) R.string.ao_emergency_note else R.string.ao_emergency_note_off),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    if (confirmEmergencyOff) {
        com.riyadm.wakenudge.ui.components.ConfirmDialog(
            title = stringResource(R.string.ao_emergency_off_title),
            text = stringResource(R.string.ao_emergency_off_body),
            confirmLabel = stringResource(R.string.ao_emergency_off_confirm),
            onConfirm = { set { it.copy(emergencyStopEnabled = false) }; confirmEmergencyOff = false },
            onDismiss = { confirmEmergencyOff = false },
            destructive = true,
        )
    }
}

@Composable
fun SunriseSection(alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit) {
    val s = alarm.sunrise
    SectionTitle(stringResource(R.string.section_sunrise))
    SettingsGroup {
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.sunrise_title),
                subtitle = stringResource(R.string.sunrise_hint),
                icon = Icons.Outlined.WbTwilight,
                checked = s.enabled,
                onCheckedChange = { on -> onChange { it.copy(sunrise = it.sunrise.copy(enabled = on)) } },
                shape = shape, enabled = enabled,
            )
        }
        if (s.enabled) {
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.sunrise_lead),
                    valueText = minutesLabel(s.leadMinutes),
                    onMinus = { onChange { it.copy(sunrise = it.sunrise.copy(leadMinutes = (s.leadMinutes - 1).coerceAtLeast(1))) } },
                    onPlus = { onChange { it.copy(sunrise = it.sunrise.copy(leadMinutes = (s.leadMinutes + 1).coerceAtMost(30))) } },
                    canMinus = s.leadMinutes > 1, canPlus = s.leadMinutes < 30, shape = shape, enabled = enabled,
                )
            }
        }
    }
}

@Composable
fun ExtrasSection(alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit, showTitle: Boolean = true) {
    if (showTitle) SectionTitle(stringResource(R.string.section_extras))
    SettingsGroup {
        if (alarm.isRepeating) {
            item { shape ->
                SwitchRow(
                    title = stringResource(R.string.extras_respect_exceptions),
                    subtitle = stringResource(R.string.extras_respect_exceptions_hint),
                    checked = alarm.respectExceptions,
                    onCheckedChange = { on -> onChange { it.copy(respectExceptions = on) } },
                    shape = shape, enabled = enabled,
                )
            }
        }
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.extras_ignore_vacation),
                subtitle = stringResource(R.string.extras_ignore_vacation_hint),
                checked = alarm.ignoreVacation,
                onCheckedChange = { on -> onChange { it.copy(ignoreVacation = on) } },
                shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            val options = listOf(0, 15, 30, 60, 120)
            ChipChoiceRow(
                title = stringResource(R.string.extras_upcoming),
                subtitle = stringResource(R.string.extras_upcoming_hint),
                options = options,
                selected = options.minByOrNull { kotlin.math.abs(it - alarm.upcomingNoticeMinutes) } ?: 0,
                onSelect = { m -> onChange { it.copy(upcomingNoticeMinutes = m) } },
                optionLabel = { if (it == 0) stringResource(R.string.state_off) else minutesLabel(it) },
                shape = shape, enabled = enabled,
            )
        }
        if (!alarm.isRepeating) {
            item { shape ->
                SwitchRow(
                    title = stringResource(R.string.extras_delete_after),
                    subtitle = stringResource(R.string.extras_delete_after_hint),
                    checked = alarm.deleteAfterUse,
                    onCheckedChange = { on -> onChange { it.copy(deleteAfterUse = on) } },
                    shape = shape, enabled = enabled,
                )
            }
        }
    }
}
