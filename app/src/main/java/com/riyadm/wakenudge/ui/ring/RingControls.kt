package com.riyadm.wakenudge.ui.ring

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.PolygonShape
import com.riyadm.wakenudge.ui.theme.WakeMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Slide-to-dismiss: a deliberate gesture that is very hard to do by accident while half asleep. For screen readers
 * (and switch users) the track exposes an ordinary click action, so no precise gesture is ever required.
 */
@Composable
fun SlideToDismiss(
    label: String,
    actionDescription: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalWakeHaptics.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    // In right-to-left languages the thumb starts on the right and travels left; [offset] is always "progress".
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val thumb = 64.dp
    val inset = 4.dp

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .semantics {
                role = Role.Button
                contentDescription = actionDescription
                onClick(label = actionDescription) { onDismiss(); true }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val maxOffset = with(density) { (maxWidth - thumb - inset * 2).toPx() }.coerceAtLeast(1f)
        // Two lines at most, and the track grows to hold them: a large font must not cut the instruction off.
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = thumb + 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                .graphicsLayer { alpha = (1f - offset.value / maxOffset * 1.4f).coerceIn(0f, 1f) },
        )
        Box(
            Modifier
                .padding(inset)
                .offset { IntOffset(direction * offset.value.roundToInt(), 0) }
                .size(thumb)
                .clip(PolygonShape(Blobs.Cookie))
                .background(MaterialTheme.colorScheme.onPrimary)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val next = (offset.value + direction * delta).coerceIn(0f, maxOffset)
                        scope.launch { offset.snapTo(next) }
                        val nowArmed = next >= maxOffset * 0.8f
                        if (nowArmed && !armed) haptics.threshold()
                        armed = nowArmed
                    },
                    onDragStopped = {
                        if (offset.value >= maxOffset * 0.8f) {
                            haptics.confirm()
                            onDismiss()
                        }
                        armed = false
                        scope.launch { offset.animateTo(0f, WakeMotion.bouncy()) }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Hold-to-confirm button: fills while pressed and fires when the hold completes. Releasing early cancels. Screen
 * readers get a plain click action (see [SlideToDismiss]).
 */
@Composable
fun HoldButton(
    label: String,
    holdSeconds: Int,
    actionDescription: String,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    onAccessibilityClick: () -> Unit = onComplete,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    fill: Color = MaterialTheme.colorScheme.primaryContainer,
    height: androidx.compose.ui.unit.Dp = 72.dp,
) {
    val haptics = LocalWakeHaptics.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(CircleShape)
            .background(container)
            .drawBehind {
                // The fill grows from the start edge (the right one in right-to-left languages).
                val width = size.width * progress.value.coerceIn(0f, 1f)
                val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                drawRect(fill, topLeft = Offset(left, 0f), size = Size(width, size.height))
            }
            .semantics {
                role = Role.Button
                contentDescription = actionDescription
                onClick(label = actionDescription) { onAccessibilityClick(); true }
            }
            .pointerInput(holdSeconds) {
                detectTapGestures(
                    onPress = {
                        haptics.click()
                        val job = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween((holdSeconds * 1000).coerceAtLeast(300), easing = LinearEasing))
                            haptics.confirm()
                            onComplete()
                        }
                        tryAwaitRelease()
                        if (progress.value < 1f) {
                            job.cancel()
                            scope.launch { progress.animateTo(0f, WakeMotion.fastEffects()) }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (progress.value >= 1f) {
            Icon(Icons.Filled.Check, null, tint = content)
        } else {
            Text(
                label, style = MaterialTheme.typography.titleMedium, color = content, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

