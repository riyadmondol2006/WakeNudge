package com.riyadm.wakenudge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.MorphShape
import com.riyadm.wakenudge.ui.theme.WakeMotion
import com.riyadm.wakenudge.ui.theme.pressScale
import com.riyadm.wakenudge.ui.theme.rememberInteractionSource
import kotlin.math.roundToInt

private val SwitchMorph by lazy { Blobs.morph(Blobs.Circle, Blobs.Cookie) }

/**
 * Switch whose thumb is a circle when off and morphs into a rubbery cookie when on. A real, accessible
 * [Role.Switch] with haptics, 48 dp minimum touch height, and no motion beyond a spring.
 */
@Composable
fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalWakeHaptics.current
    val scheme = MaterialTheme.colorScheme
    val morph by animateFloatAsState(if (checked) 1f else 0f, WakeMotion.defaultEffects(), label = "switchMorph")
    val travel by animateFloatAsState(if (checked) 1f else 0f, WakeMotion.fastSpatial(), label = "switchTravel")
    val track by animateColorAsState(if (checked) scheme.primary else scheme.surfaceContainerHighest, WakeMotion.defaultEffects(), label = "track")
    val thumb by animateColorAsState(if (checked) scheme.onPrimary else scheme.outline, WakeMotion.defaultEffects(), label = "thumb")
    val width = 64.dp
    val height = 36.dp
    val thumbSize = lerpDp(16.dp, 26.dp, morph)
    val pad = 5.dp
    val interaction = rememberInteractionSource()

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .pressScale(interaction, 0.92f)
            .toggleable(
                value = checked,
                enabled = enabled && onCheckedChange != null,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
            ) {
                haptics.toggle(it)
                onCheckedChange?.invoke(it)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width, height)
                .clip(CircleShape)
                .background(track.copy(alpha = if (enabled) 1f else 0.5f))
                .then(if (checked) Modifier else Modifier.border(2.dp, scheme.outline.copy(alpha = if (enabled) 1f else 0.5f), CircleShape)),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        // Rests a little further in when off (small thumb inside the outline), tight to the edge when on.
                        val start = lerpDp(8.dp, pad, morph).toPx()
                        val end = width.toPx() - pad.toPx() - thumbSize.toPx()
                        IntOffset((start + (end - start) * travel).roundToInt(), 0)
                    }
                    .size(thumbSize)
                    .clip(MorphShape(SwitchMorph, morph))
                    .background(thumb.copy(alpha = if (enabled) 1f else 0.6f)),
            )
        }
    }
}

private fun lerpDp(a: Dp, b: Dp, t: Float): Dp = a + (b - a) * t

private val DayMorph by lazy { Blobs.morph(Blobs.Circle, Blobs.Cookie) }

/** A weekday toggle: circle at rest, cookie when selected. */
@Composable
fun DayChip(
    label: String,
    fullName: String,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalWakeHaptics.current
    val morph by animateFloatAsState(if (selected) 1f else 0f, WakeMotion.defaultEffects(), label = "dayMorph")
    val bg by animateColorAsState(if (selected) scheme.primary else scheme.surfaceContainerHighest, WakeMotion.defaultEffects(), label = "dayBg")
    val fg by animateColorAsState(if (selected) scheme.onPrimary else scheme.onSurfaceVariant, WakeMotion.defaultEffects(), label = "dayFg")
    val interaction = rememberInteractionSource()
    Box(
        modifier = modifier
            .size(44.dp)
            .pressScale(interaction, 0.88f)
            .clip(MorphShape(DayMorph, morph))
            .background(bg)
            .toggleable(selected, enabled = enabled, role = Role.Checkbox, interactionSource = interaction, indication = null) {
                haptics.toggle(it)
                onToggle(it)
            }
            .semantics { contentDescription = fullName; stateDescription = if (selected) "On" else "Off" },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

// ---- grouped settings list ---------------------------------------------------------------------------------

class SettingsGroupScope {
    internal val items = mutableListOf<@Composable (Shape) -> Unit>()
    fun item(content: @Composable (shape: Shape) -> Unit) {
        items += content
    }
}

private val BigCorner = 28.dp
private val SmallCorner = 6.dp

internal fun groupShape(index: Int, count: Int): Shape = when {
    count == 1 -> RoundedCornerShape(BigCorner)
    index == 0 -> RoundedCornerShape(BigCorner, BigCorner, SmallCorner, SmallCorner)
    index == count - 1 -> RoundedCornerShape(SmallCorner, SmallCorner, BigCorner, BigCorner)
    else -> RoundedCornerShape(SmallCorner)
}

/** A column of rows that read as one segmented card: big outer corners, tight inner ones. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: SettingsGroupScope.() -> Unit) {
    val scope = SettingsGroupScope().apply(content)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        scope.items.forEachIndexed { i, item -> item(groupShape(i, scope.items.size)) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A generic row inside a [SettingsGroup]. */
@Composable
fun SettingRow(
    title: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.5f
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .then(if (onClick != null && enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), modifier = Modifier.padding(end = 16.dp).size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(12.dp))
                    trailing()
                }
            }
            if (below != null) below()
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val haptics = LocalWakeHaptics.current
    SettingRow(
        title = title, subtitle = subtitle, icon = icon, shape = shape, enabled = enabled, modifier = modifier,
        onClick = { haptics.toggle(!checked); onCheckedChange(!checked) },
        trailing = { ExpressiveSwitch(checked, null, enabled = enabled) },
        // The whole row is the click target; the switch is decorative, so expose one toggle to accessibility.
    )
}

/** A slider with a visible value and detent haptics. */
@Composable
fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    shape: Shape,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    subtitle: String? = null,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val haptics = LocalWakeHaptics.current
    var last by remember { mutableFloatStateOf(value) }
    SettingRow(
        title = title, subtitle = subtitle, shape = shape, enabled = enabled, modifier = modifier,
        trailing = { Text(valueText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) },
        below = {
            Slider(
                value = value,
                onValueChange = {
                    if (steps > 0 && it != last) haptics.tick()
                    last = it
                    onValueChange(it)
                },
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                onValueChangeFinished = onValueChangeFinished,
                modifier = Modifier.semantics { contentDescription = "$title, $valueText" },
            )
        },
    )
}

/** − value + stepper for small integers (snooze minutes, counts). */
@Composable
fun StepperRow(
    title: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    canMinus: Boolean = true,
    canPlus: Boolean = true,
    enabled: Boolean = true,
) {
    val haptics = LocalWakeHaptics.current
    SettingRow(
        title = title, subtitle = subtitle, shape = shape, enabled = enabled, modifier = modifier,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { haptics.tick(); onMinus() }, enabled = enabled && canMinus) {
                    Icon(Icons.Filled.Remove, contentDescription = "Decrease $title")
                }
                Text(valueText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.defaultMinSize(minWidth = 56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { haptics.tick(); onPlus() }, enabled = enabled && canPlus) {
                    Icon(Icons.Filled.Add, contentDescription = "Increase $title")
                }
            }
        },
    )
}

/** Filled tonal container used for hero blocks (next-alarm summary, stats). */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    shape: Shape = RoundedCornerShape(36.dp),
    content: @Composable () -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = color, shape = shape) {
        Box(Modifier.padding(24.dp)) { content() }
    }
}

internal fun outlinedBorder(color: Color) = BorderStroke(1.dp, color)

@Composable
internal fun colorLerp(a: Color, b: Color, t: Float): Color = lerp(a, b, t)

@Composable
fun VerticalGap(height: Dp = 12.dp) = Spacer(Modifier.height(height))
