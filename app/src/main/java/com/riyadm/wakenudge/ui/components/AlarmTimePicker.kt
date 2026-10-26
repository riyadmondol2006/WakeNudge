package com.riyadm.wakenudge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.ui.theme.ClockType

/**
 * The alarm time picker: three snapping wheels (hour, minute, AM/PM when the user prefers a 12-hour clock) with
 * detent haptics. [onChange] receives a 24-hour value either way. Hours stay left of minutes in right-to-left
 * languages too, as on any clock.
 */
@Composable
fun AlarmTimePicker(
    hour: Int,
    minute: Int,
    is24: Boolean,
    onChange: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val pm = hour >= 12
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        WheelPicker(
            count = if (is24) 24 else 12,
            selected = if (is24) hour else hour % 12,
            onSelected = { i -> onChange(if (is24) i else (i % 12) + if (pm) 12 else 0, minute) },
            label = { i -> if (is24) "%02d".format(i) else if (i == 0) "12" else i.toString() },
            description = stringResource(R.string.picker_hour),
            enabled = enabled,
        )
        Text(":", style = ClockType.WheelItem, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 2.dp))
        WheelPicker(
            count = 60,
            selected = minute,
            onSelected = { onChange(hour, it) },
            label = { "%02d".format(it) },
            description = stringResource(R.string.picker_minute),
            enabled = enabled,
        )
        if (!is24) {
            Row(Modifier.width(12.dp)) {}
            WheelPicker(
                count = 2,
                selected = if (pm) 1 else 0,
                onSelected = { i -> onChange((hour % 12) + if (i == 1) 12 else 0, minute) },
                label = { if (it == 0) "AM" else "PM" },
                description = stringResource(R.string.picker_am_pm),
                loop = false,
                width = 88.dp,
                textStyle = ClockType.WheelItem.copy(fontSize = androidx.compose.ui.unit.TextUnit(34f, androidx.compose.ui.unit.TextUnitType.Sp)),
                enabled = enabled,
            )
        }
    }
    }
}

/** Typed time entry: the precise, screen-reader- and keyboard-friendly alternative to the wheels. */
@Composable
fun TimeEntryDialog(
    hour: Int,
    minute: Int,
    is24: Boolean,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = is24)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_type_time)) },
        text = { TimeInput(state) },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute); onDismiss() }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
