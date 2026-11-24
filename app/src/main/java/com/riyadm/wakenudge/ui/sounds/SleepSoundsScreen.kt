package com.riyadm.wakenudge.ui.sounds

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.engine.sounds.*
import kotlin.math.sin

@Composable
fun SleepSoundsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, vm: SleepSoundsViewModel = hiltViewModel()) {
    val mix by vm.store.mix.collectAsStateWithLifecycle()
    val ready by vm.store.ready.collectAsStateWithLifecycle()
    val playing by vm.store.playing.collectAsStateWithLifecycle()
    val remaining by vm.store.remaining.collectAsStateWithLifecycle()
    val error by vm.store.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onSuccess { vm.store.save(vm.store.mix.value.copy(uri = uri.toString())) }
                .onFailure { vm.store.error.value = true }
        }
    }
    var timerOpen by remember { mutableStateOf(false) }
    Scaffold(modifier, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.sleep_sounds_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) }
        })
    }) { padding ->
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.sounds_intro), style = MaterialTheme.typography.bodyLarge)
            }
            items(SleepSound.entries, key = { it.name }) { kind ->
                val selected = kind in mix.layers
                val label = stringResource(soundLabel(kind))
                val state = stringResource(if (selected) R.string.sounds_added else R.string.sounds_not_added)
                val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                OutlinedCard(onClick = {
                    vm.store.save(mix.copy(layers = if (selected) mix.layers - kind else mix.layers + (kind to 0.5f)))
                }, enabled = selected || mix.layers.size < 3, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label; stateDescription = state }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Small original line glyphs: each sound has its own wave frequency and silhouette.
                        Canvas(Modifier.size(32.dp)) {
                            val points = 24
                            for (i in 1..points) {
                                fun point(j: Int) = Offset(size.width * j / points, size.height * (0.5f + 0.28f * sin(j * (kind.ordinal + 2) * 0.18f)))
                                drawLine(color, point(i-1), point(i), strokeWidth = 2.dp.toPx())
                            }
                        }
                        Text(label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    mix.layers.forEach { (kind, volume) ->
                        Text(stringResource(soundLabel(kind)))
                        val volumeLabel = stringResource(R.string.sounds_volume, stringResource(soundLabel(kind)))
                        Slider(volume, onValueChange = { vm.store.save(mix.copy(layers = mix.layers + (kind to it))) }, modifier = Modifier.semantics { contentDescription = volumeLabel })
                    }
                    OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !playing, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (mix.uri == null) R.string.sounds_add_file else R.string.sounds_replace_file))
                    }
                    if (mix.uri != null) {
                        Text(stringResource(R.string.sounds_file))
                        val fileVolumeLabel = stringResource(R.string.sounds_file_volume)
                        Slider(mix.fileVolume, onValueChange = { vm.store.save(mix.copy(fileVolume = it)) }, modifier = Modifier.semantics { contentDescription = fileVolumeLabel })
                        TextButton(onClick = { vm.store.save(mix.copy(uri = null)) }, enabled = !playing) { Text(stringResource(R.string.sounds_remove_file)) }
                    }
                    Box {
                        OutlinedButton(onClick = { timerOpen = true }, enabled = !playing, modifier = Modifier.fillMaxWidth()) {
                            Text(if (mix.minutes == 0) stringResource(R.string.sounds_next_alarm) else stringResource(R.string.sounds_timer, mix.minutes))
                        }
                        DropdownMenu(expanded = timerOpen, onDismissRequest = { timerOpen = false }) {
                            listOf(15, 30, 45, 60, 90, 0).forEach { minutes ->
                                DropdownMenuItem(text = { Text(if (minutes == 0) stringResource(R.string.sounds_next_alarm) else stringResource(R.string.sounds_timer, minutes)) }, onClick = { vm.store.save(mix.copy(minutes = minutes)); timerOpen = false })
                            }
                        }
                    }
                    if (playing) Text(stringResource(R.string.sounds_remaining, (remaining + 59999) / 60000))
                    if (error) Text(stringResource(R.string.sounds_error), color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::toggle, enabled = ready && (playing || mix.layers.isNotEmpty() || mix.uri != null), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(if (playing) R.string.sounds_stop else R.string.sounds_play))
                    }
                    Text(stringResource(R.string.sounds_footer), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun soundLabel(kind: SleepSound) = when (kind) {
    SleepSound.WHITE -> R.string.sounds_white
    SleepSound.PINK -> R.string.sounds_pink
    SleepSound.BROWN -> R.string.sounds_brown
    SleepSound.RAIN -> R.string.sounds_rain
    SleepSound.OCEAN -> R.string.sounds_ocean
    SleepSound.WIND -> R.string.sounds_wind
    SleepSound.STREAM -> R.string.sounds_stream
    SleepSound.FAN -> R.string.sounds_fan
    SleepSound.FIRE -> R.string.sounds_fire
}
