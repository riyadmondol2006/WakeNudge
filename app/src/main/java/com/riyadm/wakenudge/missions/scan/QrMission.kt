package com.riyadm.wakenudge.missions.scan

import android.os.SystemClock
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.missions.camera.CameraAnalysisView
import com.riyadm.wakenudge.missions.camera.FrameSampling
import com.riyadm.wakenudge.missions.ui.MissionButton
import com.riyadm.wakenudge.missions.ui.MissionButtonKind
import com.riyadm.wakenudge.missions.ui.MissionNotice
import com.riyadm.wakenudge.missions.ui.MissionWayOut
import com.riyadm.wakenudge.missions.ui.MissionProgress
import com.riyadm.wakenudge.missions.ui.MissionPrompt
import com.riyadm.wakenudge.missions.ui.MissionScaffold
import com.riyadm.wakenudge.missions.ui.PermissionGate
import com.riyadm.wakenudge.missions.ui.SetupFrame
import com.riyadm.wakenudge.missions.ui.TouchTarget
import com.riyadm.wakenudge.missions.ui.errorShake
import com.riyadm.wakenudge.missions.ui.rememberErrorFeedback
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.WakeMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Camera view that decodes QR codes and barcodes in every frame and reports each hit to [onCode] on the main thread
 * (the same code repeats every frame while it stays in view; callers apply their own cooldown). Handles the camera
 * permission, an unavailable camera, and a torch toggle for dark rooms.
 *
 * @param success when true a check mark is shown over the preview.
 */
@Composable
internal fun CodeScannerView(
    modifier: Modifier = Modifier,
    success: Boolean = false,
    onCode: (DecodedCode) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latestOnCode by rememberUpdatedState(onCode)
    val decoder = remember { CodeDecoder() }
    var torchOn by rememberSaveable { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }

    PermissionGate(
        permission = android.Manifest.permission.CAMERA,
        icon = Icons.Filled.QrCodeScanner,
        title = stringResource(R.string.mission_camera_permission_title),
        rationale = stringResource(R.string.mission_camera_permission_scan),
        modifier = modifier,
    ) {
        if (cameraFailed) {
            MissionNotice(Icons.Filled.NoPhotography, stringResource(R.string.mission_camera_failed_title), stringResource(R.string.mission_camera_failed_body), modifier) {
                MissionWayOut()
            }
        } else {
            Box(modifier.clip(MaterialTheme.shapes.extraLarge).background(Color.Black)) {
                CameraAnalysisView(
                    modifier = Modifier.fillMaxSize(),
                    analysisSize = Size(1280, 720),
                    torchOn = torchOn,
                    onTorchAvailable = { torchAvailable = it },
                    onUnavailable = { cameraFailed = true },
                ) { image ->
                    val code = synchronized(decoder) {
                        decoder.decode(FrameSampling.luminance(image), image.width, image.height, image.imageInfo.rotationDegrees)
                    }
                    if (code != null) scope.launch { latestOnCode(code) }
                }
                Viewfinder(Modifier.fillMaxSize(), if (success) MaterialTheme.colorScheme.tertiary else Color.White)
                if (torchAvailable) {
                    IconButton(onClick = { torchOn = !torchOn }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(TouchTarget)) {
                        Icon(
                            if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                            contentDescription = stringResource(if (torchOn) R.string.mission_torch_off else R.string.mission_torch_on),
                            tint = Color.White,
                        )
                    }
                }
                AnimatedVisibility(success, Modifier.align(Alignment.Center), enter = scaleIn(WakeMotion.bouncy()), exit = scaleOut()) {
                    Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiary, modifier = Modifier.size(56.dp))
                    }
                }
            }
        }
    }
}

/** Four corner brackets around the centre of the preview, showing where to hold the code. */
@Composable
private fun Viewfinder(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val side = minOf(size.width, size.height) * 0.62f
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        val arm = side * 0.2f
        val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            val path = Path().apply {
                moveTo(x, y + dy * arm)
                lineTo(x, y)
                lineTo(x + dx * arm, y)
            }
            drawPath(path, color, style = stroke)
        }
        corner(left, top, 1f, 1f)
        corner(left + side, top, -1f, 1f)
        corner(left, top + side, 1f, -1f)
        corner(left + side, top + side, -1f, -1f)
    }
}

/** Scan the registered code (or barcode) [MissionConfig.count] times. Only an exact match counts; other codes get a gentle nudge. */
@Composable
internal fun QrMission(config: MissionConfig, onInteraction: () -> Unit, onComplete: () -> Unit) {
    val haptics = LocalWakeHaptics.current
    val feedback = rememberErrorFeedback()
    val expected = config.params[MissionConfig.PARAM_CODE].orEmpty()
    val label = config.params[MissionConfig.PARAM_CODE_LABEL].orEmpty()
    val total = config.count.coerceAtLeast(1)
    var scanned by rememberSaveable { mutableIntStateOf(0) }
    var finished by rememberSaveable { mutableStateOf(false) }
    var lastResultAt by remember { mutableLongStateOf(0L) }
    var wrong by remember { mutableStateOf(false) }
    var justFound by remember { mutableStateOf(false) }

    LaunchedEffect(finished) {
        if (finished) {
            delay(700)
            onComplete()
        }
    }
    LaunchedEffect(justFound) {
        if (justFound) {
            delay(1200)
            justFound = false
        }
    }

    fun handle(code: DecodedCode) {
        val now = SystemClock.elapsedRealtime()
        if (finished || now - lastResultAt < COOLDOWN_MS) return
        lastResultAt = now
        onInteraction()
        if (code.text == expected) {
            wrong = false
            haptics.confirm()
            scanned++
            justFound = true
            if (scanned >= total) finished = true
        } else {
            wrong = true
            haptics.reject()
            feedback.trigger()
        }
    }

    MissionScaffold(scrollable = false, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (total > 1) MissionProgress(scanned.toFloat() / total, stringResource(R.string.mission_scan_progress, minOf(scanned + 1, total), total))
        MissionPrompt(
            stringResource(R.string.mission_scan_prompt),
            body = if (label.isNotBlank()) stringResource(R.string.mission_scan_target, label) else stringResource(R.string.mission_scan_target_unknown),
        )
        CodeScannerView(Modifier.weight(1f).fillMaxWidth().errorShake(feedback), success = justFound || finished, onCode = ::handle)
        Text(
            when {
                finished || justFound -> stringResource(R.string.mission_scan_found)
                wrong -> stringResource(R.string.mission_scan_wrong)
                else -> stringResource(R.string.mission_scan_searching)
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = if (wrong && !justFound) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private const val COOLDOWN_MS = 1500L

/** One-time registration: scan any QR code or barcode, confirm it, and store it (with a readable label) in the config. */
@Composable
internal fun QrSetup(config: MissionConfig, onSaved: (MissionConfig) -> Unit, onCancel: () -> Unit) {
    val haptics = LocalWakeHaptics.current
    var text by rememberSaveable { mutableStateOf<String?>(null) }
    var formatName by rememberSaveable { mutableStateOf("") }

    SetupFrame(stringResource(R.string.mission_setup_qr_title), onCancel) {
        val captured = text
        if (captured == null) {
            MissionPrompt(stringResource(R.string.mission_setup_qr_prompt), body = stringResource(R.string.mission_setup_qr_hint))
            CodeScannerView(Modifier.weight(1f).fillMaxWidth(), onCode = { code ->
                // The same code arrives on every frame until the confirm screen replaces the scanner.
                if (text == null) {
                    haptics.confirm()
                    text = code.text
                    formatName = CodeLabels.formatName(code.format)
                }
            })
        } else {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                MissionNotice(
                    icon = Icons.Filled.QrCodeScanner,
                    title = stringResource(R.string.mission_setup_qr_found, formatName),
                    body = CodeLabels.shorten(captured).let { stringResource(R.string.mission_setup_qr_found_body, it) },
                ) {
                    MissionButton(stringResource(R.string.mission_setup_use_code), {
                        val label = "$formatName · ${CodeLabels.shorten(captured)}"
                        onSaved(config.copy(params = config.params + (MissionConfig.PARAM_CODE to captured) + (MissionConfig.PARAM_CODE_LABEL to label)))
                    }, Modifier.fillMaxWidth())
                    MissionButton(stringResource(R.string.mission_setup_scan_another), { text = null }, Modifier.fillMaxWidth(), kind = MissionButtonKind.Tonal)
                }
            }
        }
    }
}
