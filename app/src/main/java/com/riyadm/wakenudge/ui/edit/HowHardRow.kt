package com.riyadm.wakenudge.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Hardness
import com.riyadm.wakenudge.domain.model.HardnessPresets
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics

fun levelName(level: Hardness) = when (level) {
    Hardness.EASY -> R.string.level_easy
    Hardness.NORMAL -> R.string.level_normal
    Hardness.HARD -> R.string.level_hard
    Hardness.NO_MERCY -> R.string.level_no_mercy
    Hardness.CUSTOM -> R.string.level_custom
}

fun levelDescription(level: Hardness) = when (level) {
    Hardness.EASY -> R.string.level_easy_desc
    Hardness.NORMAL -> R.string.level_normal_desc
    Hardness.HARD -> R.string.level_hard_desc
    Hardness.NO_MERCY -> R.string.level_no_mercy_desc
    Hardness.CUSTOM -> R.string.level_custom_desc
}

/**
 * "How hard?": the four levels, each with what it does in one line, as one radio group. Every description is shown, not
 * only the chosen one, so the choice can be made by reading once. Custom appears only for an alarm that is Custom, and
 * cannot be picked: it is what an alarm becomes when its details are changed.
 *
 * [stepsLeft]: under Hard and No mercy, how many Protection steps are still to do (0 is fully protected); null hides the line.
 */
@Composable
fun HowHardCard(
    selected: Hardness,
    enabled: Boolean,
    onPick: (Hardness) -> Unit,
    modifier: Modifier = Modifier,
    stepsLeft: Int? = null,
    onOpenProtection: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                stringResource(R.string.level_title),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).semantics { heading() },
            )
            Column(Modifier.selectableGroup()) {
                val shown = if (selected == Hardness.CUSTOM) HardnessPresets.levels + Hardness.CUSTOM else HardnessPresets.levels
                shown.forEach { level -> LevelOption(level, level == selected, enabled && level != Hardness.CUSTOM, onPick) }
            }
            if (stepsLeft != null && (selected == Hardness.HARD || selected == Hardness.NO_MERCY) && onOpenProtection != null) {
                ProtectionStatus(stepsLeft, onOpenProtection)
            }
        }
    }
}

@Composable
private fun LevelOption(level: Hardness, selected: Boolean, enabled: Boolean, onPick: (Hardness) -> Unit) {
    val haptics = LocalWakeHaptics.current
    val alpha = if (enabled || selected) 1f else 0.6f
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            // The row is the radio button: the name and its line are read together, with the selected state.
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                if (!selected) {
                    haptics.tick()
                    onPick(level)
                }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled || selected)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                stringResource(levelName(level)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(
                stringResource(levelDescription(level)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}

@Composable
private fun ProtectionStatus(stepsLeft: Int, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val done = stepsLeft <= 0
    val text = if (done) stringResource(R.string.level_status_done) else pluralStringResource(R.plurals.level_status_left, stepsLeft, stepsLeft)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.level_status_open), onClick = onOpen)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (done) Icons.Outlined.VerifiedUser else Icons.Outlined.ErrorOutline, null,
            tint = if (done) scheme.primary else scheme.error, modifier = Modifier.size(24.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (done) scheme.onSurface else scheme.error, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant)
    }
}
