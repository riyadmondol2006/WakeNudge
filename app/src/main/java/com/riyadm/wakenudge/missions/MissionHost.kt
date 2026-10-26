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
import com.riyadm.wakenudge.missions.ui.LocalMissionWayOut
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
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
 * Progress is kept across recomposition and configuration changes. A mission that can never succeed on this phone
 * (missing hardware, or never set up) shows an explanation with a Continue button instead of trapping the user.
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
    onComplete: () -> Unit,
) {
    val total = missions.size
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnComplete by rememberUpdatedState(onComplete)
    val reduceMotion = rememberReduceMotion()
    var completed by rememberSaveable { mutableIntStateOf(firstMission.coerceIn(0, total)) }
    var completionReported by rememberSaveable { mutableStateOf(false) }

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
                    MissionGate(missions[index], previewMode, onInteraction, finishMission)
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

@Composable
private fun MissionGate(config: MissionConfig, previewMode: Boolean, onInteraction: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val supported = remember(config.type) { MissionInfo.isSupported(context, config.type) }
    when {
        !supported -> Unavailable(
            icon = Icons.Filled.SensorsOff,
            title = stringResource(MissionInfo.titleRes(config.type)),
            body = stringResource(MissionInfo.unsupportedReasonRes(config.type)),
            previewMode = previewMode,
            onContinue = onDone,
        )
        !isReady(config) -> Unavailable(
            icon = Icons.Filled.Construction,
            title = stringResource(R.string.mission_not_set_up_title),
            body = stringResource(R.string.mission_not_set_up_body, stringResource(MissionInfo.titleRes(config.type))),
            previewMode = previewMode,
            onContinue = onDone,
        )
        else -> CompositionLocalProvider(LocalMissionWayOut provides if (previewMode) null else onDone) {
            MissionContent(config, onInteraction, onDone)
        }
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
    }
}

/** Explains why a mission cannot run. In a real alarm a Continue button lets the user through; in preview, Skip already does. */
@Composable
private fun Unavailable(icon: ImageVector, title: String, body: String, previewMode: Boolean, onContinue: () -> Unit) {
    MissionScaffold {
        MissionNotice(icon, title, body) {
            if (!previewMode) MissionButton(stringResource(R.string.mission_continue), onContinue, Modifier.fillMaxWidth())
        }
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
        if (total > 1 || previewMode) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (total > 1) {
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
