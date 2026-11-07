package com.riyadm.wakenudge.ui.edit

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.engine.PreflightKind
import com.riyadm.wakenudge.health.HealthFix
import com.riyadm.wakenudge.health.HealthFixKind
import com.riyadm.wakenudge.health.HealthIntents
import com.riyadm.wakenudge.health.launch
import com.riyadm.wakenudge.missions.MissionInfo

/**
 * After a save: what would stop the alarm's missions from running, each with its fix right here (a permission is asked
 * for now, not found missing at ring time), and "Try it now" for a strict alarm never rehearsed. Only a warning.
 */
@Composable
internal fun PreflightDialog(after: AlarmEditViewModel.AfterSave, onRehearse: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.preflight_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in after.problems) {
                    val mission = stringResource(MissionInfo.titleRes(p.mission))
                    Text(
                        when (p.kind) {
                            PreflightKind.NO_HARDWARE -> stringResource(R.string.preflight_no_hardware, mission)
                            PreflightKind.NOT_SET_UP -> stringResource(R.string.preflight_not_set_up, mission)
                            PreflightKind.NEEDS_CAMERA -> stringResource(R.string.preflight_permission_camera, mission)
                            PreflightKind.NFC_OFF -> stringResource(R.string.preflight_nfc_off)
                            PreflightKind.NEEDS_ACTIVITY -> stringResource(R.string.preflight_permission_activity)
                            PreflightKind.MOTION_WITH_SCREEN_READER -> stringResource(R.string.preflight_motion_screen_reader, mission)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    when (p.kind) {
                        PreflightKind.NEEDS_CAMERA -> TextButton(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.preflight_allow)) }
                        PreflightKind.NEEDS_ACTIVITY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            TextButton(onClick = { ask.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) { Text(stringResource(R.string.preflight_allow)) }
                        }
                        PreflightKind.NFC_OFF -> TextButton(onClick = {
                            HealthFix(R.string.preflight_open_nfc, HealthIntents.forKind(HealthFixKind.NFC_SETTINGS, context.packageName), HealthFixKind.NFC_SETTINGS).launch(context)
                        }) { Text(stringResource(R.string.preflight_open_nfc)) }
                        else -> Unit
                    }
                }
                if (after.offerRehearsal) {
                    Text(stringResource(R.string.preflight_rehearse), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.preflight_ok)) } },
        dismissButton = if (after.offerRehearsal) {
            { TextButton(onClick = onRehearse) { Text(stringResource(R.string.protection_rehearsal_try)) } }
        } else null,
    )
}
