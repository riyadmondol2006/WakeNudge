package com.riyadm.wakenudge.missions.camera

import android.os.SystemClock
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

/** A camera that has delivered nothing for this long, while the screen is in front, is treated as unavailable. */
private const val NO_FRAMES_MS = 8_000L

/**
 * A live camera preview that also delivers every analysed frame to [onFrame] on a background thread (latest frame
 * only, so a slow analysis never builds a backlog). The frame is closed by this view after [onFrame] returns; do not
 * keep it. Everything is bound to the lifecycle and explicitly unbound on dispose, so leaving the screen always
 * releases the camera for the next app - important when the alarm screen is dismissed.
 *
 * A camera that another app is using, or that a device policy disabled, often binds without any error and then simply
 * never delivers a frame. So [onUnavailable] also fires when no frame has arrived for [NO_FRAMES_MS] while the screen
 * is in front, which covers every way a camera can fail to show up.
 *
 * @param analysisSize preferred analysis resolution; the camera picks the closest it supports.
 * @param torchOn whether the torch should be lit (ignored if the phone has none; see [onTorchAvailable]).
 */
@Composable
internal fun CameraAnalysisView(
    modifier: Modifier = Modifier,
    analysisSize: Size = Size(640, 480),
    torchOn: Boolean = false,
    onTorchAvailable: (Boolean) -> Unit = {},
    onUnavailable: () -> Unit = {},
    onFrame: (ImageProxy) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The analysis use case bakes in the display rotation, so it is rebuilt when the screen turns.
    val orientation = LocalConfiguration.current.orientation
    val latestFrameHandler by rememberUpdatedState(onFrame)
    val latestUnavailable by rememberUpdatedState(onUnavailable)
    val latestTorchAvailable by rememberUpdatedState(onTorchAvailable)
    val previewView = remember {
        PreviewView(context).apply {
            // TextureView-based so the preview clips, fades and animates like any other Compose content.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val lastFrameAt = remember { AtomicLong(SystemClock.elapsedRealtime()) }

    DisposableEffect(lifecycleOwner, orientation, analysisSize) {
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(analysisSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { image ->
            lastFrameAt.set(SystemClock.elapsedRealtime())
            try {
                latestFrameHandler(image)
            } catch (_: Exception) {
                // A bad frame must never take the alarm screen down; the next frame will be analysed normally.
            } finally {
                image.close()
            }
        }
        providerFuture.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = providerFuture.get().also { provider = it }
                val selector = when {
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> null
                }
                if (selector == null) {
                    latestUnavailable()
                } else {
                    cameraProvider.unbind(preview, analysis)
                    val bound = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                    lastFrameAt.set(SystemClock.elapsedRealtime())
                    camera = bound
                    latestTorchAvailable(bound.cameraInfo.hasFlashUnit())
                }
            } catch (_: Exception) {
                latestUnavailable()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            camera = null
            executor.shutdown()
        }
    }

    // The screen going off pauses the camera; only time spent in front counts towards "no frames".
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { lastFrameAt.set(SystemClock.elapsedRealtime()) }
    LaunchedEffect(camera) {
        if (camera == null) return@LaunchedEffect
        while (true) {
            delay(1_000)
            val inFront = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (inFront && SystemClock.elapsedRealtime() - lastFrameAt.get() > NO_FRAMES_MS) {
                latestUnavailable()
                break
            }
        }
    }

    LaunchedEffect(camera, torchOn) {
        camera?.takeIf { it.cameraInfo.hasFlashUnit() }?.cameraControl?.enableTorch(torchOn)
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
