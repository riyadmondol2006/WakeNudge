package com.riyadm.wakenudge.missions.sensors

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.missions.MissionInfo
import com.riyadm.wakenudge.missions.ui.MissionButton
import com.riyadm.wakenudge.missions.ui.MissionButtonKind
import com.riyadm.wakenudge.missions.ui.MissionNotice
import com.riyadm.wakenudge.missions.ui.MissionPrompt
import com.riyadm.wakenudge.missions.ui.MissionScaffold
import com.riyadm.wakenudge.missions.ui.PermissionGate
import com.riyadm.wakenudge.missions.ui.PulsingBadge
import com.riyadm.wakenudge.missions.ui.SensorEventsEffect
import com.riyadm.wakenudge.missions.ui.hasSensor
import com.riyadm.wakenudge.missions.ui.isPermissionGranted
import com.riyadm.wakenudge.ui.components.WavyCircularProgress
import com.riyadm.wakenudge.ui.components.rememberReduceMotion
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.WakeMotion
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A big wavy ring with the running count in the middle; announced to screen readers as "n of total". */
@Composable
private fun CounterRing(count: Int, total: Int, caption: String, icon: ImageVector, iconRotation: Float = 0f, size: Dp = 248.dp) {
    val progress by animateFloatAsState((count.toFloat() / total).coerceIn(0f, 1f), WakeMotion.defaultSpatial(), label = "counterRing")
    val description = stringResource(R.string.mission_counter_description, count, total)
    Box(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
        contentAlignment = Alignment.Center,
    ) {
        WavyCircularProgress(progress, size, strokeWidth = 12.dp, amplitude = 5.dp, waves = 16)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp).graphicsLayer { rotationZ = iconRotation },
            )
            Text("$count", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(caption, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SensorMissingNotice(type: MissionType) {
    MissionScaffold {
        MissionNotice(Icons.Filled.SensorsOff, stringResource(MissionInfo.titleRes(type)), stringResource(MissionInfo.unsupportedReasonRes(type)))
    }
}

/** Shake the phone [MissionConfig.count] times; harder levels need harder shakes. */
@Composable
internal fun ShakeMission(config: MissionConfig, onInteraction: () -> Unit, onComplete: () -> Unit) {
    val context = LocalContext.current
    if (!context.hasSensor(Sensor.TYPE_ACCELEROMETER)) return SensorMissingNotice(MissionType.SHAKE)
    val haptics = LocalWakeHaptics.current
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val total = config.count.coerceAtLeast(1)
    var shakes by rememberSaveable { mutableIntStateOf(0) }
    var finished by rememberSaveable { mutableStateOf(false) }
    val detector = remember(config.difficulty) { ShakeDetector.forDifficulty(config.difficulty) }
    val wobble = remember { Animatable(0f) }

    SensorEventsEffect(Sensor.TYPE_ACCELEROMETER, enabled = !finished) { event ->
        val (x, y, z) = event.values
        // Any real movement shows the user is awake and trying, even if it is too gentle to count yet.
        if (abs(sqrt(x * x + y * y + z * z) - STANDARD_GRAVITY) > MOVEMENT_THRESHOLD_MS2) onInteraction()
        if (detector.onSample(event.timestamp, x, y, z)) {
            shakes++
            onInteraction()
            if (shakes >= total) {
                haptics.confirm()
                finished = true
            } else {
                haptics.tick()
            }
            if (!reduceMotion) scope.launch {
                wobble.snapTo(if (shakes % 2 == 0) 16f else -16f)
                wobble.animateTo(0f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium))
            }
        }
    }
    LaunchedEffect(finished) {
        if (finished) {
            delay(450)
            onComplete()
        }
    }

    MissionScaffold(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        MissionPrompt(stringResource(R.string.mission_shake_prompt), body = stringResource(R.string.mission_shake_hint))
        CounterRing(shakes.coerceAtMost(total), total, pluralStringResource(R.plurals.mission_shake_caption, total, total), Icons.Filled.Vibration, wobble.value)
    }
}

private val TrackerSaver = listSaver<StepCounterTracker, Float>(save = { it.toSaved() }, restore = { StepCounterTracker.restore(it) })

/**
 * Walk [MissionConfig.count] steps. Uses the hardware step counter when the phone has one and the user allowed
 * activity recognition; otherwise the step detector, and finally a pure-accelerometer pedometer that needs no
 * permission. The user can pick the motion sensor right away instead of granting the permission.
 */
@Composable
internal fun StepsMission(config: MissionConfig, onInteraction: () -> Unit, onComplete: () -> Unit) {
    val context = LocalContext.current
    val hasCounter = context.hasSensor(Sensor.TYPE_STEP_COUNTER)
    val hasDetector = context.hasSensor(Sensor.TYPE_STEP_DETECTOR)
    val hasAccelerometer = context.hasSensor(Sensor.TYPE_ACCELEROMETER)
    val permission = MissionInfo.requiredPermission(MissionType.STEPS)
    var preferAccelerometer by rememberSaveable { mutableStateOf(false) }

    if (permission != null && (hasCounter || hasDetector) && !preferAccelerometer) {
        PermissionGate(
            permission = permission,
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            title = stringResource(R.string.mission_steps_permission_title),
            rationale = stringResource(R.string.mission_steps_permission_rationale),
            extraActions = {
                if (hasAccelerometer) {
                    MissionButton(
                        stringResource(R.string.mission_steps_use_motion),
                        { preferAccelerometer = true },
                        Modifier.fillMaxWidth(),
                        kind = MissionButtonKind.Tonal,
                    )
                }
            },
        ) {
            StepsCounting(StepSensorAvailability(hasCounter, hasDetector, hasAccelerometer, permissionGranted = true), config, onInteraction, onComplete)
        }
    } else {
        val granted = permission == null || isPermissionGranted(context, permission)
        StepsCounting(StepSensorAvailability(hasCounter, hasDetector, hasAccelerometer, granted, preferAccelerometer), config, onInteraction, onComplete)
    }
}

@Composable
private fun StepsCounting(availability: StepSensorAvailability, config: MissionConfig, onInteraction: () -> Unit, onComplete: () -> Unit) {
    val source = availability.choose()
    if (source == StepSource.NONE) return SensorMissingNotice(MissionType.STEPS)
    val haptics = LocalWakeHaptics.current
    val total = config.count.coerceAtLeast(1)
    val tracker = rememberSaveable(saver = TrackerSaver) { StepCounterTracker() }
    var steps by rememberSaveable { mutableIntStateOf(tracker.steps) }
    var finished by rememberSaveable { mutableStateOf(false) }
    val accelerometerPedometer = remember { StepDetector() }

    fun update(newSteps: Int) {
        if (newSteps == steps || finished) return
        steps = newSteps
        onInteraction()
        if (steps >= total) {
            haptics.confirm()
            finished = true
        } else {
            haptics.tick()
        }
    }

    SensorEventsEffect(Sensor.TYPE_STEP_COUNTER, enabled = source == StepSource.COUNTER && !finished, samplingPeriodUs = SensorManager.SENSOR_DELAY_UI) {
        update(tracker.onCounter(it.values[0]))
    }
    SensorEventsEffect(Sensor.TYPE_STEP_DETECTOR, enabled = source == StepSource.DETECTOR && !finished, samplingPeriodUs = SensorManager.SENSOR_DELAY_UI) {
        update(tracker.onDetectorStep())
    }
    SensorEventsEffect(Sensor.TYPE_ACCELEROMETER, enabled = source == StepSource.ACCELEROMETER && !finished) { event ->
        val credited = accelerometerPedometer.onSample(event.timestamp, event.values[0], event.values[1], event.values[2])
        if (credited > 0) update(tracker.add(credited))
    }
    LaunchedEffect(finished) {
        if (finished) {
            delay(450)
            onComplete()
        }
    }

    MissionScaffold(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        MissionPrompt(
            pluralStringResource(R.plurals.mission_steps_prompt, total, total),
            body = stringResource(
                when (source) {
                    StepSource.COUNTER -> R.string.mission_steps_source_counter
                    StepSource.DETECTOR -> R.string.mission_steps_source_detector
                    else -> R.string.mission_steps_source_motion
                },
            ),
        )
        CounterRing(steps.coerceAtMost(total), total, pluralStringResource(R.plurals.mission_steps_caption, total, total), Icons.AutoMirrored.Filled.DirectionsWalk)
    }
}

/**
 * Do [MissionConfig.count] squats with the phone held against the chest or in both hands. The accelerometer cannot
 * see your legs, so the screen coaches the rhythm (down, then up) and shows when the "down" part was registered.
 */
@Composable
internal fun SquatsMission(config: MissionConfig, onInteraction: () -> Unit, onComplete: () -> Unit) {
    val context = LocalContext.current
    if (!context.hasSensor(Sensor.TYPE_ACCELEROMETER)) return SensorMissingNotice(MissionType.SQUATS)
    val haptics = LocalWakeHaptics.current
    val reduceMotion = rememberReduceMotion()
    val total = config.count.coerceAtLeast(1)
    var reps by rememberSaveable { mutableIntStateOf(0) }
    var finished by rememberSaveable { mutableStateOf(false) }
    val detector = remember(config.difficulty) { SquatDetector.forDifficulty(config.difficulty) }
    var phase by remember { mutableStateOf(SquatDetector.Phase.READY) }
    var firstSampleNs by remember { mutableLongStateOf(-1L) }
    var calibrated by remember { mutableStateOf(false) }

    SensorEventsEffect(Sensor.TYPE_ACCELEROMETER, enabled = !finished) { event ->
        if (firstSampleNs < 0) firstSampleNs = event.timestamp
        if (!calibrated && event.timestamp - firstSampleNs > CALIBRATION_NS) calibrated = true
        val repDone = detector.onSample(event.timestamp, event.values[0], event.values[1], event.values[2])
        if (detector.phase != phase) {
            phase = detector.phase
            if (phase == SquatDetector.Phase.AT_BOTTOM) {
                haptics.tick()
                onInteraction()
            }
        }
        if (repDone) {
            reps++
            onInteraction()
            if (reps >= total) {
                haptics.confirm()
                finished = true
            } else {
                haptics.threshold()
            }
        }
    }
    LaunchedEffect(finished) {
        if (finished) {
            delay(450)
            onComplete()
        }
    }

    val cue = when {
        finished -> SquatCue.Done
        !calibrated -> SquatCue.GetReady
        phase == SquatDetector.Phase.AT_BOTTOM -> SquatCue.Up
        else -> SquatCue.Down
    }
    MissionScaffold(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        MissionPrompt(stringResource(R.string.mission_squats_prompt), body = stringResource(R.string.mission_squats_hint))
        CounterRing(reps.coerceAtMost(total), total, pluralStringResource(R.plurals.mission_squats_caption, total, total), Icons.Filled.FitnessCenter, size = 216.dp)
        AnimatedContent(
            targetState = cue,
            transitionSpec = { (scaleIn(WakeMotion.fastSpatial(), initialScale = 0.7f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.7f) + fadeOut()) },
            label = "squatCue",
        ) { current ->
            Column(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulsingBadge(
                    icon = when (current) {
                        SquatCue.Down -> Icons.Filled.ArrowDownward
                        SquatCue.Up -> Icons.Filled.ArrowUpward
                        SquatCue.GetReady -> Icons.Filled.HourglassTop
                        SquatCue.Done -> Icons.Filled.ArrowUpward
                    },
                    size = 88.dp,
                    pulsing = !reduceMotion && current != SquatCue.Done,
                    container = if (current == SquatCue.Up) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    content = if (current == SquatCue.Up) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(
                        when (current) {
                            SquatCue.Down -> R.string.mission_squats_cue_down
                            SquatCue.Up -> R.string.mission_squats_cue_up
                            SquatCue.GetReady -> R.string.mission_squats_cue_ready
                            SquatCue.Done -> R.string.mission_squats_cue_done
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private enum class SquatCue { GetReady, Down, Up, Done }

private const val CALIBRATION_NS = 1_700_000_000L

/** Acceleration beyond resting gravity that counts as deliberate movement (the phone's own vibration is far below this). */
private const val MOVEMENT_THRESHOLD_MS2 = 4f
