package com.riyadm.wakenudge.missions.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.ui.components.WavyLinearProgress
import com.riyadm.wakenudge.ui.components.rememberReduceMotion
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.MorphShape
import com.riyadm.wakenudge.ui.theme.WakeMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Every tappable control in a mission is at least this tall and wide: used half asleep, with thumbs. */
internal val TouchTarget = 56.dp

/** Missions are phone-first; on tablets the content stays a comfortable column instead of stretching. */
internal val MaxContentWidth = 520.dp

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Standard frame of a mission: a centred, optionally scrollable column. */
@Composable
internal fun MissionScaffold(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxSize()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** The instruction line of a mission, announced politely when it changes (round changes, errors, hints). */
@Composable
internal fun MissionPrompt(title: String, modifier: Modifier = Modifier, body: String? = null) {
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Wavy progress with a spoken label ("2 of 3"), announced as it changes. */
@Composable
internal fun MissionProgress(progress: Float, label: String, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), WakeMotion.defaultSpatial(), label = "missionProgress")
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = label
            liveRegion = LiveRegionMode.Polite
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WavyLinearProgress(animated)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * The "that was wrong" response: a short, gentle horizontal shake plus a colour flash. With animations switched off
 * the shake is skipped and only the flash remains. Wrong answers never fail a mission, so this stays soft.
 */
@Stable
internal class ErrorFeedback(private val scope: CoroutineScope, private val reduceMotion: Boolean) {
    private val shake = Animatable(0f)
    private val flashAnimation = Animatable(0f)
    private var job: Job? = null

    /** Current horizontal offset in dp. */
    val shakeDp: Float get() = shake.value

    /** 1 right after an error, easing to 0. */
    val flash: Float get() = flashAnimation.value

    fun trigger() {
        job?.cancel()
        job = scope.launch {
            launch {
                flashAnimation.snapTo(1f)
                flashAnimation.animateTo(0f, tween(500))
            }
            if (!reduceMotion) {
                shake.snapTo(0f)
                for (target in floatArrayOf(-10f, 8f, -6f, 4f, -2f, 0f)) shake.animateTo(target, tween(55))
            }
        }
    }
}

@Composable
internal fun rememberErrorFeedback(): ErrorFeedback {
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    return remember(scope, reduceMotion) { ErrorFeedback(scope, reduceMotion) }
}

internal fun Modifier.errorShake(feedback: ErrorFeedback): Modifier = graphicsLayer { translationX = feedback.shakeDp * density }

/** [base] blended towards the error container while the error flash is showing. */
@Composable
internal fun ErrorFeedback.tint(base: Color): Color = lerp(base, MaterialTheme.colorScheme.errorContainer, flash)

/** A translucent error-container wash for drawing over a control while the error flash is showing. */
@Composable
internal fun ErrorFeedback.overlay(): Color = MaterialTheme.colorScheme.errorContainer.copy(alpha = flash * 0.85f)

/** A cookie-shaped badge holding an icon; it breathes between circle and cookie while [pulsing]. */
@Composable
internal fun PulsingBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    pulsing: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val reduceMotion = rememberReduceMotion()
    val morph = remember { Blobs.morph(Blobs.Circle, Blobs.Cookie) }
    val progress = if (pulsing && !reduceMotion) {
        rememberInfiniteTransition(label = "badge").animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "badgeMorph",
        ).value
    } else {
        1f
    }
    Box(modifier.size(size).clip(MorphShape(morph, progress)).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

internal enum class MissionButtonKind { Filled, Tonal, Text }

/** A mission button: at least 56 dp tall, with a click haptic. */
@Composable
internal fun MissionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: MissionButtonKind = MissionButtonKind.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val haptics = LocalWakeHaptics.current
    val handleClick = { haptics.click(); onClick() }
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, textAlign = TextAlign.Center)
    }
    val sized = modifier.heightIn(min = TouchTarget)
    when (kind) {
        MissionButtonKind.Filled -> Button(handleClick, sized, enabled, content = content)
        MissionButtonKind.Tonal -> FilledTonalButton(handleClick, sized, enabled, content = content)
        MissionButtonKind.Text -> TextButton(handleClick, sized, enabled, content = content)
    }
}

/** A card explaining why something cannot proceed (unsupported hardware, switched-off NFC, not set up) with optional actions. */
@Composable
internal fun MissionNotice(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PulsingBadge(icon, size = 72.dp, pulsing = false)
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

internal fun isPermissionGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Increments each time the host activity resumes; lets a composable re-check things the user may have changed in system settings. */
@Composable
internal fun rememberResumeCount(): Int {
    var count by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { count++ }
    return count
}

/**
 * Shows [content] once [permission] is granted. Until then it explains why the permission is needed, offers to ask,
 * and - after the system stops showing its dialog (permanently denied) - sends the user to the app settings page.
 * [extraActions] lets a mission offer an alternative that needs no permission.
 */
@Composable
internal fun PermissionGate(
    permission: String,
    icon: ImageVector,
    title: String,
    rationale: String,
    modifier: Modifier = Modifier,
    extraActions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val resumeCount = rememberResumeCount()
    var granted by remember { mutableStateOf(isPermissionGranted(context, permission)) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(resumeCount) { granted = isPermissionGranted(context, permission) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        granted = isGranted
        if (!isGranted) {
            // Before the first request shouldShowRequestPermissionRationale is false too, so this is only meaningful after a denial.
            permanentlyDenied = context.findActivity()?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: true
        }
    }
    if (granted) {
        content()
    } else {
        MissionScaffold(modifier) {
            MissionNotice(
                icon = icon,
                title = title,
                body = if (permanentlyDenied) stringResource(R.string.mission_permission_denied_forever, rationale) else rationale,
            ) {
                if (permanentlyDenied) {
                    MissionButton(stringResource(R.string.mission_open_app_settings), { openAppSettings(context) }, Modifier.fillMaxWidth(), icon = Icons.Filled.Settings)
                } else {
                    MissionButton(stringResource(R.string.mission_allow_access), { launcher.launch(permission) }, Modifier.fillMaxWidth())
                }
                extraActions()
            }
        }
    }
}

/**
 * Delivers events of the default sensor of [sensorType] while the screen is started, unregistering the moment it
 * stops so a backgrounded alarm screen does not drain the battery. Does nothing if the phone has no such sensor.
 */
@Composable
internal fun SensorEventsEffect(
    sensorType: Int,
    enabled: Boolean = true,
    samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME,
    onEvent: (SensorEvent) -> Unit,
) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onEvent)
    LifecycleStartEffect(sensorType, enabled, samplingPeriodUs) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor: Sensor? = manager?.getDefaultSensor(sensorType)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = latest(event)
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (enabled && manager != null && sensor != null) manager.registerListener(listener, sensor, samplingPeriodUs)
        onStopOrDispose { manager?.unregisterListener(listener) }
    }
}

internal fun Context.hasSensor(sensorType: Int): Boolean =
    (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(sensorType) != null

/** Full-screen frame of a one-time setup flow: a title row with a close button, then the flow's content. */
@Composable
internal fun SetupFrame(
    title: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onCancel)
    val haptics = LocalWakeHaptics.current
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = MaxContentWidth).fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { haptics.click(); onCancel() }, modifier = Modifier.size(TouchTarget)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.mission_setup_cancel))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 4.dp).semantics { heading() },
                    )
                }
                content()
            }
        }
    }
}
