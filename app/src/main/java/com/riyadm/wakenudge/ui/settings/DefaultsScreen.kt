package com.riyadm.wakenudge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.ui.edit.AntiOversleepSection
import com.riyadm.wakenudge.ui.edit.SnoozeSection
import com.riyadm.wakenudge.ui.edit.SoundSection
import com.riyadm.wakenudge.ui.edit.VibrationSection
import com.riyadm.wakenudge.ui.edit.VolumeSection

/** Defaults for new alarms, edited with the very same sections as a real alarm so nothing can drift. */
@Composable
fun DefaultsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val alarm by viewModel.defaultsAlarm.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.defaults_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text(stringResource(R.string.defaults_hint), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) }
            item { SoundSection(alarm, true, viewModel.soundPreviewer, viewModel::updateDefaults) }
            item { VolumeSection(alarm, true, viewModel::updateDefaults) }
            item { VibrationSection(alarm, true, viewModel.vibrator, viewModel::updateDefaults) }
            item { SnoozeSection(alarm, true, viewModel::updateDefaults) {} }
            item { AntiOversleepSection(alarm, true, viewModel::updateDefaults) }
        }
    }
}
