package com.riyadm.wakenudge.ui.edit

import androidx.compose.ui.platform.LocalResources
import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.SoundMode
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.domain.model.SoundSource
import com.riyadm.wakenudge.engine.audio.BundledTone
import com.riyadm.wakenudge.engine.audio.BundledTones
import com.riyadm.wakenudge.engine.audio.SoundPreviewer
import com.riyadm.wakenudge.engine.audio.ToneCharacter
import com.riyadm.wakenudge.ui.components.SectionTitle
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Title of a sound for display ("Sunrise", a song's file name, a system ringtone's title). */
@Composable
fun rememberSoundTitle(sound: SoundSettings): String {
    val context = LocalContext.current
    val resources = LocalResources.current
    val bundledName = BundledTones.byId(sound.bundledId)?.let { stringResource(it.nameRes) } ?: stringResource(R.string.tone_sunrise)
    val random = stringResource(R.string.sound_random)
    val custom = produceState(initialValue = "", sound) {
        value = withContext(Dispatchers.IO) {
            when {
                sound.mode == SoundMode.RANDOM_BUNDLED -> ""
                sound.mode == SoundMode.RANDOM_CUSTOM -> ""
                sound.source == SoundSource.BUNDLED -> ""
                sound.uri == null -> ""
                else -> uriTitle(context, Uri.parse(sound.uri), sound.source == SoundSource.SYSTEM)
            }
        }
    }
    return when (sound.mode) {
        SoundMode.RANDOM_BUNDLED -> stringResource(R.string.sound_random_bundled_summary, if (sound.randomPool.isEmpty()) BundledTones.all.size else sound.randomPool.size)
        SoundMode.RANDOM_CUSTOM -> stringResource(R.string.sound_random_custom_summary, sound.playlist.size)
        SoundMode.SINGLE -> if (sound.source == SoundSource.BUNDLED) bundledName else custom.value.ifBlank { random }
    }
}

internal fun uriTitle(context: android.content.Context, uri: Uri, isRingtone: Boolean): String {
    if (isRingtone) {
        runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()?.let { return it }
    }
    var cursor: Cursor? = null
    return try {
        cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (cursor != null && cursor.moveToFirst()) cursor.getString(0).substringBeforeLast('.') else uri.lastPathSegment.orEmpty()
    } catch (_: Throwable) {
        uri.lastPathSegment.orEmpty()
    } finally {
        cursor?.close()
    }
}

@Composable
fun SoundPickerSheet(
    sound: SoundSettings,
    onChange: (SoundSettings) -> Unit,
    onDismiss: () -> Unit,
    previewer: SoundPreviewer,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val haptics = LocalWakeHaptics.current
    val playing by previewer.playing.collectAsState()
    val failed by previewer.failed.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Whatever way the sheet goes away (dismiss, back, the app being hidden), the preview must not outlive it.
    DisposableEffect(previewer) { onDispose { previewer.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { previewer.stop() }

    fun persist(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                onChange(sound.copy(mode = SoundMode.SINGLE, source = SoundSource.SYSTEM, uri = uri.toString()))
                previewer.playUri(uri.toString(), toggle = false)
            }
        }
    }
    val singleFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persist(uri)
            onChange(sound.copy(mode = SoundMode.SINGLE, source = SoundSource.CUSTOM, uri = uri.toString()))
            previewer.playUri(uri.toString(), toggle = false)
        }
    }
    val playlistFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(::persist)
            onChange(sound.copy(mode = SoundMode.RANDOM_CUSTOM, playlist = (sound.playlist + uris.map { it.toString() }).distinct()))
            previewer.playUri(uris.first().toString(), toggle = false)
        }
    }

    ModalBottomSheet(onDismissRequest = { previewer.stop(); onDismiss() }, sheetState = sheet) {
        LazyColumn(
            modifier = Modifier.navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(stringResource(R.string.sound_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                com.riyadm.wakenudge.ui.components.SettingsGroup {
                    item { shape ->
                        com.riyadm.wakenudge.ui.components.ChipChoiceRow(
                            title = stringResource(R.string.sound_mode),
                            options = SoundMode.entries.toList(),
                            selected = sound.mode,
                            onSelect = { previewer.stop(); onChange(sound.copy(mode = it)) },
                            optionLabel = { stringResource(soundModeLabel(it)) },
                            shape = shape,
                        )
                    }
                }
                val streamSilent = remember(playing) { previewer.alarmStreamSilent }
                if (failed != null) {
                    PickerHint(stringResource(R.string.sound_preview_failed), isError = true)
                } else if (streamSilent) {
                    PickerHint(stringResource(R.string.sound_preview_silent))
                } else {
                    PickerHint(stringResource(R.string.sound_preview_tip))
                }
            }
            when (sound.mode) {
                SoundMode.SINGLE -> {
                    ToneCharacter.entries.forEach { character ->
                        item(key = "h-$character") { SectionTitle(stringResource(characterLabel(character))) }
                        items(BundledTones.all.filter { it.character == character }, key = { it.id }) { tone ->
                            ToneRow(
                                tone = tone,
                                selected = sound.source == SoundSource.BUNDLED && sound.bundledId == tone.id,
                                playing = playing == tone.id,
                                onSelect = {
                                    haptics.tick()
                                    onChange(sound.copy(mode = SoundMode.SINGLE, source = SoundSource.BUNDLED, bundledId = tone.id))
                                    previewer.playBundled(tone.id, toggle = false)
                                },
                                onPreview = { previewer.playBundled(tone.id) },
                            )
                        }
                    }
                    item(key = "more") {
                        SectionTitle(stringResource(R.string.sound_more))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, resources.getString(R.string.sound_system_picker_title))
                                    sound.uri?.takeIf { sound.source == SoundSource.SYSTEM }?.let {
                                        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
                                    }
                                    ringtonePicker.launch(intent)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Outlined.LibraryMusic, null, Modifier.padding(end = 8.dp))
                                Text(stringResource(R.string.sound_system_sounds))
                            }
                            OutlinedButton(onClick = { singleFile.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.AudioFile, null, Modifier.padding(end = 8.dp))
                                Text(stringResource(R.string.sound_my_audio))
                            }
                            if (sound.source != SoundSource.BUNDLED && sound.uri != null) {
                                Text(
                                    stringResource(R.string.sound_current, rememberSoundTitle(sound)),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                SoundMode.RANDOM_BUNDLED -> {
                    item { Text(stringResource(R.string.sound_random_bundled_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
                    items(BundledTones.all, key = { it.id }) { tone ->
                        val inPool = sound.randomPool.isEmpty() || tone.id in sound.randomPool
                        ToneRow(
                            tone = tone,
                            selected = inPool,
                            multi = true,
                            playing = playing == tone.id,
                            onSelect = {
                                haptics.tick()
                                val current = if (sound.randomPool.isEmpty()) BundledTones.all.map { it.id }.toSet() else sound.randomPool
                                val next = if (tone.id in current) current - tone.id else current + tone.id
                                // Never allow an empty pool by unticking the last tone; empty means "all" in storage.
                                if (next.isNotEmpty()) {
                                    onChange(sound.copy(randomPool = if (next.size == BundledTones.all.size) emptySet() else next))
                                    if (tone.id in next) previewer.playBundled(tone.id, toggle = false) else if (playing == tone.id) previewer.stop()
                                }
                            },
                            onPreview = { previewer.playBundled(tone.id) },
                        )
                    }
                }
                SoundMode.RANDOM_CUSTOM -> {
                    item {
                        Text(stringResource(R.string.sound_random_custom_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                        OutlinedButton(onClick = { playlistFiles.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.LibraryMusic, null, Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.sound_add_songs))
                        }
                    }
                    items(sound.playlist, key = { it }) { uri ->
                        val title by produceState("", uri) { value = withContext(Dispatchers.IO) { uriTitle(context, Uri.parse(uri), false) } }
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { previewer.playUri(uri) }) {
                                Icon(if (playing == uri) Icons.Filled.Stop else Icons.Filled.PlayArrow, stringResource(R.string.cd_preview_sound))
                            }
                            Text(title.ifBlank { uri }, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { onChange(sound.copy(playlist = sound.playlist - uri)) }) {
                                Icon(Icons.Filled.Close, stringResource(R.string.cd_remove))
                            }
                        }
                    }
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun ToneRow(tone: BundledTone, selected: Boolean, playing: Boolean, onSelect: () -> Unit, onPreview: () -> Unit, multi: Boolean = false) {
    val rowModifier = if (multi) {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onSelect() })
    } else {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
    }
    Row(
        rowModifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row is the single touch target; the control is only the indicator.
        if (multi) Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(12.dp)) else RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(stringResource(tone.nameRes), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = onPreview) {
            Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, stringResource(R.string.cd_preview_sound))
        }
    }
}

@Composable
private fun PickerHint(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

private fun soundModeLabel(mode: SoundMode) = when (mode) {
    SoundMode.SINGLE -> R.string.sound_mode_single
    SoundMode.RANDOM_BUNDLED -> R.string.sound_mode_random_bundled
    SoundMode.RANDOM_CUSTOM -> R.string.sound_mode_random_custom
}

private fun characterLabel(c: ToneCharacter) = when (c) {
    ToneCharacter.GENTLE -> R.string.tone_group_gentle
    ToneCharacter.STANDARD -> R.string.tone_group_standard
    ToneCharacter.LOUD -> R.string.tone_group_loud
}
