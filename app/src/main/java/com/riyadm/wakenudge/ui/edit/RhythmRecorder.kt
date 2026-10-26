package com.riyadm.wakenudge.ui.edit

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.engine.audio.VibrationPatterns
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics

/**
 * Tap out your own vibration rhythm. Presses become "on" segments and the gaps between them "off" segments, giving
 * the alternating off/on list the vibrator expects. At most [VibrationPatterns.MAX_CUSTOM_STEPS] steps, 8 seconds.
 */
@Composable
fun RhythmRecorderDialog(
    initial: List<Long>,
    onSave: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalWakeHaptics.current
    val steps = remember { mutableStateListOf<Long>().apply { addAll(initial) } }
    val recordLabel = stringResource(R.string.cd_rhythm_record)
    var lastEnd by remember { mutableStateOf(0L) }
    var started by remember { mutableStateOf(0L) }
    var pressing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rhythm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.rhythm_hint), style = MaterialTheme.typography.bodyMedium)
                Box(
                    Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(
                            if (pressing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(28.dp),
                        )
                        .semantics {
                            role = Role.Button
                            contentDescription = recordLabel
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    val now = SystemClock.elapsedRealtime()
                                    if (steps.size >= VibrationPatterns.MAX_CUSTOM_STEPS - 1) return@detectTapGestures
                                    val gap = if (steps.isEmpty()) 0L else (now - lastEnd).coerceIn(40, VibrationPatterns.MAX_STEP_MS)
                                    started = now
                                    pressing = true
                                    haptics.click()
                                    tryAwaitRelease()
                                    val end = SystemClock.elapsedRealtime()
                                    pressing = false
                                    lastEnd = end
                                    steps += gap
                                    steps += (end - started).coerceIn(40, VibrationPatterns.MAX_STEP_MS)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        pluralStringResource(R.plurals.rhythm_taps, steps.size / 2, steps.size / 2),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (pressing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(steps.toList()); onDismiss() }, enabled = steps.size >= 2) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row2(
                onClear = { steps.clear(); lastEnd = 0 },
                onCancel = onDismiss,
            )
        },
    )
}

@Composable
private fun Row2(onClear: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.foundation.layout.Row {
        TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}
