package com.riyadm.wakenudge.ui.protection

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.health.HealthFix
import com.riyadm.wakenudge.health.HealthFixKind
import com.riyadm.wakenudge.health.HealthIntents
import com.riyadm.wakenudge.health.launch
import com.riyadm.wakenudge.ui.components.SectionTitle
import com.riyadm.wakenudge.ui.settings.SettingsPage
import com.riyadm.wakenudge.core.TimeText

/** What the Protection content can ask for; the screen wires each to Android, a test to a recorder. */
class ProtectionActions(
    val openFix: (HealthFixKind) -> Unit,
    val overlayOpened: () -> Unit,
    val appInfoOpened: () -> Unit,
    val rehearse: (Alarm) -> Unit,
    val turnOnAdmin: () -> Unit,
    val turnOffAdmin: () -> Unit,
)

@Composable
fun ProtectionScreen(onBack: () -> Unit, viewModel: ProtectionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Every step is done somewhere the app cannot watch, so everything is read again whenever the screen is back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refresh() }
    val explanation = stringResource(R.string.protection_admin_explanation)
    state.notice?.let { notice ->
        LaunchedEffect(notice) {
            android.widget.Toast.makeText(context, resources.getString(notice), android.widget.Toast.LENGTH_LONG).show()
            viewModel.noticeShown()
        }
    }
    fun open(kind: HealthFixKind) {
        HealthFix(R.string.protection_fix, HealthIntents.forKind(kind, context.packageName), kind).launch(context)
    }
    val actions = ProtectionActions(
        openFix = ::open,
        overlayOpened = { open(HealthFixKind.OVERLAY); viewModel.overlayOpened() },
        appInfoOpened = { open(HealthFixKind.APP_DETAILS); viewModel.appInfoOpened() },
        rehearse = viewModel::rehearse,
        turnOnAdmin = {
            try {
                adminLauncher.launch(viewModel.adminIntent(explanation))
            } catch (_: android.content.ActivityNotFoundException) {
                open(HealthFixKind.APP_DETAILS)
            }
        },
        turnOffAdmin = { viewModel.turnOffAdmin() },
    )
    SettingsPage(stringResource(R.string.protection_title), onBack, intro = stringResource(R.string.protection_intro)) {
        protectionContent(state, actions)
    }
}

/** The whole setup as list items, from a state alone, so a test can show any combination. */
fun LazyListScope.protectionContent(state: ProtectionUiState, actions: ProtectionActions) {
    val inputs = state.inputs ?: return
    val steps = ProtectionPolicy.steps(inputs).associateBy { it.id }
    item(key = "status") {
        val left = ProtectionPolicy.stepsLeft(inputs)
        Text(
            if (left == 0) stringResource(R.string.protection_status_full) else pluralStringResource(R.plurals.protection_status_left, left, left),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).testTag("protection_status"),
        )
    }
    item(key = "overlay") {
        StepCard(R.string.protection_overlay_title, steps.getValue(StepId.OVERLAY).state, "step_overlay") {
            Text(stringResource(R.string.protection_overlay_body))
            if (!inputs.overlayGranted) {
                val restricted = ProtectionPolicy.showRestrictedHelp(inputs)
                when {
                    restricted && inputs.overlayStage == OverlayStage.APP_INFO_OPENED -> {
                        Text(stringResource(R.string.protection_overlay_again_body))
                        Button(onClick = actions.overlayOpened) { Text(stringResource(R.string.protection_overlay_again)) }
                    }
                    restricted -> {
                        Text(stringResource(R.string.protection_overlay_blocked))
                        Text(stringResource(R.string.protection_restricted_body))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = actions.appInfoOpened) { Text(stringResource(R.string.protection_overlay_next)) }
                            OutlinedButton(onClick = actions.overlayOpened) { Text(stringResource(R.string.protection_overlay_again)) }
                        }
                    }
                    else -> Button(onClick = actions.overlayOpened) { Text(stringResource(R.string.protection_overlay_open)) }
                }
            }
        }
    }
    fixStep(StepId.BATTERY, R.string.protection_battery_title, R.string.protection_battery_body, HealthFixKind.BATTERY_OPTIMIZATION, steps, actions)
    fixStep(StepId.NOTIFICATIONS, R.string.protection_notifications_title, R.string.protection_notifications_body, HealthFixKind.APP_NOTIFICATIONS, steps, actions)
    fixStep(StepId.FULL_SCREEN, R.string.protection_full_screen_title, R.string.protection_full_screen_body, HealthFixKind.FULL_SCREEN_INTENT, steps, actions)
    item(key = "rehearsal") {
        StepCard(R.string.protection_rehearsal_title, steps.getValue(StepId.REHEARSAL).state, "step_rehearsal") {
            Text(stringResource(R.string.protection_rehearsal_body))
            if (state.rehearsals.isEmpty()) Text(stringResource(R.string.protection_rehearsal_none))
            val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
            for (choice in state.rehearsals) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val name = TimeText.format(choice.alarm.time, is24) + choice.alarm.label.takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty()
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (choice.rehearsed) Text(stringResource(R.string.protection_rehearsal_done), color = MaterialTheme.colorScheme.primary)
                    FilledTonalButton(onClick = { actions.rehearse(choice.alarm) }, modifier = Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.protection_rehearsal_try))
                    }
                }
            }
        }
    }
    item(key = "admin") { AdminCard(inputs, steps.getValue(StepId.ADMIN).state, actions) }
    item(key = "limits") {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.protection_limits_title))
            Text(stringResource(R.string.protection_limits_power), modifier = Modifier.testTag("limits_power"))
            Text(stringResource(R.string.protection_limits_other))
        }
    }
}

private fun LazyListScope.fixStep(
    id: StepId,
    title: Int,
    body: Int,
    kind: HealthFixKind,
    steps: Map<StepId, ProtectionStep>,
    actions: ProtectionActions,
) = item(key = id.name) {
    val state = steps.getValue(id).state
    StepCard(title, state, "step_${id.name.lowercase()}") {
        Text(stringResource(body))
        if (state != StepState.DONE) Button(onClick = { actions.openFix(kind) }) { Text(stringResource(R.string.protection_fix)) }
    }
}

/**
 * Device admin. The row is there whatever the state, and so is the system route; Turn off is refused only while an
 * alarm rings or is snoozed, and says so, rather than disappearing.
 */
@Composable
private fun AdminCard(inputs: ProtectionInputs, state: StepState, actions: ProtectionActions) {
    val row = ProtectionPolicy.admin(inputs)
    StepCard(R.string.protection_admin_title, state, "step_admin") {
        Text(stringResource(R.string.protection_admin_body))
        Text(
            stringResource(if (row.active) R.string.protection_admin_on else R.string.protection_admin_off),
            style = MaterialTheme.typography.titleSmall,
        )
        if (row.active) {
            OutlinedButton(onClick = actions.turnOffAdmin, enabled = row.canTurnOff, modifier = Modifier.testTag("admin_turn_off")) {
                Text(stringResource(R.string.protection_admin_turn_off))
            }
            if (!row.canTurnOff) Text(stringResource(R.string.protection_admin_wait))
        } else {
            if (state == StepState.LOCKED) Text(stringResource(R.string.protection_admin_locked))
            else if (inputs.sdkInt >= ProtectionPolicy.RESTRICTED_SETTINGS_SDK) Text(stringResource(R.string.protection_admin_restricted), style = MaterialTheme.typography.bodySmall)
            Button(onClick = actions.turnOnAdmin, enabled = row.canTurnOn, modifier = Modifier.testTag("admin_turn_on")) {
                Text(stringResource(R.string.protection_admin_turn_on))
            }
        }
        Text(stringResource(R.string.protection_admin_system_route), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StepCard(title: Int, state: StepState, tag: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                when (state) {
                    StepState.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.protection_done), color = MaterialTheme.colorScheme.primary)
                    }
                    StepState.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, null)
                        Text(stringResource(R.string.protection_locked))
                    }
                    StepState.TODO -> Unit
                }
            }
            content()
        }
    }
}
