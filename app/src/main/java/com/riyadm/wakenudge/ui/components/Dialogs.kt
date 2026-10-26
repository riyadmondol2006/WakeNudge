package com.riyadm.wakenudge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.riyadm.wakenudge.R

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.action_cancel),
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (destructive) androidx.compose.material3.MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    label: String? = null,
    maxLength: Int = 60,
    singleLine: Boolean = true,
    numeric: Boolean = false,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= maxLength) value = it },
                    label = label?.let { { Text(it) } },
                    singleLine = singleLine,
                    keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()); onDismiss() }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * For date pickers whose day is only useful from [firstEpochDay] on (a one-time alarm, a day off, the end of a
 * vacation): earlier days cannot be picked, so nobody saves an alarm that can never ring. The picker hands out UTC
 * midnights, which is why the day is compared in UTC.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class SelectableFrom(private val firstEpochDay: Long) : androidx.compose.material3.SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = Math.floorDiv(utcTimeMillis, 86_400_000L) >= firstEpochDay

    override fun isSelectableYear(year: Int): Boolean = year >= java.time.LocalDate.ofEpochDay(firstEpochDay).year
}
