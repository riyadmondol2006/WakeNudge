package com.riyadm.wakenudge.ui.settings

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.ui.components.ConfirmDialog
import com.riyadm.wakenudge.ui.components.ExpressiveSwitch
import com.riyadm.wakenudge.ui.components.TextInputDialog

/** Profiles (groups): switch whole sets of alarms on or off together, e.g. "Work week" vs "Weekend". */
@Composable
fun GroupsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: GroupsViewModel = hiltViewModel()) {
    val groups by viewModel.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AlarmGroup?>(null) }
    var deleting by remember { mutableStateOf<AlarmGroup?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.groups_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(stringResource(R.string.groups_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            items(groups, key = { it.id }) { group ->
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().animateItem()) {
                    Row(Modifier.padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(group.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(if (group.enabled) R.string.state_on else R.string.state_off),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Each button names its profile: in a list of them, "Rename" alone does not say which.
                        IconButton(onClick = { renaming = group }) { Icon(Icons.Outlined.Edit, stringResource(R.string.groups_rename_named, group.name)) }
                        IconButton(onClick = { deleting = group }) { Icon(Icons.Outlined.Delete, stringResource(R.string.groups_delete_named, group.name)) }
                        ExpressiveSwitch(
                            checked = group.enabled, onCheckedChange = { viewModel.setEnabled(group, it) },
                            modifier = Modifier.semantics { contentDescription = group.name },
                        )
                    }
                }
            }
            item {
                Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.groups_new))
                }
            }
        }
    }
    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.groups_new), initial = "", label = stringResource(R.string.groups_name), maxLength = 30,
            onConfirm = viewModel::create, onDismiss = { creating = false },
        )
    }
    renaming?.let { g ->
        TextInputDialog(
            title = stringResource(R.string.groups_rename), initial = g.name, label = stringResource(R.string.groups_name), maxLength = 30,
            onConfirm = { viewModel.rename(g, it) }, onDismiss = { renaming = null },
        )
    }
    deleting?.let { g ->
        ConfirmDialog(
            title = stringResource(R.string.groups_delete_title, g.name), text = stringResource(R.string.groups_delete_body),
            confirmLabel = stringResource(R.string.action_delete), onConfirm = { viewModel.delete(g) }, onDismiss = { deleting = null }, destructive = true,
        )
    }
}
