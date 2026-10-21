package com.riyadm.wakenudge.ui.backup

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.data.backup.BackupManager
import com.riyadm.wakenudge.data.backup.ImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A result banner: a headline (resource + optional arguments) and bullet details. */
class BackupMessage(@StringRes private val res: Int, private val args: List<Any> = emptyList(), val details: List<String> = emptyList()) {
    fun text(context: Context): String = context.getString(res, *args.toTypedArray())
}

data class BackupUiState(val working: Boolean = false, val message: BackupMessage? = null, val isError: Boolean = false)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val manager: BackupManager,
    private val time: TimeSource,
) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun suggestedFileName(): String = manager.suggestedFileName(time.now())

    fun export(uri: Uri, includeHistory: Boolean) {
        viewModelScope.launch {
            _state.value = BackupUiState(working = true)
            _state.value = try {
                manager.exportTo(uri, includeHistory)
                BackupUiState(message = BackupMessage(R.string.backup_export_done))
            } catch (t: Throwable) {
                BackupUiState(message = BackupMessage(R.string.backup_export_failed, listOf(t.message ?: t.javaClass.simpleName)), isError = true)
            }
        }
    }

    fun restore(uri: Uri, context: Context) {
        viewModelScope.launch {
            _state.value = BackupUiState(working = true)
            _state.value = when (val result = manager.importFrom(uri)) {
                is ImportResult.Success -> {
                    val c = result.counts
                    BackupUiState(
                        message = BackupMessage(
                            R.string.backup_restore_done,
                            listOf(c.alarms, c.groups),
                            result.warnings.map { it.text(context) },
                        ),
                    )
                }
                is ImportResult.Failure -> BackupUiState(message = BackupMessage(R.string.backup_restore_failed, listOf(result.reason.text(context))), isError = true)
            }
        }
    }
}
