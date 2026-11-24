package com.riyadm.wakenudge.ui.sleeptrack

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.TimeFormat
import com.riyadm.wakenudge.engine.sleeptrack.TrackNotice
import com.riyadm.wakenudge.engine.sleeptrack.TrackStatus
import com.riyadm.wakenudge.ui.components.ConfirmDialog
import com.riyadm.wakenudge.ui.components.HeroCard
import com.riyadm.wakenudge.ui.components.SettingRow
import com.riyadm.wakenudge.ui.components.SettingsGroup
import com.riyadm.wakenudge.ui.components.SwitchRow
import com.riyadm.wakenudge.ui.theme.ClockType
import com.riyadm.wakenudge.ui.util.rememberIs24Hour
import com.riyadm.wakenudge.ui.util.spokenDuration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

private fun Context.granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** The health service type needs the physical-activity permission from Android 14. */
private fun needsActivityPermission(context: Context) =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !context.granted(Manifest.permission.ACTIVITY_RECOGNITION)

/** Start a night, watch it while it runs, and see the last one. */
@Composable
fun SleepTrackScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SleepTrackViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val is24 = rememberIs24Hour(settings?.timeFormat ?: TimeFormat.SYSTEM)
    val context = LocalContext.current
    val res = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var askActivity by remember { mutableStateOf(false) }
    var confirmDeleteClips by remember { mutableStateOf(false) }
    var nightOpen by rememberSaveable { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); message = null }
    }
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        message = res.getString(
            when (notice) {
                TrackNotice.REFUSED -> R.string.sleeptrack_refused
                TrackNotice.MIC_UNAVAILABLE -> R.string.sleeptrack_mic_unavailable
                TrackNotice.NO_SENSOR -> R.string.sleeptrack_no_sensor
                TrackNotice.TIME_LIMIT -> R.string.sleeptrack_time_limit
            },
        )
        viewModel.consumeNotice()
    }

    fun startTracking() {
        val mic = state.recordSnoring && context.granted(Manifest.permission.RECORD_AUDIO)
        if (state.recordSnoring && !mic) {
            viewModel.setRecordSnoring(false)
            message = res.getString(R.string.sleeptrack_mic_denied)
        }
        viewModel.start(mic)
    }

    val startPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (needsActivityPermission(context)) askActivity = true else startTracking()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setRecordSnoring(granted)
        if (!granted) message = res.getString(R.string.sleeptrack_mic_denied)
    }

    fun onStart() {
        val wanted = buildList {
            if (needsActivityPermission(context)) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (state.recordSnoring && !context.granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            // Optional: without it the tracking notification (with its microphone notice and I'm up) is hidden.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isEmpty()) startTracking() else startPermissions.launch(wanted.toTypedArray())
    }

    val running = state.status as? TrackStatus.Running
    if (running != null) {
        CalmTracking(running, is24, snackbar, onImUp = viewModel::stop, onBack = onBack, modifier = modifier)
    } else {
        val charging = rememberCharging()
        Scaffold(
            modifier = modifier,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.sleep_track_title)) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
                )
            },
        ) { padding ->
            val zone = remember { ZoneId.systemDefault() }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    HeroCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Bedtime, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(stringResource(R.string.sleeptrack_ready_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.semantics { heading() })
                            if (viewModel.hasSensor) {
                                Text(stringResource(R.string.sleeptrack_ready_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Button(onClick = { onStart() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                    Text(stringResource(R.string.sleeptrack_start), style = MaterialTheme.typography.titleMedium)
                                }
                            } else {
                                Text(stringResource(R.string.sleeptrack_no_sensor), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
                if (!charging) {
                    item {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.BatteryAlert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.sleeptrack_battery_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                }
                item {
                    SettingsGroup {
                        item { shape ->
                            SwitchRow(
                                title = stringResource(R.string.sleeptrack_snoring),
                                subtitle = stringResource(R.string.sleeptrack_snoring_note),
                                icon = Icons.Outlined.Mic,
                                checked = state.recordSnoring,
                                onCheckedChange = { on ->
                                    when {
                                        !on -> viewModel.setRecordSnoring(false)
                                        context.granted(Manifest.permission.RECORD_AUDIO) -> viewModel.setRecordSnoring(true)
                                        else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                shape = shape,
                            )
                        }
                        if (state.clipCount > 0) {
                            item { shape ->
                                SettingRow(
                                    title = stringResource(R.string.sleeptrack_delete_all_clips),
                                    subtitle = pluralStringResource(R.plurals.sleeptrack_clip_count, state.clipCount, state.clipCount),
                                    icon = Icons.Outlined.MicOff,
                                    shape = shape,
                                    onClick = { confirmDeleteClips = true },
                                )
                            }
                        }
                    }
                }
                state.lastNight?.let { night ->
                    item {
                        SectionCard(stringResource(R.string.sleeptrack_last_night)) {
                            Text(nightTitle(night, zone, is24), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            NightNumbers(night)
                            if (night.summary.hasEstimate && night.summary.latencyMs != null) StageLegend(summary = night.summary)
                            FilledTonalButton(onClick = { nightOpen = night.sessionId }) { Text(stringResource(R.string.sleeptrack_view_night)) }
                        }
                    }
                }
                item {
                    SectionCard(stringResource(R.string.sleeptrack_how_title)) {
                        Row {
                            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.sleeptrack_how_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
    }

    if (askActivity) {
        AlertDialog(
            onDismissRequest = { askActivity = false },
            title = { Text(stringResource(R.string.sleeptrack_need_activity_title)) },
            text = { Text(stringResource(R.string.sleeptrack_need_activity_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askActivity = false
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }
                }) { Text(stringResource(R.string.sleeptrack_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { askActivity = false }) { Text(stringResource(R.string.sleeptrack_cancel)) } },
        )
    }
    if (confirmDeleteClips) {
        ConfirmDialog(
            title = stringResource(R.string.sleeptrack_delete_all_clips_title),
            text = stringResource(R.string.sleeptrack_delete_all_clips_body),
            confirmLabel = stringResource(R.string.sleeptrack_delete),
            onConfirm = viewModel::deleteAllClips, onDismiss = { confirmDeleteClips = false }, destructive = true,
        )
    }
    nightOpen?.let { id -> NightDetailDialog(id, onDismiss = { nightOpen = null }) }
}

/**
 * The screen while a night is tracked: dark, a large clock, the time tracked and I'm up. The window is dimmed and the
 * screen may switch off as usual; the clock moves a little every minute so nothing stays lit in one place.
 */
@Composable
private fun CalmTracking(status: TrackStatus.Running, is24: Boolean, snackbar: SnackbarHostState, onImUp: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val res = LocalResources.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }
    DimWindow()
    BackHandler(onBack = onBack)
    val minute = (now / 60_000L).toInt()
    // A slow walk around a small square, one step a minute.
    val shiftX = listOf(0, 8, 16, 8, 0, -8, -16, -8)[minute % 8].dp
    val shiftY = listOf(0, -8, 0, 8, 16, 8, 0, -8)[minute % 8].dp
    val elapsed = (now - status.startedAt).coerceAtLeast(0)
    val dim = Color(0xFF9E9E9E)
    Box(modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back), tint = dim)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).offset(shiftX, shiftY).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                TimeText.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime(), is24),
                style = ClockType.Hero.copy(fontSize = 88.sp, lineHeight = 96.sp), color = dim, textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.sleeptrack_tracking_for, TimeText.duration(elapsed)),
                style = MaterialTheme.typography.titleMedium, color = dim, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = res.getString(R.string.sleeptrack_tracking_for, spokenDuration(res, elapsed)) },
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Icon(if (status.micOn) Icons.Outlined.Mic else Icons.Outlined.MicOff, null, tint = dim)
                Text(
                    stringResource(
                        when {
                            status.micOn -> R.string.sleeptrack_mic_on
                            status.micStoppedForAlarm -> R.string.sleeptrack_mic_stopped
                            else -> R.string.sleeptrack_mic_off
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = dim, modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(40.dp))
            OutlinedButton(onClick = onImUp, modifier = Modifier.fillMaxWidth(0.7f).heightIn(min = 64.dp)) {
                Text(stringResource(R.string.sleeptrack_im_up), style = MaterialTheme.typography.titleLarge, color = dim)
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.sleeptrack_leave_hint), style = MaterialTheme.typography.bodySmall, color = dim.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** Lowers this window's brightness while the calm screen shows; the system setting is never touched. */
@Composable
private fun DimWindow() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val before = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = 0.02f }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = before.takeIf { it >= 0f } ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Whether the phone is on a charger, kept up to date while the screen shows. */
@Composable
private fun rememberCharging(): Boolean {
    val context = LocalContext.current
    var charging by remember { mutableStateOf(true) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                charging = plugged != 0
            }
        }
        val sticky = ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        sticky?.let { charging = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return charging
}
