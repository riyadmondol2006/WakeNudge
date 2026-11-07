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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Hardness
import com.riyadm.wakenudge.ui.edit.HowHardCard
import com.riyadm.wakenudge.ui.edit.SoundRow
import com.riyadm.wakenudge.ui.edit.VibrationSection

/**
 * Defaults for new alarms: how hard, the sound and the vibration. The level decides everything else, and any detail can
 * still be changed on the alarm itself.
 */
@Composable
fun DefaultsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val alarm = viewModel.defaultsAlarm.collectAsStateWithLifecycle().value ?: return
    val level = viewModel.defaultLevel.collectAsStateWithLifecycle().value ?: return
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
            item {
                Text(
                    stringResource(R.string.defaults_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            item { HowHardCard(selected = level, enabled = true, onPick = viewModel::setDefaultLevel) }
            item { SoundRow(alarm, enabled = true, previewer = viewModel.soundPreviewer, onChange = viewModel::updateDefaults) }
            item {
                VibrationSection(alarm, enabled = true, vibrator = viewModel.vibrator, onChange = viewModel::updateDefaults, lockedOn = level != Hardness.EASY)
            }
        }
    }
}
