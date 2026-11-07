package com.riyadm.wakenudge.ui.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.VibrationPattern
import com.riyadm.wakenudge.domain.model.VolumeKeyAction
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.domain.model.WakeCheckSettings
import com.riyadm.wakenudge.engine.audio.AlarmVibrator
import com.riyadm.wakenudge.engine.audio.SoundPreviewer
import com.riyadm.wakenudge.ui.components.ChipChoiceRow
import com.riyadm.wakenudge.ui.components.ConfirmDialog
import com.riyadm.wakenudge.ui.components.NavRow
import com.riyadm.wakenudge.ui.components.SectionTitle
import com.riyadm.wakenudge.ui.components.SettingRow
import com.riyadm.wakenudge.ui.components.SettingsGroup
import com.riyadm.wakenudge.ui.components.SliderRow
import com.riyadm.wakenudge.ui.components.StepperRow
import com.riyadm.wakenudge.ui.components.SwitchRow
import com.riyadm.wakenudge.ui.components.ValueRow
import com.riyadm.wakenudge.ui.util.FadeInSteps
import com.riyadm.wakenudge.ui.util.LockText
import com.riyadm.wakenudge.ui.util.fadeLabel
import com.riyadm.wakenudge.ui.util.minutesLabel
import kotlin.math.roundToInt

/** The alarm's sound, as one row that opens the picker: the main editor and the new-alarm defaults both show it. */
@Composable
fun SoundRow(alarm: Alarm, enabled: Boolean, previewer: SoundPreviewer, onChange: ((Alarm) -> Alarm) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    SettingsGroup(Modifier.padding(top = 8.dp)) {
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

/**
 * How loud, and whether it may be turned down. "Use the phone's alarm volume" is its own switch: it used to be the
 * slider's lowest position, so dragging an alarm quiet could land on it and ring at the phone's (loud) level instead.
 */
@Composable
fun VolumeSection(alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit) {
    val v = alarm.volume
    val a = alarm.antiOversleep
    fun set(block: (VolumeSettings) -> VolumeSettings) = onChange { it.copy(volume = block(it.volume)) }
    fun setAo(block: (AntiOversleepSettings) -> AntiOversleepSettings) = onChange { it.copy(antiOversleep = block(it.antiOversleep)) }
    SettingsGroup {
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.opt_phone_volume),
                subtitle = stringResource(R.string.opt_phone_volume_hint),
                checked = v.level == 0,
                onCheckedChange = { on -> set { it.copy(level = if (on) 0 else DEFAULT_LEVEL) } },
                shape = shape, enabled = enabled,
            )
        }
        if (v.level != 0) {
            item { shape ->
                SliderRow(
                    title = stringResource(R.string.opt_how_loud),
                    subtitle = stringResource(R.string.opt_how_loud_hint),
                    valueText = "${v.level}%",
                    value = v.level.coerceIn(MIN_LEVEL, 100).toFloat(),
                    // Never 0 from here: that is the switch above, and a drag must not reach it by accident.
                    onValueChange = { x -> set { it.copy(level = ((x / 5).roundToInt() * 5).coerceIn(MIN_LEVEL, 100)) } },
                    valueRange = MIN_LEVEL.toFloat()..100f, steps = (100 - MIN_LEVEL) / 5 - 1, shape = shape, enabled = enabled,
                )
            }
        }
        item { shape ->
            val index = FadeInSteps.indexOfFirst { it >= v.rampSeconds }.coerceAtLeast(0)
            SliderRow(
                title = stringResource(R.string.opt_fade_in),
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
            SwitchRow(
                title = stringResource(R.string.opt_louder),
                subtitle = stringResource(R.string.opt_louder_hint),
                icon = Icons.Outlined.GraphicEq,
                checked = v.escalateWhenIgnored,
                onCheckedChange = { on -> set { it.copy(escalateWhenIgnored = on) } },
                shape = shape, enabled = enabled,
            )
        }
        if (v.escalateWhenIgnored) {
            item { shape ->
                SwitchRow(
                    title = stringResource(R.string.opt_calm_progress),
                    subtitle = stringResource(R.string.opt_calm_progress_hint),
                    checked = a.calmOnlyOnProgress,
                    onCheckedChange = { on -> setAo { it.copy(calmOnlyOnProgress = on) } },
                    shape = shape, enabled = enabled,
                )
            }
        }
        item { shape ->
            // Chips, not a slider: off or one of a few clear steps, nothing to drag near the screen edge.
            ChipChoiceRow(
                title = stringResource(R.string.volume_extra_loud),
                subtitle = stringResource(R.string.volume_extra_loud_hint),
                options = withCurrent(listOf(0, 25, 50, 75, 100), v.extraLoudStrength),
                selected = v.extraLoudStrength,
                onSelect = { p -> set { if (p == 0) it.copy(extraLoud = false) else it.copy(extraLoud = true, extraLoudPercent = p) } },
                optionLabel = { if (it == 0) stringResource(R.string.state_off) else "$it%" },
                shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            val steps = listOf(0, 25, 50, 75, 100)
            ChipChoiceRow(
                title = stringResource(R.string.opt_floor),
                subtitle = stringResource(R.string.opt_floor_hint),
                options = withCurrent(steps, a.volumeFloorPercent),
                selected = a.volumeFloorPercent,
                onSelect = { p -> setAo { it.copy(volumeFloorPercent = p) } },
                optionLabel = { if (it == 0) stringResource(R.string.state_off) else "$it%" },
                shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.opt_loudest),
                subtitle = stringResource(R.string.volume_use_max_hint),
                checked = v.useMaxSystemVolume,
                onCheckedChange = { on -> set { it.copy(useMaxSystemVolume = on) } },
                shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.opt_speaker),
                subtitle = stringResource(R.string.opt_speaker_hint),
                checked = a.forceSpeaker,
                onCheckedChange = { on -> setAo { it.copy(forceSpeaker = on) } },
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

/**
 * A chip row's steps with the alarm's own value among them, so an alarm set to a value between the steps (from an older
 * version, or a restored file) shows what it really has and is not changed by being looked at.
 */
private fun withCurrent(steps: List<Int>, current: Int): List<Int> = (steps + current).distinct().sorted()

private const val DEFAULT_LEVEL = 80
private const val MIN_LEVEL = 10

/**
 * [lockedOn]: the level keeps vibration on (Normal and harder), so the switch shows that instead of offering to turn it
 * off; patterns and strength are still the user's.
 */
@Composable
fun VibrationSection(alarm: Alarm, enabled: Boolean, vibrator: AlarmVibrator, onChange: ((Alarm) -> Alarm) -> Unit, lockedOn: Boolean = false) {
    val vib = alarm.vibration
    var recording by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.section_vibration))
    SettingsGroup {
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.vibration_title),
                subtitle = when {
                    !vibrator.isAvailable -> stringResource(R.string.vibration_unavailable)
                    lockedOn -> stringResource(R.string.defaults_vibration_owned)
                    else -> null
                },
                icon = Icons.Outlined.Vibration,
                checked = vib.enabled || lockedOn,
                onCheckedChange = { on -> onChange { it.copy(vibration = it.vibration.copy(enabled = on)) } },
                shape = shape, enabled = enabled && vibrator.isAvailable && !lockedOn,
            )
        }
        if (vib.enabled || lockedOn) {
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

/** "Still up?": the check a few minutes after the alarm was stopped. */
@Composable
fun StillUpSection(alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit) {
    val w = alarm.wakeCheck
    fun set(block: (WakeCheckSettings) -> WakeCheckSettings) = onChange { it.copy(wakeCheck = block(it.wakeCheck)) }
    SettingsGroup {
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.opt_check),
                subtitle = stringResource(R.string.opt_check_hint),
                icon = Icons.Outlined.NotificationsActive,
                checked = w.enabled,
                onCheckedChange = { on -> set { it.copy(enabled = on) } },
                shape = shape, enabled = enabled,
            )
        }
        if (w.enabled) {
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.opt_check_after),
                    valueText = minutesLabel(w.delayMinutes),
                    onMinus = { set { it.copy(delayMinutes = (w.delayMinutes - 1).coerceAtLeast(1)) } },
                    onPlus = { set { it.copy(delayMinutes = (w.delayMinutes + 1).coerceAtMost(30)) } },
                    canMinus = w.delayMinutes > 1, canPlus = w.delayMinutes < 30, shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                SwitchRow(
                    title = stringResource(R.string.opt_check_proof),
                    subtitle = stringResource(R.string.opt_check_proof_hint),
                    checked = w.requireProof,
                    onCheckedChange = { on -> set { it.copy(requireProof = on) } },
                    shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                val steps = listOf(15, 30, 60, 90, 120, 180, 300)
                ChipChoiceRow(
                    title = stringResource(R.string.wake_check_response),
                    subtitle = stringResource(R.string.wake_check_response_hint),
                    options = withCurrent(steps, w.responseSeconds),
                    selected = w.responseSeconds,
                    onSelect = { sec -> set { it.copy(responseSeconds = sec) } },
                    optionLabel = { fadeLabel(it) },
                    shape = shape, enabled = enabled,
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
                    onPlus = { set { it.copy(maxReRings = (w.maxReRings + 1).coerceAtMost(MAX_RE_RINGS)) } },
                    canMinus = w.maxReRings > 1, canPlus = w.maxReRings < MAX_RE_RINGS, shape = shape, enabled = enabled,
                )
            }
        }
    }
}

/** What a restored file may hold (BackupSanitizer), and what Hard and No mercy use. */
private const val MAX_RE_RINGS = 10

private enum class WayOutChoice { HOLD, TASKS, NONE }

/**
 * How it ends: ringing for ever or not, the hold, the way out without the mission, the backup mission and the backup ring.
 * The way out can be removed only on an alarm with a mission that had a finished rehearsal ([protection]).
 */
@Composable
fun StoppingSection(alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit, protection: ProtectionLink?) {
    val a = alarm.antiOversleep
    fun set(block: (AntiOversleepSettings) -> AntiOversleepSettings) = onChange { it.copy(antiOversleep = block(it.antiOversleep)) }
    var confirmNone by remember { mutableStateOf(false) }
    val hasMission = alarm.missions.isNotEmpty()
    SettingsGroup {
        item { shape ->
            val never = a.ringTimeoutMinutes == AntiOversleepSettings.RING_TIMEOUT_NEVER
            SwitchRow(
                title = stringResource(R.string.opt_keep_ringing),
                subtitle = stringResource(R.string.opt_keep_ringing_hint),
                checked = never,
                onCheckedChange = { on -> set { it.copy(ringTimeoutMinutes = if (on) AntiOversleepSettings.RING_TIMEOUT_NEVER else DEFAULT_TIMEOUT) } },
                shape = shape, enabled = enabled,
            )
        }
        if (a.ringTimeoutMinutes != AntiOversleepSettings.RING_TIMEOUT_NEVER) {
            item { shape ->
                // 1 to 30 minutes; "for ever" is the switch above, never a step below 1.
                StepperRow(
                    title = stringResource(R.string.opt_stop_after),
                    valueText = minutesLabel(a.ringTimeoutMinutes),
                    onMinus = { set { it.copy(ringTimeoutMinutes = (a.ringTimeoutMinutes - 1).coerceAtLeast(1)) } },
                    onPlus = { set { it.copy(ringTimeoutMinutes = (a.ringTimeoutMinutes + 1).coerceAtMost(30)) } },
                    canMinus = a.ringTimeoutMinutes > 1, canPlus = a.ringTimeoutMinutes < 30, shape = shape, enabled = enabled,
                )
            }
        }
        item { shape ->
            StepperRow(
                title = stringResource(R.string.opt_hold),
                subtitle = stringResource(R.string.ao_hold_hint),
                valueText = if (a.holdToDismissSeconds == 0) stringResource(R.string.state_off) else stringResource(R.string.unit_seconds, a.holdToDismissSeconds),
                onMinus = { set { it.copy(holdToDismissSeconds = (a.holdToDismissSeconds - 1).coerceAtLeast(0)) } },
                onPlus = { set { it.copy(holdToDismissSeconds = (a.holdToDismissSeconds + 1).coerceAtMost(10)) } },
                canMinus = a.holdToDismissSeconds > 0, canPlus = a.holdToDismissSeconds < 10, shape = shape, enabled = enabled,
            )
        }
    }
    // The way out and its backup exist only on an alarm with a mission: without one, stopping it is one gesture anyway.
    if (hasMission) {
        SettingsGroup(Modifier.padding(top = 8.dp)) {
            item { shape ->
                val rehearsed = protection?.rehearsed?.invoke(alarm) == true
                val current = when {
                    a.wayOutRemoved -> WayOutChoice.NONE
                    a.emergencyStopEnabled -> WayOutChoice.HOLD
                    else -> WayOutChoice.TASKS
                }
                val options = buildList {
                    add(WayOutChoice.HOLD)
                    add(WayOutChoice.TASKS)
                    // None needs a finished rehearsal of these missions; once chosen it can always be taken back.
                    if (protection != null && (rehearsed || a.wayOutRemoved)) add(WayOutChoice.NONE)
                }
                ChipChoiceRow(
                    title = stringResource(R.string.opt_way_out),
                    subtitle = stringResource(
                        when {
                            current == WayOutChoice.NONE -> R.string.ao_way_out_none_hint
                            protection != null && !rehearsed -> R.string.ao_way_out_none_locked
                            else -> R.string.opt_way_out_hint
                        },
                    ),
                    options = options,
                    selected = current,
                    onSelect = { choice ->
                        when (choice) {
                            WayOutChoice.HOLD -> set { it.copy(emergencyStopEnabled = true, wayOutRemoved = false) }
                            WayOutChoice.TASKS -> set { it.copy(emergencyStopEnabled = false, wayOutRemoved = false) }
                            WayOutChoice.NONE -> if (!a.wayOutRemoved) confirmNone = true
                        }
                    },
                    optionLabel = {
                        stringResource(
                            when (it) {
                                WayOutChoice.HOLD -> R.string.opt_way_out_hold
                                WayOutChoice.TASKS -> R.string.opt_way_out_tasks
                                WayOutChoice.NONE -> R.string.opt_way_out_none
                            },
                        )
                    },
                    shape = shape, enabled = enabled,
                )
            }
            if (!a.emergencyStopEnabled && !a.wayOutRemoved) {
                item { shape ->
                    val steps = listOf(0, 1, 3, 5, 10, 15, 20, 30)
                    ChipChoiceRow(
                        title = stringResource(R.string.opt_way_out_after),
                        options = withCurrent(steps, a.wayOutDelayMinutes),
                        selected = a.wayOutDelayMinutes,
                        onSelect = { m -> set { it.copy(wayOutDelayMinutes = m) } },
                        optionLabel = { if (it == 0) stringResource(R.string.opt_way_out_now) else minutesLabel(it) },
                        shape = shape, enabled = enabled,
                    )
                }
            }
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.opt_way_out_count),
                    valueText = a.wayOutCount.toString(),
                    onMinus = { set { it.copy(wayOutCount = (a.wayOutCount - 1).coerceAtLeast(MIN_WAY_OUT)) } },
                    onPlus = { set { it.copy(wayOutCount = (a.wayOutCount + 1).coerceAtMost(MAX_WAY_OUT)) } },
                    canMinus = a.wayOutCount > MIN_WAY_OUT, canPlus = a.wayOutCount < MAX_WAY_OUT, shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.opt_fallback),
                    subtitle = stringResource(R.string.opt_fallback_hint),
                    valueText = pluralStringResource(R.plurals.opt_fallback_value, a.fallbackCount, a.fallbackCount),
                    onMinus = { set { it.copy(fallbackCount = (a.fallbackCount - 1).coerceAtLeast(1)) } },
                    onPlus = { set { it.copy(fallbackCount = (a.fallbackCount + 1).coerceAtMost(MAX_FALLBACK)) } },
                    canMinus = a.fallbackCount > 1, canPlus = a.fallbackCount < MAX_FALLBACK, shape = shape, enabled = enabled,
                )
            }
        }
    }
    SettingsGroup(Modifier.padding(top = 8.dp)) {
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.opt_backup),
                subtitle = stringResource(R.string.opt_backup_hint),
                checked = a.backupEnabled,
                onCheckedChange = { on -> set { it.copy(backupEnabled = on) } },
                shape = shape, enabled = enabled,
            )
        }
        if (a.backupEnabled) {
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.opt_backup_after),
                    valueText = minutesLabel(a.backupDelayMinutes),
                    onMinus = { set { it.copy(backupDelayMinutes = (a.backupDelayMinutes - 1).coerceAtLeast(1)) } },
                    onPlus = { set { it.copy(backupDelayMinutes = (a.backupDelayMinutes + 1).coerceAtMost(30)) } },
                    canMinus = a.backupDelayMinutes > 1, canPlus = a.backupDelayMinutes < 30, shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.opt_backup_times),
                    valueText = a.backupCount.toString(),
                    onMinus = { set { it.copy(backupCount = (a.backupCount - 1).coerceAtLeast(1)) } },
                    onPlus = { set { it.copy(backupCount = (a.backupCount + 1).coerceAtMost(3)) } },
                    canMinus = a.backupCount > 1, canPlus = a.backupCount < 3, shape = shape, enabled = enabled,
                )
            }
        }
    }
    Text(
        stringResource(if (a.emergencyStopEnabled) R.string.ao_emergency_note else R.string.ao_emergency_note_off),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    if (confirmNone) {
        val restarts = a.maxResumes.coerceIn(1, AntiOversleepSettings.MAX_RESUMES_LIMIT)
        ConfirmDialog(
            title = stringResource(R.string.ao_way_out_none_title),
            text = pluralStringResource(R.plurals.ao_way_out_none_body, restarts, restarts),
            confirmLabel = stringResource(R.string.ao_way_out_none_confirm),
            onConfirm = { set { it.copy(wayOutRemoved = true) }; confirmNone = false },
            onDismiss = { confirmNone = false },
            destructive = true,
        )
    }
}

private const val DEFAULT_TIMEOUT = 10
private const val MIN_WAY_OUT = 3
private const val MAX_WAY_OUT = 30
private const val MAX_FALLBACK = 20

private enum class ReturnChoice { OFF, ALERT, FORCE }

/** What happens when someone tries to get away from the alarm: leaving its screen, the keys, mutes, restarts, edits. */
@Composable
fun LeavingSection(alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit, protection: ProtectionLink?) {
    val a = alarm.antiOversleep
    val res = LocalResources.current
    fun set(block: (AntiOversleepSettings) -> AntiOversleepSettings) = onChange { it.copy(antiOversleep = block(it.antiOversleep)) }
    SettingsGroup {
        item { shape ->
            val current = when {
                !a.returnToAlarm -> ReturnChoice.OFF
                a.forceReturn -> ReturnChoice.FORCE
                else -> ReturnChoice.ALERT
            }
            ChipChoiceRow(
                title = stringResource(R.string.opt_return),
                subtitle = stringResource(
                    when (current) {
                        ReturnChoice.OFF -> R.string.opt_return_hint_off
                        ReturnChoice.ALERT -> R.string.opt_return_hint_alert
                        ReturnChoice.FORCE -> R.string.opt_return_hint_force
                    },
                ),
                options = ReturnChoice.entries.toList(),
                selected = current,
                onSelect = { c ->
                    set {
                        when (c) {
                            ReturnChoice.OFF -> it.copy(returnToAlarm = false, forceReturn = false)
                            ReturnChoice.ALERT -> it.copy(returnToAlarm = true, forceReturn = false)
                            ReturnChoice.FORCE -> it.copy(returnToAlarm = true, forceReturn = true)
                        }
                    }
                },
                optionLabel = {
                    stringResource(
                        when (it) {
                            ReturnChoice.OFF -> R.string.opt_return_off
                            ReturnChoice.ALERT -> R.string.opt_return_alert
                            ReturnChoice.FORCE -> R.string.opt_return_force
                        },
                    )
                },
                shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            ChipChoiceRow(
                title = stringResource(R.string.opt_volume_keys),
                options = VolumeKeyAction.entries.toList(),
                selected = a.volumeKeyAction,
                onSelect = { k -> set { it.copy(volumeKeyAction = k) } },
                optionLabel = { stringResource(volumeKeyLabel(it)) },
                shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            StepperRow(
                title = stringResource(R.string.opt_mute),
                valueText = if (a.muteBudget == 0) stringResource(R.string.opt_mute_off) else pluralStringResource(R.plurals.opt_mute_times, a.muteBudget, a.muteBudget),
                onMinus = { set { it.copy(muteBudget = (a.muteBudget - 1).coerceAtLeast(0)) } },
                onPlus = { set { it.copy(muteBudget = (a.muteBudget + 1).coerceAtMost(MAX_MUTES)) } },
                canMinus = a.muteBudget > 0, canPlus = a.muteBudget < MAX_MUTES, shape = shape, enabled = enabled,
            )
        }
        if (a.muteBudget > 0) {
            item { shape ->
                StepperRow(
                    title = stringResource(R.string.opt_mute_length),
                    valueText = stringResource(R.string.unit_seconds, a.muteSeconds),
                    onMinus = { set { it.copy(muteSeconds = (a.muteSeconds - 5).coerceAtLeast(5)) } },
                    onPlus = { set { it.copy(muteSeconds = (a.muteSeconds + 5).coerceAtMost(60)) } },
                    canMinus = a.muteSeconds > 5, canPlus = a.muteSeconds < 60, shape = shape, enabled = enabled,
                )
            }
        }
    }
    SettingsGroup(Modifier.padding(top = 8.dp)) {
        item { shape ->
            val steps = listOf(10, 30, 60, 120, 360, 720, 1_440)
            ChipChoiceRow(
                title = stringResource(R.string.opt_restart),
                subtitle = stringResource(R.string.opt_restart_hint),
                options = withCurrent(steps, a.resumeWindowMinutes),
                selected = a.resumeWindowMinutes,
                onSelect = { m -> set { it.copy(resumeWindowMinutes = m) } },
                optionLabel = { LockText.duration(res, it) },
                shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            StepperRow(
                title = stringResource(R.string.opt_restart_times),
                valueText = a.maxResumes.toString(),
                onMinus = { set { it.copy(maxResumes = (a.maxResumes - 1).coerceAtLeast(1)) } },
                onPlus = { set { it.copy(maxResumes = (a.maxResumes + 1).coerceAtMost(AntiOversleepSettings.MAX_RESUMES_LIMIT)) } },
                canMinus = a.maxResumes > 1, canPlus = a.maxResumes < AntiOversleepSettings.MAX_RESUMES_LIMIT, shape = shape, enabled = enabled,
            )
        }
        item { shape ->
            // Up to 12 hours, so the lock can cover the night before as well as the last minutes.
            val steps = listOf(0, 5, 10, 15, 30, 60, 120, 240, 480, 720)
            ChipChoiceRow(
                title = stringResource(R.string.opt_lock),
                subtitle = stringResource(R.string.opt_lock_hint),
                options = withCurrent(steps, a.editLockMinutes),
                selected = a.editLockMinutes,
                onSelect = { m -> set { it.copy(editLockMinutes = m) } },
                optionLabel = { if (it == 0) stringResource(R.string.state_off) else LockText.duration(res, it) },
                shape = shape, enabled = enabled,
            )
        }
        if (a.editLockMinutes > 0 && alarm.missions.isNotEmpty()) {
            item { shape ->
                SwitchRow(
                    title = stringResource(R.string.opt_lock_mission),
                    subtitle = stringResource(R.string.opt_lock_mission_hint),
                    checked = a.lockOverrideMission,
                    onCheckedChange = { on -> set { it.copy(lockOverrideMission = on) } },
                    shape = shape, enabled = enabled,
                )
            }
        }
        if (protection != null) {
            item { shape -> NavRow(stringResource(R.string.protection_open_from_editor), shape, protection.open, icon = Icons.Outlined.Shield) }
        }
    }
}

private const val MAX_MUTES = 5

internal fun volumeKeyLabel(a: VolumeKeyAction) = when (a) {
    VolumeKeyAction.VOLUME -> R.string.vk_volume
    VolumeKeyAction.SNOOZE -> R.string.vk_snooze
    VolumeKeyAction.DISMISS -> R.string.vk_dismiss
    VolumeKeyAction.SILENCE_TEMPORARILY -> R.string.vk_silence
    VolumeKeyAction.NOTHING -> R.string.vk_nothing
}

/** Spoken time, the screen's glow before the alarm and its brightness while it rings. */
@Composable
fun LightSection(alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit) {
    val voice = alarm.voice
    val s = alarm.sunrise
    SettingsGroup {
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.opt_say_time),
                subtitle = stringResource(R.string.voice_subtitle),
                icon = Icons.Outlined.Campaign,
                checked = voice.enabled,
                onCheckedChange = { on -> onChange { it.copy(voice = it.voice.copy(enabled = on)) } },
                shape = shape, enabled = enabled,
            )
        }
        if (voice.enabled) {
            item { shape ->
                SwitchRow(
                    title = stringResource(R.string.voice_announce_time),
                    checked = voice.announceTime,
                    onCheckedChange = { on -> onChange { it.copy(voice = it.voice.copy(announceTime = on)) } },
                    shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                SwitchRow(
                    title = stringResource(R.string.voice_announce_label),
                    checked = voice.announceLabel,
                    onCheckedChange = { on -> onChange { it.copy(voice = it.voice.copy(announceLabel = on)) } },
                    shape = shape, enabled = enabled,
                )
            }
            item { shape ->
                val steps = listOf(15, 30, 45, 60, 90, 120)
                ChipChoiceRow(
                    title = stringResource(R.string.voice_repeat),
                    options = withCurrent(steps, voice.repeatSeconds),
                    selected = voice.repeatSeconds,
                    onSelect = { sec -> onChange { it.copy(voice = it.voice.copy(repeatSeconds = sec)) } },
                    optionLabel = { fadeLabel(it) },
                    shape = shape, enabled = enabled,
                )
            }
        }
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.opt_glow),
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
        item { shape ->
            SwitchRow(
                title = stringResource(R.string.opt_bright),
                icon = Icons.Outlined.LightMode,
                checked = alarm.antiOversleep.fullBrightness,
                onCheckedChange = { on -> onChange { it.copy(antiOversleep = it.antiOversleep.copy(fullBrightness = on)) } },
                shape = shape, enabled = enabled,
            )
        }
    }
}

/** When the alarm applies: its group, holidays, vacation, a heads-up and deleting a one-time alarm after use. */
@Composable
fun ExtrasSection(alarm: Alarm, enabled: Boolean, onChange: ((Alarm) -> Alarm) -> Unit, groups: List<com.riyadm.wakenudge.domain.model.AlarmGroup> = emptyList()) {
    SettingsGroup {
        if (groups.isNotEmpty()) {
            item { shape ->
                val options = listOf<Long?>(null) + groups.map { it.id }
                ChipChoiceRow(
                    title = stringResource(R.string.edit_group),
                    options = options,
                    selected = alarm.groupId?.takeIf { id -> groups.any { it.id == id } },
                    onSelect = { g -> onChange { it.copy(groupId = g) } },
                    optionLabel = { id -> if (id == null) stringResource(R.string.group_none) else groups.first { it.id == id }.name },
                    shape = shape, enabled = enabled,
                )
            }
        }
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
            ChipChoiceRow(
                title = stringResource(R.string.extras_upcoming),
                subtitle = stringResource(R.string.extras_upcoming_hint),
                options = withCurrent(listOf(0, 15, 30, 60, 120), alarm.upcomingNoticeMinutes),
                selected = alarm.upcomingNoticeMinutes,
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
