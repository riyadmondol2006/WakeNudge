package com.riyadm.wakenudge.ui.health

import androidx.compose.ui.platform.LocalResources
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.health.HealthItem
import com.riyadm.wakenudge.health.Severity
import com.riyadm.wakenudge.health.launch
import com.riyadm.wakenudge.ui.components.SectionTitle
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.ClockType
import com.riyadm.wakenudge.ui.theme.PolygonShape
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HealthScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: HealthViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val testArmedAt by viewModel.testArmedAt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showEvents by remember { mutableStateOf(false) }
    // Coming back from a Settings screen is the moment to re-check.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_screen_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
                actions = { IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.health_recheck)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Summary(report?.worstSeverity, report?.count(Severity.ERROR) ?: 0, report?.count(Severity.WARNING) ?: 0) }
            item { TestAlarmCard(testArmedAt, onTest10 = { viewModel.armTestAlarm(10) }, onTest60 = { viewModel.armTestAlarm(60) }) }
            val items = report?.bySeverity.orEmpty()
            if (items.isNotEmpty()) item { SectionTitle(stringResource(R.string.health_checks)) }
            items(items, key = { it.id }) { item -> HealthRow(item) { item.fix?.launch(context) } }
            item {
                SectionTitle(stringResource(R.string.health_diagnostics))
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.health_diagnostics_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val text = viewModel.buildReport()
                                    clipboard.setClipEntry(ClipData.newPlainText("WakeNudge diagnostics", text).toClipEntry())
                                    Toast.makeText(context, R.string.health_copied, Toast.LENGTH_SHORT).show()
                                }
                            }) { Text(stringResource(R.string.health_copy)) }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val text = viewModel.buildReport()
                                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                                    context.startActivity(Intent.createChooser(send, resources.getString(R.string.health_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }) {
                                Icon(Icons.Outlined.Share, null, Modifier.padding(end = 8.dp).size(18.dp))
                                Text(stringResource(R.string.health_share))
                            }
                        }
                        TextButton(onClick = { showEvents = !showEvents }) { Text(stringResource(if (showEvents) R.string.health_hide_events else R.string.health_show_events)) }
                        if (showEvents) {
                            val fmt = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
                            if (events.isEmpty()) Text(stringResource(R.string.health_no_events), style = MaterialTheme.typography.bodySmall)
                            events.forEach { e ->
                                Text("${fmt.format(Date(e.timestamp))}  ${e.level}  [${e.tag}] ${e.message}", style = ClockType.Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (events.isNotEmpty()) TextButton(onClick = viewModel::clearLog) { Text(stringResource(R.string.health_clear_log)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Summary(worst: Severity?, errors: Int, warnings: Int) {
    val scheme = MaterialTheme.colorScheme
    val (container, onContainer, icon, title) = when (worst) {
        null -> Quad(scheme.surfaceContainerHigh, scheme.onSurface, Icons.Outlined.Info, stringResource(R.string.health_checking))
        Severity.ERROR -> Quad(scheme.errorContainer, scheme.onErrorContainer, Icons.Outlined.ErrorOutline, stringResource(R.string.health_summary_error))
        Severity.WARNING -> Quad(scheme.tertiaryContainer, scheme.onTertiaryContainer, Icons.Outlined.WarningAmber, stringResource(R.string.health_summary_warning))
        else -> Quad(scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Outlined.CheckCircle, stringResource(R.string.health_summary_ok))
    }
    Surface(shape = RoundedCornerShape(36.dp), color = container, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(PolygonShape(Blobs.Cookie)).background(onContainer.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(36.dp), tint = onContainer)
            }
            Column(Modifier.padding(start = 20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = onContainer)
                if (worst != null) {
                    Text(stringResource(R.string.health_summary_counts, errors, warnings), style = MaterialTheme.typography.bodyMedium, color = onContainer)
                }
            }
        }
    }
}

private data class Quad(val a: androidx.compose.ui.graphics.Color, val b: androidx.compose.ui.graphics.Color, val c: ImageVector, val d: String)

@Composable
private fun TestAlarmCard(armedAt: Long, onTest10: () -> Unit, onTest60: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.health_test_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.health_test_body), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTest10) { Text(stringResource(R.string.health_test_10)) }
                OutlinedButton(onClick = onTest60) { Text(stringResource(R.string.health_test_60)) }
            }
            // Re-evaluated when the test alarm's moment passes, so "will ring at" never outlives the ring.
            var now by remember(armedAt) { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(armedAt) {
                val wait = armedAt - System.currentTimeMillis()
                if (wait > 0) {
                    delay(wait + 250)
                    now = System.currentTimeMillis()
                }
            }
            if (armedAt > now) {
                Text(
                    stringResource(R.string.health_test_armed, DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(armedAt))),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.primary,
                )
            }
        }
    }
}

@Composable
private fun HealthRow(item: HealthItem, onFix: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when (item.severity) {
        Severity.OK -> Icons.Outlined.CheckCircle to scheme.primary
        Severity.INFO -> Icons.Outlined.Info to scheme.onSurfaceVariant
        Severity.WARNING -> Icons.Outlined.WarningAmber to scheme.tertiary
        Severity.ERROR -> Icons.Outlined.ErrorOutline to scheme.error
    }
    Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(stringResource(item.title), style = MaterialTheme.typography.titleSmall)
                Text(item.detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                item.fix?.let { fix ->
                    TextButton(onClick = onFix, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(fix.label)) }
                }
            }
        }
    }
}
