package com.riyadm.wakenudge.ui.update

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.BuildConfig
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.engine.RingSessionStore
import com.riyadm.wakenudge.ui.components.SectionTitle
import com.riyadm.wakenudge.ui.components.SettingRow
import com.riyadm.wakenudge.ui.components.SettingsGroup
import com.riyadm.wakenudge.ui.components.SwitchRow
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.PolygonShape
import com.riyadm.wakenudge.update.ProjectLinks
import com.riyadm.wakenudge.update.ReleaseInfo
import com.riyadm.wakenudge.update.UpdateManager
import com.riyadm.wakenudge.update.UpdateStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the About screen's "Updates" section shows. */
data class UpdatesUiState(
    val enabled: Boolean = true,
    /** Epoch millis of the last check GitHub answered; 0 when there has not been one. */
    val lastCheckedAt: Long = 0,
    val status: UpdateStatus = UpdateStatus.Idle,
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val manager: UpdateManager,
    settings: SettingsRepository,
    ring: RingSessionStore,
) : ViewModel() {

    /** The release to announce. Held back while an alarm rings, so the popup can never get in its way. */
    val prompt: StateFlow<ReleaseInfo?> = combine(manager.prompt, ring.session) { release, session -> release.takeIf { session == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<UpdatesUiState> = combine(settings.settings, manager.status) { s, status ->
        UpdatesUiState(enabled = s.updateChecksEnabled, lastCheckedAt = s.lastUpdateCheckAt, status = status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdatesUiState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { manager.setEnabled(enabled) }
    }

    fun checkNow() = manager.checkNow()

    fun dismissPrompt() = manager.dismissPrompt()

    fun skip(release: ReleaseInfo) {
        viewModelScope.launch { manager.skip(release) }
    }
}

/** Shows the "Update available" popup when there is something to announce. Lives inside the main app only. */
@Composable
fun UpdatePromptHost() {
    val viewModel: UpdateViewModel = hiltViewModel()
    val release by viewModel.prompt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    release?.let { current ->
        UpdateAvailableDialog(
            release = current,
            installedVersion = BuildConfig.VERSION_NAME,
            onDownload = {
                viewModel.dismissPrompt()
                ProjectLinks.open(context, current.downloadUrl)
            },
            onLater = viewModel::dismissPrompt,
            onSkip = { viewModel.skip(current) },
        )
    }
}

/**
 * The popup: what is available, what you have, a short excerpt of the release notes, and three ways out. The body
 * scrolls so every button stays reachable at the largest font size.
 */
@Composable
fun UpdateAvailableDialog(
    release: ReleaseInfo,
    installedVersion: String,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val title = stringResource(R.string.update_dialog_title)
    val primary = remember { FocusRequester() }
    // Keyboard and switch users land on the main action; TalkBack still starts from the title.
    LaunchedEffect(Unit) { runCatching { primary.requestFocus() } }

    BasicAlertDialog(onDismissRequest = onLater) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier.semantics { paneTitle = title },
        ) {
            Column(Modifier.padding(24.dp)) {
                Box(Modifier.size(56.dp).clip(PolygonShape(Blobs.Sunny)).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = scheme.onPrimaryContainer)
                }
                Text(
                    title, style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 16.dp).semantics { heading() },
                )
                Column(Modifier.weight(1f, fill = false).padding(top = 12.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.update_dialog_body, release.version.toString(), installedVersion),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (release.notes.isNotBlank()) {
                        Text(
                            stringResource(R.string.update_notes_title), style = MaterialTheme.typography.titleSmall, color = scheme.primary,
                            modifier = Modifier.padding(top = 16.dp).semantics { heading() },
                        )
                        Text(
                            release.notes, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.update_skip)) }
                    TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
                    Button(onClick = onDownload, modifier = Modifier.focusRequester(primary)) { Text(stringResource(R.string.update_download)) }
                }
            }
        }
    }
}

/** The "Updates" section of the About screen. Absent from the Play build, which defines an empty one. */
fun LazyListScope.updatesSection() {
    item {
        val viewModel: UpdateViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        UpdatesCard(
            state = state,
            onToggle = viewModel::setEnabled,
            onCheckNow = viewModel::checkNow,
            onDownload = { ProjectLinks.open(context, it.downloadUrl) },
        )
    }
}

@Composable
internal fun UpdatesCard(
    state: UpdatesUiState,
    onToggle: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onDownload: (ReleaseInfo) -> Unit,
) {
    val context = LocalContext.current
    val status = state.status
    val headline = when (status) {
        UpdateStatus.Idle -> stringResource(R.string.update_idle)
        UpdateStatus.Checking -> stringResource(R.string.update_checking)
        UpdateStatus.UpToDate -> stringResource(R.string.update_up_to_date)
        is UpdateStatus.Available -> stringResource(R.string.update_available_row, status.release.version.toString())
        is UpdateStatus.Failed -> stringResource(R.string.update_failed)
    }
    val lastChecked = if (state.lastCheckedAt <= 0) {
        stringResource(R.string.update_never_checked)
    } else {
        val whenText = DateUtils.formatDateTime(
            context, state.lastCheckedAt,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
        stringResource(R.string.update_last_checked, whenText)
    }

    Column {
        SectionTitle(stringResource(R.string.update_section))
        SettingsGroup {
            item { shape ->
                SwitchRow(
                    title = stringResource(R.string.update_switch), subtitle = stringResource(R.string.update_switch_body),
                    checked = state.enabled, onCheckedChange = onToggle, shape = shape,
                )
            }
            item { shape ->
                SettingRow(
                    title = headline, subtitle = lastChecked, shape = shape,
                    // The result changes under the user's finger; have TalkBack read it out.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    below = {
                        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(onClick = onCheckNow, enabled = status != UpdateStatus.Checking) {
                                Text(stringResource(R.string.update_check_now))
                            }
                            if (status is UpdateStatus.Available) {
                                Button(onClick = { onDownload(status.release) }) { Text(stringResource(R.string.update_download)) }
                            }
                        }
                    },
                )
            }
            item { shape -> SettingRow(title = stringResource(R.string.update_disclosure_title), subtitle = stringResource(R.string.update_disclosure), shape = shape) }
        }
    }
}
