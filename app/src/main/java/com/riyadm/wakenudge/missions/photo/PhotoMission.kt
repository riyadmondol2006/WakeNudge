package com.riyadm.wakenudge.missions.photo

import android.os.SystemClock
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.missions.MissionParams
import com.riyadm.wakenudge.missions.camera.CameraAnalysisView
import com.riyadm.wakenudge.missions.camera.FrameSampling
import com.riyadm.wakenudge.missions.camera.Thumbnails
import com.riyadm.wakenudge.missions.ui.MissionButton
import com.riyadm.wakenudge.missions.ui.MissionButtonKind
import com.riyadm.wakenudge.missions.ui.MissionNotice
import com.riyadm.wakenudge.missions.ui.MissionWayOut
import com.riyadm.wakenudge.missions.ui.MissionPrompt
import com.riyadm.wakenudge.missions.ui.MissionProgress
import com.riyadm.wakenudge.missions.ui.MissionScaffold
import com.riyadm.wakenudge.missions.ui.PermissionGate
import com.riyadm.wakenudge.missions.ui.SetupFrame
import com.riyadm.wakenudge.missions.ui.TouchTarget
import com.riyadm.wakenudge.missions.ui.errorShake
import com.riyadm.wakenudge.missions.ui.rememberErrorFeedback
import com.riyadm.wakenudge.ui.components.WavyLinearProgress
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Side of the square the camera frame is squashed to before the signature is computed (the signature itself is 32x32). */
private const val SIGNATURE_SAMPLE = 64

/** Longest side of the aspect-correct sample that becomes the editor thumbnail. */
private const val THUMBNAIL_SAMPLE = 80

/**
 * Point the camera at the registered place. Every analysed frame is scored against the reference signature; the meter
 * shows the live match, and the mission passes once the score stays above the difficulty's threshold for a second.
 * Nothing is recorded or stored: frames are reduced to a small signature in memory and dropped.
 */
@Composable
internal fun PhotoMission(config: MissionConfig, onInteraction: () -> Unit, onComplete: () -> Unit) {
    val haptics = LocalWakeHaptics.current
    val scope = rememberCoroutineScope()
    val reference = remember(config.params[MissionConfig.PARAM_SIGNATURE]) { config.params[MissionConfig.PARAM_SIGNATURE]?.let { PhotoSignature.decode(it) } }
    if (reference == null) {
        MissionScaffold {
            MissionNotice(Icons.Filled.NoPhotography, stringResource(R.string.mission_photo_broken_title), stringResource(R.string.mission_photo_broken_body)) {
                MissionWayOut()
            }
        }
        return
    }
    val threshold = remember(config.difficulty) { PhotoMatcher.thresholdFor(config.difficulty) }
    val thumbnail = remember(config.params[MissionParams.THUMB]) { Thumbnails.decode(config.params[MissionParams.THUMB]) }
    val total = config.count.coerceAtLeast(1)
    val latestOnInteraction by rememberUpdatedState(onInteraction)
    var found by rememberSaveable { mutableIntStateOf(0) }
    var finished by rememberSaveable { mutableStateOf(false) }
    var score by remember { mutableFloatStateOf(0f) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var torchOn by rememberSaveable { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    val hold = remember(threshold) { MatchHold(threshold) }
    val passing = remember { AtomicBoolean(false) }
    val lastInteractionNs = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    LaunchedEffect(finished) {
        if (finished) {
            delay(800)
            onComplete()
        }
    }

    MissionScaffold(scrollable = false, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (total > 1) MissionProgress(found.toFloat() / total, stringResource(R.string.mission_photo_progress, minOf(found + 1, total), total))
        // Without a camera the hint about holding still is noise, and its lines are better spent on the notice.
        MissionPrompt(stringResource(R.string.mission_photo_prompt), body = if (cameraFailed) null else stringResource(R.string.mission_photo_hint))
        PermissionGate(
            permission = android.Manifest.permission.CAMERA,
            icon = Icons.Filled.PhotoCamera,
            title = stringResource(R.string.mission_camera_permission_title),
            rationale = stringResource(R.string.mission_camera_permission_photo),
            modifier = Modifier.weight(1f),
        ) {
            if (cameraFailed) {
                MissionNotice(
                    Icons.Filled.NoPhotography, stringResource(R.string.mission_camera_failed_title), stringResource(R.string.mission_camera_failed_body),
                    Modifier.weight(1f), scrollable = true,
                ) {
                    MissionWayOut()
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(Color.Black)) {
                    CameraAnalysisView(
                        modifier = Modifier.fillMaxSize(),
                        analysisSize = Size(640, 480),
                        torchOn = torchOn,
                        onTorchAvailable = { torchAvailable = it },
                        onUnavailable = { cameraFailed = true },
                    ) { image ->
                        if (passing.get()) return@CameraAnalysisView
                        val now = SystemClock.elapsedRealtimeNanos()
                        val frame = PhotoSignatureBuilder.build(FrameSampling.rgb(image, SIGNATURE_SAMPLE, SIGNATURE_SAMPLE))
                        val similarity = PhotoMatcher.similarity(reference, frame)
                        val satisfied = hold.update(now, similarity)
                        score = similarity
                        holdProgress = hold.progress
                        // Only count it as the user doing something once the camera is looking at something resembling the place.
                        if (similarity >= threshold * 0.6f && now - lastInteractionNs.get() > 1_000_000_000L) {
                            lastInteractionNs.set(now)
                            scope.launch { latestOnInteraction() }
                        }
                        if (satisfied && passing.compareAndSet(false, true)) {
                            scope.launch {
                                haptics.confirm()
                                found++
                                if (found >= total) {
                                    finished = true
                                } else {
                                    hold.reset()
                                    delay(1200)
                                    passing.set(false)
                                }
                            }
                        }
                    }
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail,
                            contentDescription = stringResource(R.string.mission_photo_reference_description),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                                .size(width = 72.dp, height = 96.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .border(2.dp, Color.White, RoundedCornerShape(16.dp)),
                        )
                    }
                    if (torchAvailable) {
                        IconButton(onClick = { torchOn = !torchOn }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(TouchTarget)) {
                            Icon(
                                if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                                contentDescription = stringResource(if (torchOn) R.string.mission_torch_off else R.string.mission_torch_on),
                                tint = Color.White,
                            )
                        }
                    }
                    MatchMeter(
                        score = score,
                        threshold = threshold,
                        holdProgress = holdProgress,
                        success = finished,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                    )
                }
            }
        }
    }
}

/** Live "how close is this view" bar with the pass mark, over a scrim so it reads on any camera image. */
@Composable
private fun MatchMeter(score: Float, threshold: Float, holdProgress: Float, success: Boolean, modifier: Modifier = Modifier) {
    val percent = (score * 100).roundToInt()
    val needed = (threshold * 100).roundToInt()
    val reached = score >= threshold
    val status = when {
        success -> stringResource(R.string.mission_photo_matched)
        reached -> stringResource(R.string.mission_photo_hold)
        else -> stringResource(R.string.mission_photo_searching)
    }
    val description = stringResource(R.string.mission_photo_meter_description, percent, needed, status)
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // Only the status is announced as it changes (searching, hold, matched); the percentage moves with every
            // camera frame and would keep a screen reader talking, so it is read when focused, not pushed.
            Text(
                status, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                stringResource(R.string.mission_photo_percent, percent), style = MaterialTheme.typography.titleMedium, color = Color.White,
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
        Box(Modifier.fillMaxWidth()) {
            val barColor = if (reached) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
            WavyLinearProgress(
                progress = if (success) 1f else if (reached) 0.5f + 0.5f * holdProgress else score / threshold * 0.5f,
                color = barColor,
                trackColor = Color.White.copy(alpha = 0.25f),
            )
        }
        Text(
            stringResource(R.string.mission_photo_needed, needed),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- setup --------------------------------------------------------------------------------------------------------

private enum class CaptureState { Live, TooPlain }

/**
 * One-time registration: frame the place (bathroom sink, coffee machine...), press the shutter, review, confirm. Only
 * a compact signature and a tiny thumbnail are stored in the alarm; the photo itself is never saved anywhere.
 */
@Composable
internal fun PhotoSetup(config: MissionConfig, onSaved: (MissionConfig) -> Unit, onCancel: () -> Unit) {
    val haptics = LocalWakeHaptics.current
    val scope = rememberCoroutineScope()
    val feedback = rememberErrorFeedback()
    var signature by rememberSaveable { mutableStateOf<String?>(null) }
    var thumb by rememberSaveable { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf(CaptureState.Live) }
    var torchOn by rememberSaveable { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    val captureRequested = remember { AtomicBoolean(false) }
    val reviewImage = remember(thumb) { Thumbnails.decode(thumb) }

    SetupFrame(stringResource(R.string.mission_setup_photo_title), onCancel) {
        val captured = signature
        if (captured != null) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                MissionPrompt(stringResource(R.string.mission_setup_photo_review), body = stringResource(R.string.mission_setup_photo_review_hint))
                if (reviewImage != null) {
                    Image(
                        bitmap = reviewImage,
                        contentDescription = stringResource(R.string.mission_photo_reference_description),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(0.6f).clip(MaterialTheme.shapes.extraLarge),
                    )
                }
                MissionButton(stringResource(R.string.mission_setup_photo_use), {
                    onSaved(
                        config.copy(
                            params = config.params + (MissionConfig.PARAM_SIGNATURE to captured) +
                                (thumb?.let { mapOf(MissionParams.THUMB to it) } ?: emptyMap()),
                        ),
                    )
                }, Modifier.fillMaxWidth())
                MissionButton(stringResource(R.string.mission_setup_photo_retake), { signature = null; thumb = null; state = CaptureState.Live }, Modifier.fillMaxWidth(), kind = MissionButtonKind.Tonal)
            }
        } else {
            MissionPrompt(stringResource(R.string.mission_setup_photo_prompt), body = stringResource(R.string.mission_setup_photo_hint))
            PermissionGate(
                permission = android.Manifest.permission.CAMERA,
                icon = Icons.Filled.PhotoCamera,
                title = stringResource(R.string.mission_camera_permission_title),
                rationale = stringResource(R.string.mission_camera_permission_photo),
                modifier = Modifier.weight(1f),
            ) {
                if (cameraFailed) {
                    MissionNotice(Icons.Filled.NoPhotography, stringResource(R.string.mission_camera_failed_title), stringResource(R.string.mission_camera_failed_body))
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f).fillMaxWidth().errorShake(feedback).clip(MaterialTheme.shapes.extraLarge).background(Color.Black)) {
                            CameraAnalysisView(
                                modifier = Modifier.fillMaxSize(),
                                analysisSize = Size(640, 480),
                                torchOn = torchOn,
                                onTorchAvailable = { torchAvailable = it },
                                onUnavailable = { cameraFailed = true },
                            ) { image ->
                                if (!captureRequested.compareAndSet(true, false)) return@CameraAnalysisView
                                val sample = FrameSampling.rgb(image, SIGNATURE_SAMPLE, SIGNATURE_SAMPLE)
                                if (PhotoSignatureBuilder.detailOf(sample) < PhotoSignatureBuilder.MIN_DETAIL) {
                                    scope.launch {
                                        haptics.reject()
                                        feedback.trigger()
                                        state = CaptureState.TooPlain
                                    }
                                } else {
                                    val (uprightW, uprightH) = FrameSampling.uprightSize(image)
                                    val scale = THUMBNAIL_SAMPLE.toFloat() / maxOf(uprightW, uprightH)
                                    val preview = FrameSampling.rgb(image, (uprightW * scale).roundToInt().coerceAtLeast(8), (uprightH * scale).roundToInt().coerceAtLeast(8))
                                    val encodedSignature = PhotoSignatureBuilder.build(sample).encode()
                                    val encodedThumb = Thumbnails.encode(preview)
                                    scope.launch {
                                        haptics.confirm()
                                        thumb = encodedThumb
                                        signature = encodedSignature
                                    }
                                }
                            }
                            if (torchAvailable) {
                                IconButton(onClick = { torchOn = !torchOn }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(TouchTarget)) {
                                    Icon(
                                        if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                                        contentDescription = stringResource(if (torchOn) R.string.mission_torch_off else R.string.mission_torch_on),
                                        tint = Color.White,
                                    )
                                }
                            }
                        }
                        Text(
                            if (state == CaptureState.TooPlain) stringResource(R.string.mission_setup_photo_too_plain) else stringResource(R.string.mission_setup_photo_tip),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state == CaptureState.TooPlain) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        MissionButton(
                            stringResource(R.string.mission_setup_photo_capture),
                            { state = CaptureState.Live; captureRequested.set(true) },
                            Modifier.fillMaxWidth(),
                            icon = Icons.Filled.PhotoCamera,
                        )
                    }
                }
            }
        }
    }
}
