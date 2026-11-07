package com.riyadm.wakenudge.missions

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.riyadm.wakenudge.missions.ui.LocalMissionFallback
import com.riyadm.wakenudge.missions.ui.MissionFallback
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.missions.focus.FocusMission
import com.riyadm.wakenudge.missions.math.MathMission
import com.riyadm.wakenudge.missions.memory.MemoryMission
import com.riyadm.wakenudge.missions.nfc.NfcMission
import com.riyadm.wakenudge.missions.nfc.NfcSetup
import com.riyadm.wakenudge.missions.photo.PhotoMission
import com.riyadm.wakenudge.missions.photo.PhotoSetup
import com.riyadm.wakenudge.missions.photo.PhotoSignature
import com.riyadm.wakenudge.missions.puzzle.PuzzleMission
import com.riyadm.wakenudge.missions.scan.QrMission
import com.riyadm.wakenudge.missions.scan.QrSetup
import com.riyadm.wakenudge.missions.sensors.ShakeMission
import com.riyadm.wakenudge.missions.sensors.SquatsMission
import com.riyadm.wakenudge.missions.sensors.StepsMission
import com.riyadm.wakenudge.missions.typing.TypingMission
import com.riyadm.wakenudge.missions.ui.MissionButton
import com.riyadm.wakenudge.missions.ui.MissionButtonKind
import com.riyadm.wakenudge.missions.ui.MissionNotice
import com.riyadm.wakenudge.missions.ui.MissionScaffold
import com.riyadm.wakenudge.missions.ui.PulsingBadge
import com.riyadm.wakenudge.missions.ui.TouchTarget
import com.riyadm.wakenudge.ui.components.rememberReduceMotion
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.MorphShape
import com.riyadm.wakenudge.ui.theme.WakeMotion

/**
 * Runs [missions] one after another. [onProgress] gets the number of completed missions after each one; [onComplete]
 * fires once, after the last. [onInteraction] is called on every meaningful user input or sensor event (the caller
 * rate-limits it; it calms an escalating alarm). With [previewMode] a small "Preview" label shows and every mission
 * also offers a "Skip" action; behaviour is otherwise real.
 *
 * Progress is kept across recomposition and configuration changes. During a real ring ([backup] set) a mission that
 * cannot be done (missing hardware, never set up, a dead sensor or camera, a refused permission) is never passed: a
 * backup mission of [FallbackChain] steps takes its place, and the mission counts as done only when that is finished.
 */
@Composable
fun MissionHost(
    missions: List<MissionConfig>,
    modifier: Modifier = Modifier,
    previewMode: Boolean = false,
    onInteraction: () -> Unit = {},
    onProgress: (completed: Int) -> Unit = {},
    /** Missions already done in this ring (the user went back, or the screen was rebuilt): the chain resumes there. */
    firstMission: Int = 0,
    /**
     * Whether [firstMission] comes from something that outlives this screen, as it does on the ring screen. Progress is
     * then not saved here as well: a screen restored after the process died must not believe more was done than the
     * engine counted, or the chain would finish and the engine would refuse to end the ring.
     */
    progressKeptElsewhere: Boolean = false,
    /** What a mission that cannot be done is replaced by; null outside a real ring (previews, setup). */
    backup: MissionBackup? = null,
    onComplete: () -> Unit,
) {
    val total = missions.size
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnComplete by rememberUpdatedState(onComplete)
    val reduceMotion = rememberReduceMotion()
    val completedState = if (progressKeptElsewhere) {
        remember { mutableIntStateOf(firstMission.coerceIn(0, total)) }
    } else {
        rememberSaveable { mutableIntStateOf(firstMission.coerceIn(0, total)) }
    }
    var completed by completedState
    val completionState = if (progressKeptElsewhere) {
        remember { mutableStateOf(false) }
    } else {
        rememberSaveable { mutableStateOf(false) }
    }
    var completionReported by completionState

    LaunchedEffect(completed, total) {
        if (completed >= total && !completionReported) {
            completionReported = true
            latestOnComplete()
        }
    }

    Column(modifier.fillMaxSize()) {
        MissionHeader(missions, completed.coerceAtMost(total), previewMode)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = completed.coerceAtMost(total),
                transitionSpec = { missionTransition(reduceMotion) },
                label = "mission",
            ) { index ->
                if (index >= total) {
                    AllDone()
                } else {
                    // Capturing the index makes a second completion report from the same mission harmless.
                    val finishMission = {
                        if (completed == index) {
                            completed = index + 1
                            latestOnProgress(index + 1)
                        }
                    }
                    MissionGate(missions[index], index, previewMode, backup, onInteraction, finishMission)
                }
            }
        }
        if (previewMode && completed < total) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                MissionButton(
                    stringResource(R.string.mission_skip),
                    {
                        val index = completed
                        completed = index + 1
                        latestOnProgress(index + 1)
                    },
                    kind = MissionButtonKind.Text,
                )
            }
        }
    }
}

private fun missionTransition(reduceMotion: Boolean): ContentTransform =
    if (reduceMotion) {
        fadeIn(WakeMotion.defaultEffects()) togetherWith fadeOut(WakeMotion.fastEffects())
    } else {
        (slideInHorizontally(WakeMotion.defaultSpatial()) { it / 3 } + fadeIn(WakeMotion.defaultEffects()))
            .togetherWith(slideOutHorizontally(WakeMotion.defaultSpatial()) { -it / 3 } + fadeOut(WakeMotion.fastEffects()))
    }

/** Full-content preview of one mission for the alarm editor, with a close button. Behaves like the real thing, plus Skip. */
@Composable
fun MissionPreview(config: MissionConfig, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val haptics = LocalWakeHaptics.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { haptics.click(); onClose() }, modifier = Modifier.size(TouchTarget)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.mission_close_preview))
                }
            }
            MissionHost(listOf(config), Modifier.weight(1f), previewMode = true, onComplete = onClose)
        }
    }
}

/**
 * One-time setup for missions with [MissionType.needsSetup]: QR_BARCODE (scan the code to register), NFC (tap the
 * tag to register) and PHOTO (capture the reference photo). Returns the config with its PARAM_* filled in through
 * [onSaved]. Missions without setup are returned unchanged immediately.
 */
@Composable
fun MissionSetup(config: MissionConfig, onSaved: (MissionConfig) -> Unit, onCancel: () -> Unit) {
    when (config.type) {
        MissionType.QR_BARCODE -> QrSetup(config, onSaved, onCancel)
        MissionType.NFC -> NfcSetup(config, onSaved, onCancel)
        MissionType.PHOTO -> PhotoSetup(config, onSaved, onCancel)
        else -> {
            val latest by rememberUpdatedState(onSaved)
            LaunchedEffect(config) { latest(config) }
        }
    }
}

/** Whether a mission has what it needs to be solved at all: a registered code/tag, or a readable reference signature. */
private fun isReady(config: MissionConfig): Boolean = config.isConfigured &&
    (config.type != MissionType.PHOTO || config.params[MissionConfig.PARAM_SIGNATURE]?.let { PhotoSignature.decode(it) } != null)

/**
 * How a real ring replaces a mission that cannot be done. [ao] gives the lengths, [style] what the steps are made of, and
 * [onUsed] tells the ring that a backup stood in, for the history.
 */
class MissionBackup(val ao: AntiOversleepSettings, val style: ChainStyle = ChainStyle.MIXED, val onUsed: () -> Unit = {})

@Composable
private fun MissionGate(config: MissionConfig, index: Int, previewMode: Boolean, backup: MissionBackup?, onInteraction: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val supported = remember(config.type) { MissionInfo.isSupported(context, config.type) }
    var reason by rememberSaveable { mutableStateOf<FallbackReason?>(null) }
    val gateReason = when {
        !supported -> FallbackReason.UNSUPPORTED
        !config.isConfigured -> FallbackReason.NOT_SET_UP
        !isReady(config) -> FallbackReason.BROKEN_SETUP
        else -> null
    }
    val active = reason ?: gateReason?.takeIf { backup != null }
    if (active != null && backup != null) {
        LaunchedEffect(active) { backup.onUsed() }
        BackupMission(config, active, index, backup, onInteraction, onDone)
        return
    }
    when {
        !supported -> Unavailable(
            icon = Icons.Filled.SensorsOff,
            title = stringResource(MissionInfo.titleRes(config.type)),
            body = stringResource(MissionInfo.unsupportedReasonRes(config.type)),
        )
        !isReady(config) -> Unavailable(
            icon = Icons.Filled.Construction,
            title = stringResource(R.string.mission_not_set_up_title),
            body = stringResource(R.string.mission_not_set_up_body, stringResource(MissionInfo.titleRes(config.type))),
        )
        else -> {
            val fallback = remember(backup) {
                backup?.let { b -> MissionFallback(steps = { FallbackChain.length(it, b.ao) }, start = { reason = it }) }
            }
            CompositionLocalProvider(LocalMissionFallback provides if (previewMode) null else fallback) {
                MissionContent(config, onInteraction, onDone)
            }
        }
    }
}

/** The backup mission in place of [config]: why, then the steps, and the mission counts as done when they are. */
@Composable
private fun BackupMission(config: MissionConfig, reason: FallbackReason, index: Int, backup: MissionBackup, onInteraction: () -> Unit, onDone: () -> Unit) {
    val steps = remember(reason, backup.ao, backup.style, index) { FallbackChain.backup(reason, backup.ao, backup.style, offset = index) }
    val name = stringResource(MissionInfo.titleRes(config.type))
    val why = when (reason) {
        FallbackReason.UNSUPPORTED -> stringResource(MissionInfo.unsupportedReasonRes(config.type))
        FallbackReason.NOT_SET_UP -> stringResource(R.string.mission_backup_why_not_set_up, name)
        FallbackReason.BROKEN_SETUP -> stringResource(R.string.mission_backup_why_broken, name)
        FallbackReason.CAMERA_FAILED -> stringResource(R.string.mission_backup_why_camera)
        FallbackReason.CAMERA_DARK -> stringResource(R.string.mission_backup_why_dark)
        FallbackReason.SENSOR_SILENT -> stringResource(R.string.mission_backup_why_sensor)
        FallbackReason.PERMISSION_REFUSED -> stringResource(R.string.mission_backup_why_permission, name)
        FallbackReason.NFC_OFF -> stringResource(R.string.mission_backup_why_nfc)
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(12.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            Text(
                stringResource(R.string.mission_backup_title), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.semantics { heading() },
            )
            Text(
                why + " " + pluralStringResource(R.plurals.mission_backup_steps, steps.size, steps.size),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        // Its own chain, done in turn; nothing in it needs hardware, a permission or a setup, so it cannot fail in turn.
        MissionHost(steps, Modifier.weight(1f), onInteraction = onInteraction, onComplete = onDone)
    }
}

@Composable
private fun MissionContent(config: MissionConfig, onInteraction: () -> Unit, onDone: () -> Unit) {
    when (config.type) {
            MissionType.MATH -> MathMission(config, onInteraction, onDone)
            MissionType.MEMORY -> MemoryMission(config, onInteraction, onDone)
            MissionType.TYPING -> TypingMission(config, onInteraction, onDone)
            MissionType.QR_BARCODE -> QrMission(config, onInteraction, onDone)
            MissionType.NFC -> NfcMission(config, onInteraction, onDone)
            MissionType.SHAKE -> ShakeMission(config, onInteraction, onDone)
            MissionType.STEPS -> StepsMission(config, onInteraction, onDone)
            MissionType.SQUATS -> SquatsMission(config, onInteraction, onDone)
            MissionType.PUZZLE -> PuzzleMission(config, onInteraction, onDone)
            MissionType.PHOTO -> PhotoMission(config, onInteraction, onDone)
            MissionType.FOCUS_TAP -> FocusMission(config, onInteraction, onDone)
    }
}

/** Explains why a mission cannot run, in a preview (where Skip moves on) or wherever there is no backup to stand in. */
@Composable
private fun Unavailable(icon: ImageVector, title: String, body: String) {
    MissionScaffold {
        MissionNotice(icon, title, body)
    }
}

@Composable
private fun AllDone() {
    MissionScaffold(scrollable = false, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        PulsingBadge(
            icon = Icons.Filled.Check,
            size = 120.dp,
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(stringResource(R.string.mission_all_done), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    }
}

// ---- header ------------------------------------------------------------------------------------------------------

@Composable
private fun MissionHeader(missions: List<MissionConfig>, completed: Int, previewMode: Boolean) {
    val total = missions.size
    val current = missions.getOrNull(completed)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // A single mission at ring time has neither steps nor a preview badge: no empty row taking the mission's height.
        if ((total in 2..MissionConfig.MAX_MISSIONS) || previewMode) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (total in 2..MissionConfig.MAX_MISSIONS) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        missions.forEachIndexed { index, mission -> StepChip(mission.type, index, completed) }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (previewMode) PreviewBadge()
            }
        }
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MissionInfo.icon(current.type), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    if (total > 1) {
                        Text(
                            stringResource(R.string.mission_step_of, completed + 1, total),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(MissionInfo.titleRes(current.type)),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewBadge() {
    Text(
        stringResource(R.string.mission_preview_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private val ChipMorph by lazy { Blobs.morph(Blobs.Circle, Blobs.Cookie) }

/** One mission in the step indicator: a check when done, a cookie highlighting the one in progress, a plain circle ahead. */
@Composable
private fun StepChip(type: MissionType, index: Int, completed: Int) {
    val done = index < completed
    val current = index == completed
    val scheme = MaterialTheme.colorScheme
    val morph by animateFloatAsState(if (current) 1f else 0f, WakeMotion.defaultEffects(), label = "chipMorph")
    val container by animateColorAsState(
        when {
            current -> scheme.primary
            done -> scheme.primaryContainer
            else -> scheme.surfaceContainerHighest
        },
        WakeMotion.defaultEffects(),
        label = "chipColor",
    )
    val content = when {
        current -> scheme.onPrimary
        done -> scheme.onPrimaryContainer
        else -> scheme.onSurfaceVariant
    }
    val stateText = stringResource(
        when {
            done -> R.string.mission_chip_done
            current -> R.string.mission_chip_current
            else -> R.string.mission_chip_upcoming
        },
    )
    val name = stringResource(MissionInfo.titleRes(type))
    Box(
        Modifier
            .size(40.dp)
            .clip(MorphShape(ChipMorph, morph))
            .background(container)
            .semantics { contentDescription = "${index + 1}. $name, $stateText" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (done) Icons.Filled.Check else MissionInfo.icon(type), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
    }
}
