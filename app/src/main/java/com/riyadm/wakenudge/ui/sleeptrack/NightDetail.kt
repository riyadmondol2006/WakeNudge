package com.riyadm.wakenudge.ui.sleeptrack

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.data.sleep.SnoreClip
import com.riyadm.wakenudge.domain.sleep.Night
import com.riyadm.wakenudge.domain.sleep.NightVerdict
import com.riyadm.wakenudge.ui.components.ConfirmDialog
import com.riyadm.wakenudge.ui.util.currentLocale
import com.riyadm.wakenudge.ui.util.rememberIs24Hour
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle

/** "Tue 24 · 23:10 – 07:02" */
@Composable
fun nightTitle(night: Night, zone: ZoneId, is24: Boolean): String {
    val end = Instant.ofEpochMilli(night.endedAt).atZone(zone)
    val day = end.dayOfWeek.getDisplayName(TextStyle.SHORT, currentLocale()) + " " + end.dayOfMonth
    return day + " · " + stringResource(R.string.sleeptrack_night_range, timeOf(night.startedAt, zone, is24), timeOf(night.endedAt, zone, is24))
}

/** The numbers of one night, or why there are none. */
@Composable
fun NightNumbers(night: Night, modifier: Modifier = Modifier) {
    val s = night.summary
    val none = stringResource(R.string.sleeptrack_none_value)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val tiles = listOf(
            stringResource(R.string.sleeptrack_asleep) to (if (s.hasEstimate) TimeText.duration(s.asleepMs) else none),
            stringResource(R.string.sleeptrack_in_bed) to TimeText.duration(s.inBedMs),
            stringResource(R.string.sleeptrack_latency) to (s.latencyMs?.let { TimeText.duration(it) } ?: none),
            stringResource(R.string.sleeptrack_awake_after) to (if (s.hasEstimate && s.latencyMs != null) TimeText.duration(s.awakeAfterOnsetMs) else none),
        )
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
            }
        }
        if (s.short) Text(stringResource(R.string.sleeptrack_short), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
        verdictText(night)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun verdictText(night: Night): String? = when {
    night.summary.verdict == NightVerdict.TOO_LITTLE_DATA -> stringResource(R.string.sleeptrack_too_little_data)
    night.summary.verdict == NightVerdict.NO_MOVEMENT -> stringResource(R.string.sleeptrack_no_movement)
    night.summary.latencyMs == null -> stringResource(R.string.sleeptrack_no_sleep_found)
    else -> null
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** A clip with Listen, Share and Delete. Share only ever happens from its button. */
@Composable
fun ClipRow(
    clip: SnoreClip,
    playing: Boolean,
    zone: ZoneId,
    is24: Boolean,
    onListen: () -> Unit,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val when_ = Instant.ofEpochMilli(clip.recordedAt).atZone(zone)
    val day = when_.dayOfWeek.getDisplayName(TextStyle.SHORT, currentLocale()) + " " + when_.dayOfMonth + " " + TimeText.format(when_.toLocalTime(), is24)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sleeptrack_clip_line, stringResource(loudnessLabel(clip.loudness)), day), style = MaterialTheme.typography.titleSmall)
        ClipWaveform(clip.envelope, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onListen) {
                Icon(if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null)
                Text(stringResource(if (playing) R.string.sleeptrack_stop_listening else R.string.sleeptrack_listen), Modifier.padding(start = 8.dp))
            }
            Box(Modifier.weight(1f))
            if (onShare != null) IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, stringResource(R.string.sleeptrack_share)) }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.sleeptrack_delete_clip)) }
        }
    }
}

/** Plays a clip, or says it cannot be played. */
@Composable
fun rememberClipListener(actions: ClipActions): (SnoreClip) -> Unit {
    val context = LocalContext.current
    val cant = stringResource(R.string.sleeptrack_cant_play)
    return remember(actions, context) { { clip -> if (!actions.toggle(clip)) Toast.makeText(context, cant, Toast.LENGTH_SHORT).show() } }
}

@Composable
fun rememberClipSharer(actions: ClipActions): (SnoreClip) -> Unit {
    val context = LocalContext.current
    val title = stringResource(R.string.sleeptrack_share_title)
    val cant = stringResource(R.string.sleeptrack_cant_play)
    return remember(actions, context) {
        { clip ->
            val intent = actions.shareIntent(clip, title)
            if (intent == null) Toast.makeText(context, cant, Toast.LENGTH_SHORT).show()
            else try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { Toast.makeText(context, cant, Toast.LENGTH_SHORT).show() }
        }
    }
}

/** A single night, full screen: numbers, the stage timeline, the sound level and the clips. */
@Composable
fun NightDetailDialog(sessionId: Long, onDismiss: () -> Unit, viewModel: NightDetailViewModel = hiltViewModel(key = "night-detail")) {
    LaunchedEffect(sessionId) { viewModel.open(sessionId) }
    val detail by viewModel.night.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playing by viewModel.clips.playing.collectAsStateWithLifecycle()
    val is24 = rememberIs24Hour(settings?.timeFormat ?: com.riyadm.wakenudge.domain.model.TimeFormat.SYSTEM)
    val zone = remember { ZoneId.systemDefault() }
    var confirmDelete by remember { mutableStateOf(false) }
    val listen = rememberClipListener(viewModel.clips)
    val share = rememberClipSharer(viewModel.clips)
    Dialog(
        onDismissRequest = { viewModel.clips.stop(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(detail?.let { nightTitle(it.night, zone, is24) } ?: "", maxLines = 1) },
                    navigationIcon = { IconButton(onClick = { viewModel.clips.stop(); onDismiss() }) { Icon(Icons.Outlined.Close, stringResource(R.string.sleeptrack_close)) } },
                    actions = {
                        if (detail != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.sleeptrack_delete_night)) }
                    },
                )
            },
        ) { padding ->
            val d = detail ?: run { Box(Modifier.fillMaxSize().padding(padding)); return@Scaffold }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { NightNumbers(d.night) }
                if (d.night.summary.hasEstimate && d.night.summary.latencyMs != null) {
                    item {
                        SectionCard(stringResource(R.string.sleeptrack_timeline)) {
                            StageTimeline(d.night.summary, d.night.startedAt, d.night.endedAt, zone, is24)
                            StageLegend(summary = d.night.summary)
                        }
                    }
                }
                if (d.epochs.any { it.soundDb != null }) {
                    item {
                        SectionCard(stringResource(R.string.sleeptrack_sound_title)) {
                            SoundLevelGraph(d.epochs, epochCount(d.night))
                            d.micStoppedAt?.let {
                                Text(stringResource(R.string.sleeptrack_mic_stopped_at, timeOf(it, zone, is24)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (d.night.usedMicrophone) {
                    item {
                        SectionCard(stringResource(R.string.sleeptrack_clips_title)) {
                            if (d.clips.isEmpty()) Text(stringResource(R.string.sleeptrack_clips_none), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(d.clips, key = { it.id }) { clip ->
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                            ClipRow(
                                clip, playing == clip.id, zone, is24,
                                onListen = { listen(clip) }, onShare = { share(clip) }, onDelete = { viewModel.deleteClip(clip) },
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.sleeptrack_how_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
        if (confirmDelete) {
            ConfirmDialog(
                title = stringResource(R.string.sleeptrack_delete_night_title),
                text = stringResource(R.string.sleeptrack_delete_night_body),
                confirmLabel = stringResource(R.string.sleeptrack_delete),
                onConfirm = { viewModel.deleteNight(onDismiss) }, onDismiss = { confirmDelete = false }, destructive = true,
            )
        }
    }
}
