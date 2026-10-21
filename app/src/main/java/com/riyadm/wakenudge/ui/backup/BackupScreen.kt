package com.riyadm.wakenudge.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.ui.components.ConfirmDialog
import com.riyadm.wakenudge.ui.components.ExpressiveSwitch

/** Local backup and restore through the system file picker: no storage permission, nothing leaves the device. */
@Composable
fun BackupScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var includeHistory by remember { mutableStateOf(true) }
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export(uri, includeHistory)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingImport = uri }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.backup_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.backup_export_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.backup_export_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.backup_include_history), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            ExpressiveSwitch(checked = includeHistory, onCheckedChange = { includeHistory = it })
                        }
                        Button(onClick = { create.launch(viewModel.suggestedFileName()) }, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.FileUpload, null, Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.backup_export_button))
                        }
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.backup_restore_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.backup_restore_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.FileDownload, null, Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.backup_restore_button))
                        }
                    }
                }
            }
            if (state.working) {
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
            }
            state.message?.let { message ->
                item {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (state.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(message.text(context), style = MaterialTheme.typography.titleSmall)
                            message.details.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
    }
    pendingImport?.let { uri ->
        ConfirmDialog(
            title = stringResource(R.string.backup_confirm_title), text = stringResource(R.string.backup_confirm_body),
            confirmLabel = stringResource(R.string.backup_restore_button),
            onConfirm = { viewModel.restore(uri, context) }, onDismiss = { pendingImport = null }, destructive = true,
        )
    }
}
