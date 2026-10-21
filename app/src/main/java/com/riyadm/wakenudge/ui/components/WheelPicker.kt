package com.riyadm.wakenudge.ui.components

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.ui.theme.ClockType
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/**
 * A snapping vertical wheel. Values are shown big at the centre and shrink/fade away from it, a detent haptic
 * fires on every step, and it wraps around when [loop] is set. For screen readers it is a single adjustable
 * control with "increase"/"decrease" actions instead of a scrolling list.
 */
@Composable
fun WheelPicker(
    count: Int,
    selected: Int,
    onSelected: (Int) -> Unit,
    label: (Int) -> String,
    description: String,
    modifier: Modifier = Modifier,
    loop: Boolean = true,
    itemHeight: Dp = 76.dp,
    textStyle: TextStyle = ClockType.WheelItem,
    width: Dp = 104.dp,
) {
    val haptics = LocalWakeHaptics.current
    val visible = 3
    val repeat = if (loop) 300 else 1
    val total = count * repeat
    val start = if (loop) (repeat / 2) * count + selected else selected
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }
    val onSelectedState = rememberUpdatedState(onSelected)

    val position by remember(state, itemPx) {
        derivedStateOf { state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / itemPx }
    }
    val centerIndex by remember(state, itemPx) { derivedStateOf { position.roundToInt().coerceIn(0, total - 1) } }

    // User scrolling -> report the value under the centre, with a detent haptic.
    LaunchedEffect(state, count) {
        var first = true
        snapshotFlow { centerIndex }.distinctUntilChanged().collect { index ->
            if (first) {
                first = false
                return@collect
            }
            haptics.tick()
            onSelectedState.value(index % count)
        }
    }
    // Programmatic change (typed time, accessibility buttons) -> glide to the nearest matching row.
    LaunchedEffect(selected, count) {
        if (!state.isScrollInProgress && centerIndex % count != selected) {
            val current = centerIndex
            val delta = ((selected - current % count) + count) % count
            val target = if (loop && delta > count / 2) current + delta - count else current + delta
            state.animateScrollToItem(target.coerceIn(0, total - 1))
        }
    }

    Box(
        modifier = modifier
            .width(width)
            .height(itemHeight * visible)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = label(selected)
                customActions = listOf(
                    CustomAccessibilityAction("Increase $description") { onSelected((selected + 1) % count); true },
                    CustomAccessibilityAction("Decrease $description") { onSelected((selected - 1 + count) % count); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = itemHeight * (visible / 2)),
            userScrollEnabled = true,
        ) {
            items(total, key = { it }) { i ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .graphicsLayer {
                            val distance = abs(i - position).coerceIn(0f, 1.6f) / 1.6f
                            val scale = 1f - 0.42f * distance
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - 0.72f * distance
                        }
                        .semantics { contentDescription = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(i % count), style = textStyle, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
