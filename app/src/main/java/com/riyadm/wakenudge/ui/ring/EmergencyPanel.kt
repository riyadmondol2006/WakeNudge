package com.riyadm.wakenudge.ui.ring

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.engine.policy.RingPolicy
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics

/**
 * The Emergency button: on every ring and mission screen, at every level, and it cannot be switched off. One tap opens a
 * small panel; "Call or use phone" pauses the alarm so a call can be made. It never ends the alarm, so it is not a way out.
 * [pausesSoFar] decides what the panel says: from the third pause in one ring the sound keeps playing (unless a call is on).
 */
@Composable
fun EmergencyButton(pausesSoFar: Int, onCall: () -> Unit, contentColor: Color, modifier: Modifier = Modifier) {
    val haptics = LocalWakeHaptics.current
    var open by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { haptics.click(); open = true },
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
    ) {
        Icon(Icons.Filled.LocalHospital, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text(stringResource(R.string.ring_emergency))
    }
    if (open) {
        val silent = pausesSoFar < RingPolicy.SILENT_PAUSES
        AlertDialog(
            onDismissRequest = { open = false },
            icon = { Icon(Icons.Filled.LocalHospital, contentDescription = null) },
            title = { Text(stringResource(R.string.ring_emergency)) },
            text = { Text(stringResource(if (silent) R.string.ring_emergency_body else R.string.ring_emergency_body_loud)) },
            confirmButton = {
                Button(
                    onClick = { open = false; onCall() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) {
                    Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.ring_emergency_call))
                }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A moment after a dismissal from the ring screen: "You're up", and whether a still-awake check is still to come. */
@Composable
fun YoureUp(checkFollows: Boolean) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(32.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.ring_youre_up), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (checkFollows) {
                Text(
                    stringResource(R.string.ring_youre_up_check), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
