package com.riyadm.wakenudge.ui.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import kotlin.random.Random

/**
 * Last-minute edit protection is meant to stop a half-asleep tap, not to lock the user out. Solving three quick sums
 * proves they are awake and lifts the protection for this editing session. It needs no sensors or permissions, so it
 * also works with a screen reader.
 */
@Composable
fun EditLockOverrideDialog(onSolved: () -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalWakeHaptics.current
    val rnd = remember { Random(System.nanoTime()) }
    var solved by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf(newProblem(rnd)) }
    var input by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_override_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lock_override_body, solved + 1, REQUIRED), style = MaterialTheme.typography.bodyMedium)
                Text("${problem.first} = ?", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 16.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit).take(5); wrong = false },
                    singleLine = true,
                    isError = wrong,
                    supportingText = { if (wrong) Text(stringResource(R.string.lock_override_wrong)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (input.toIntOrNull() == problem.second) {
                    haptics.confirm()
                    if (solved + 1 >= REQUIRED) {
                        onSolved()
                        onDismiss()
                    } else {
                        solved++
                        problem = newProblem(rnd)
                        input = ""
                    }
                } else {
                    haptics.reject()
                    wrong = true
                }
            }) { Text(stringResource(R.string.action_check)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun newProblem(rnd: Random): Pair<String, Int> {
    val a = rnd.nextInt(12, 20)
    val b = rnd.nextInt(3, 10)
    val c = rnd.nextInt(11, 50)
    return "$a × $b + $c" to (a * b + c)
}

private const val REQUIRED = 3
