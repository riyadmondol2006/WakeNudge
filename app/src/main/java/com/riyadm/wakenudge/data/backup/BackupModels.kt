package com.riyadm.wakenudge.data.backup

import android.content.Context
import androidx.annotation.AnyRes
import androidx.annotation.StringRes
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.ExceptionDate
import com.riyadm.wakenudge.domain.model.WorldClockCity
import kotlinx.serialization.Serializable

object BackupFormat {
    const val APP_ID = "WakeNudge"

    /** Bumped only for changes an older reader could not understand; additive fields keep the same version. */
    const val VERSION = 1
}

/**
 * The on-disk backup document. Timers and in-flight state (ringing session, wake checks, stopwatch) are deliberately
 * not part of it: they describe a moment, not the user's configuration. [history] is null when the user left it out.
 */
@Serializable
data class BackupFile(
    val formatVersion: Int = BackupFormat.VERSION,
    val app: String = BackupFormat.APP_ID,
    val appVersion: String = "",
    val createdAt: Long = 0,
    val settings: AppSettings = AppSettings(),
    val alarms: List<Alarm> = emptyList(),
    val groups: List<AlarmGroup> = emptyList(),
    val worldClocks: List<WorldClockCity> = emptyList(),
    val exceptionDates: List<ExceptionDate> = emptyList(),
    val history: List<AlarmEvent>? = null,
)

/** What a restore put into the app. [history] is null when the backup carried none and the stored history stayed. */
data class ImportCounts(
    val alarms: Int,
    val groups: Int,
    val worldClocks: Int,
    val exceptionDates: Int,
    val history: Int?,
)

/**
 * Something the user should know about a restore that otherwise succeeded. [message] is a string resource, or a
 * plurals resource when [count] is set (the count is then also the first format argument).
 */
data class ImportWarning(@AnyRes val message: Int, val count: Int? = null, val args: List<Any> = emptyList()) {
    fun text(context: Context): String =
        if (count != null) context.resources.getQuantityString(message, count, count, *args.toTypedArray())
        else context.getString(message, *args.toTypedArray())
}

/** Why a file was refused. Nothing is changed in the app when an import fails. */
sealed class ImportFailure(@StringRes val message: Int, val args: List<Any> = emptyList()) {
    fun text(context: Context): String = context.getString(message, *args.toTypedArray())

    /** The text is not JSON at all (a wrong file, a truncated download, a binary). */
    data object NotJson : ImportFailure(R.string.backup_fail_not_json)

    /** Valid JSON, but not written by WakeNudge. */
    data class WrongApp(val found: String?) : ImportFailure(R.string.backup_fail_wrong_app)

    /** Written by a newer version of the format than this build understands. */
    data class NewerFormat(val found: Int, val supported: Int) :
        ImportFailure(R.string.backup_fail_newer_format, listOf(found, supported))

    /** A WakeNudge file whose structure is unusable, e.g. no format version. */
    data class Malformed(val detail: String) : ImportFailure(R.string.backup_fail_malformed, listOf(detail))

    data object TooLarge : ImportFailure(R.string.backup_fail_too_large)

    /** The file could not be opened or read. */
    data class Unreadable(val detail: String) : ImportFailure(R.string.backup_fail_unreadable, listOf(detail))

    /** The database refused the write; the previous data is untouched because the replacement is one transaction. */
    data class StoreFailed(val detail: String) : ImportFailure(R.string.backup_fail_store, listOf(detail))
}

sealed interface ImportResult {
    data class Success(val counts: ImportCounts, val warnings: List<ImportWarning>) : ImportResult
    data class Failure(val reason: ImportFailure) : ImportResult
}
