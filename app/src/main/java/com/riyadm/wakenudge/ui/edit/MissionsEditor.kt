package com.riyadm.wakenudge.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.PuzzleKind
import com.riyadm.wakenudge.missions.MissionInfo

import com.riyadm.wakenudge.missions.MissionPreview
import com.riyadm.wakenudge.missions.MissionSetup
import com.riyadm.wakenudge.ui.components.ChipChoiceRow
import com.riyadm.wakenudge.ui.components.SectionTitle
import com.riyadm.wakenudge.ui.components.StepperRow
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.PolygonShape

/**
 * Edits an ordered list of wake-up missions: add, remove, reorder, tune difficulty and count, run the one-time setup
 * for QR / NFC / photo missions, and try any mission out before relying on it.
 */
@Composable
fun MissionListEditor(
    title: String,
    hint: String,
    missions: List<MissionConfig>,
    enabled: Boolean,
    maxMissions: Int,
    onChange: (List<MissionConfig>) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalWakeHaptics.current
    var picking by remember { mutableStateOf(false) }
    var setupIndex by remember { mutableStateOf<Int?>(null) }
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    SectionTitle(title)
    Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        missions.forEachIndexed { index, mission ->
            MissionCard(
                mission = mission, index = index, last = index == missions.lastIndex, enabled = enabled,
                onChange = { updated -> onChange(missions.toMutableList().also { it[index] = updated }) },
                onRemove = { onChange(missions.toMutableList().also { it.removeAt(index) }) },
                onMove = { delta ->
                    val target = index + delta
                    if (target in missions.indices) onChange(missions.toMutableList().also { val m = it.removeAt(index); it.add(target, m) })
                },
                onSetup = { setupIndex = index },
                onPreview = { previewIndex = index },
            )
        }
        if (missions.size < maxMissions) {
            OutlinedButton(onClick = { haptics.click(); picking = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.edit_missions_add))
            }
        }
    }

    if (picking) {
        MissionPicker(
            onPick = { type ->
                picking = false
                val config = MissionConfig(type = type, difficulty = Difficulty.MEDIUM)
                onChange(missions + config)
                if (type.needsSetup) setupIndex = missions.size
            },
            onDismiss = { picking = false },
        )
    }
    setupIndex?.let { index ->
        val config = missions.getOrNull(index)
        if (config != null) {
            FullScreenDialog(onDismiss = { setupIndex = null }) {
                MissionSetup(
                    config = config,
                    onSaved = { saved -> onChange(missions.toMutableList().also { it[index] = saved }); setupIndex = null },
                    onCancel = { setupIndex = null },
                )
            }
        }
    }
    previewIndex?.let { index ->
        val config = missions.getOrNull(index)
        if (config != null) FullScreenDialog(onDismiss = { previewIndex = null }) { MissionPreview(config = config, onClose = { previewIndex = null }) }
    }
}

@Composable
private fun FullScreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().navigationBarsPadding().padding(top = 24.dp)) { content() }
        }
    }
}

@Composable
private fun MissionCard(
    mission: MissionConfig,
    index: Int,
    last: Boolean,
    enabled: Boolean,
    onChange: (MissionConfig) -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    onSetup: () -> Unit,
    onPreview: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val usesDifficulty = mission.type !in setOf(MissionType.QR_BARCODE, MissionType.NFC)
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(PolygonShape(Blobs.Cookie)).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(MissionInfo.icon(mission.type), null, tint = scheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("${index + 1}. ${stringResource(MissionInfo.titleRes(mission.type))}", style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(MissionInfo.descriptionRes(mission.type)), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                IconButton(onClick = { onMove(-1) }, enabled = enabled && index > 0) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.edit_missions_move_up)) }
                IconButton(onClick = { onMove(1) }, enabled = enabled && !last) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.edit_missions_move_down)) }
                IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Filled.Close, stringResource(R.string.cd_remove)) }
            }
            if (usesDifficulty) {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    ChipChoiceRow(
                        title = stringResource(R.string.edit_missions_difficulty), options = Difficulty.entries.toList(), selected = mission.difficulty,
                        onSelect = { onChange(mission.copy(difficulty = it)) }, optionLabel = { stringResource(difficultyLabel(it)) },
                        shape = RoundedCornerShape(20.dp), enabled = enabled,
                    )
                }
            }
            if (mission.type == MissionType.PUZZLE) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    ChipChoiceRow(
                        title = stringResource(R.string.edit_missions_puzzle_kind), options = PuzzleKind.entries.toList(), selected = mission.puzzleKind,
                        onSelect = { onChange(mission.copy(params = mission.params + (MissionConfig.PARAM_PUZZLE to it.name))) },
                        optionLabel = { stringResource(puzzleLabel(it)) }, shape = RoundedCornerShape(20.dp), enabled = enabled,
                    )
                }
            }
            val range = countRange(mission.type)
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                StepperRow(
                    title = stringResource(countLabel(mission.type)), valueText = mission.count.toString(),
                    onMinus = { onChange(mission.copy(count = (mission.count - range.step).coerceAtLeast(range.min))) },
                    onPlus = { onChange(mission.copy(count = (mission.count + range.step).coerceAtMost(range.max))) },
                    canMinus = mission.count > range.min, canPlus = mission.count < range.max,
                    shape = RoundedCornerShape(20.dp), enabled = enabled,
                )
            }
            if (mission.type.needsSetup) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ok = mission.isConfigured
                    Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = if (ok) scheme.primary else scheme.error)
                    Text(
                        if (ok) stringResource(R.string.edit_missions_setup_done, setupSummary(mission)) else stringResource(R.string.edit_missions_needs_setup),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    TextButton(onClick = onSetup, enabled = enabled) { Text(stringResource(if (ok) R.string.edit_missions_change else R.string.edit_missions_set_up)) }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = onPreview, enabled = !mission.type.needsSetup || mission.isConfigured) {
                    Icon(Icons.Outlined.PlayCircle, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.edit_missions_preview))
                }
            }
        }
    }
}

@Composable
private fun setupSummary(mission: MissionConfig): String = when (mission.type) {
    MissionType.QR_BARCODE -> mission.params[MissionConfig.PARAM_CODE_LABEL].orEmpty().ifBlank { stringResource(R.string.edit_missions_code_registered) }
    MissionType.NFC -> stringResource(R.string.edit_missions_tag_registered)
    MissionType.PHOTO -> stringResource(R.string.edit_missions_photo_saved)
    else -> ""
}

@Composable
private fun MissionPicker(onPick: (MissionType) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.edit_missions_pick_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) }
            items(MissionType.entries.toList(), key = { it.name }) { type ->
                val supported = MissionInfo.isSupported(context, type)
                Surface(
                    onClick = { if (supported) onPick(type) },
                    enabled = supported,
                    shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(PolygonShape(Blobs.Flower)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                            Icon(MissionInfo.icon(type), null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(stringResource(MissionInfo.titleRes(type)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (supported) 1f else 0.5f))
                            Text(
                                stringResource(if (supported) MissionInfo.descriptionRes(type) else MissionInfo.unsupportedReasonRes(type)),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.size(24.dp)) }
        }
    }
}

private class CountRange(val min: Int, val max: Int, val step: Int)

private fun countRange(type: MissionType) = when (type) {
    MissionType.MATH -> CountRange(1, 10, 1)
    MissionType.MEMORY -> CountRange(1, 8, 1)
    MissionType.TYPING -> CountRange(1, 5, 1)
    MissionType.SHAKE -> CountRange(10, 100, 5)
    MissionType.STEPS -> CountRange(10, 200, 10)
    MissionType.SQUATS -> CountRange(3, 30, 1)
    MissionType.PUZZLE, MissionType.QR_BARCODE, MissionType.NFC, MissionType.PHOTO -> CountRange(1, 3, 1)
}

private fun countLabel(type: MissionType) = when (type) {
    MissionType.MATH -> R.string.edit_missions_count_math
    MissionType.MEMORY -> R.string.edit_missions_count_memory
    MissionType.TYPING -> R.string.edit_missions_count_typing
    MissionType.SHAKE -> R.string.edit_missions_count_shake
    MissionType.STEPS -> R.string.edit_missions_count_steps
    MissionType.SQUATS -> R.string.edit_missions_count_squats
    else -> R.string.edit_missions_count_times
}

private fun difficultyLabel(d: Difficulty) = when (d) {
    Difficulty.EASY -> R.string.difficulty_easy
    Difficulty.MEDIUM -> R.string.difficulty_medium
    Difficulty.HARD -> R.string.difficulty_hard
}

private fun puzzleLabel(k: PuzzleKind) = when (k) {
    PuzzleKind.SLIDING_TILES -> R.string.puzzle_sliding
    PuzzleKind.NUMBER_GRID -> R.string.puzzle_number_grid
    PuzzleKind.PATTERN_TRACE -> R.string.puzzle_pattern
}

/** Builds the two slots the alarm editor expects: wake-up missions and the optional solve-to-snooze missions. */
fun missionSlots(): MissionSlots = MissionSlots(
    missions = { alarm, enabled, onChange ->
        MissionListEditor(
            title = stringResource(R.string.edit_missions_title),
            hint = stringResource(R.string.edit_missions_hint),
            missions = alarm.missions, enabled = enabled, maxMissions = MissionConfig.MAX_MISSIONS,
            onChange = { list -> onChange { it.copy(missions = list) } },
        )
    },
    snoozeMissions = { alarm, enabled, onChange ->
        MissionListEditor(
            title = stringResource(R.string.edit_missions_snooze_title),
            hint = stringResource(R.string.edit_missions_snooze_hint),
            missions = alarm.snoozeMissions, enabled = enabled, maxMissions = 2,
            onChange = { list -> onChange { it.copy(snoozeMissions = list) } },
        )
    },
)

