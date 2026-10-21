package com.riyadm.wakenudge.data.backup

import android.content.Context
import android.net.Uri
import com.riyadm.wakenudge.BuildConfig
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.StopwatchState
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The file is bigger than any real backup could be; it is refused before it is read into memory. */
class BackupTooLargeException : IOException("backup file is too large")

/**
 * Local backup and restore as a JSON document the user keeps wherever they like (the UI hands over Storage Access
 * Framework URIs, so no storage permission is involved). Import never throws: every problem becomes an
 * [ImportResult.Failure], and a restore is one transaction, so a failed one leaves the app exactly as it was.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: BackupStore,
    private val scheduler: AlarmScheduler,
    private val surfaces: SurfaceRefresher,
    private val time: TimeSource,
    private val diagnostics: DiagnosticsLog,
) {

    /** The whole configuration as pretty-printed JSON; [includeHistory] adds every logged alarm event. */
    suspend fun export(includeHistory: Boolean): String {
        val current = store.read(includeHistory)
        val file = current.copy(
            appVersion = BuildConfig.VERSION_NAME,
            createdAt = time.nowMillis(),
            // A running stopwatch is a moment in time, not configuration.
            settings = current.settings.copy(stopwatch = StopwatchState()),
        )
        return withContext(Dispatchers.Default) { BackupCodec.encode(file) }
    }

    /**
     * Validates [json], sanitises every field, replaces the stored data atomically, then re-arms all alarms and
     * redraws the widgets. Warnings describe anything that was corrected, skipped or needs the user's attention.
     */
    suspend fun import(json: String): ImportResult {
        if (json.length > MAX_CHARS) return ImportResult.Failure(ImportFailure.TooLarge)
        return try {
            restore(json)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            diagnostics.error("Backup", "restore failed; nothing was changed", t)
            ImportResult.Failure(ImportFailure.StoreFailed(t.message ?: t.javaClass.simpleName))
        }
    }

    private suspend fun restore(json: String): ImportResult {
        val prepared = withContext(Dispatchers.Default) { BackupSanitizer.prepare(json) }
        if (prepared is BackupSanitizer.Prepared.Refused) return ImportResult.Failure(prepared.failure)
        prepared as BackupSanitizer.Prepared.Ready
        val file = prepared.file
        val warnings = prepared.warnings.toMutableList()

        store.replace(file)
        diagnostics.info(
            "Backup",
            "restored ${file.alarms.size} alarms, ${file.groups.size} groups, ${file.history?.size ?: "no"} history entries " +
                "(format ${file.formatVersion}, app ${file.appVersion.ifBlank { "?" }})",
        )
        try {
            scheduler.rescheduleAll("restore")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            diagnostics.error("Backup", "re-arming after restore failed", t)
            warnings += ImportWarning(R.string.backup_warn_reschedule_failed)
        }
        surfaces.refreshAll()

        val counts = ImportCounts(
            alarms = file.alarms.size,
            groups = file.groups.size,
            worldClocks = file.worldClocks.size,
            exceptionDates = file.exceptionDates.size,
            history = file.history?.size,
        )
        return ImportResult.Success(counts, warnings)
    }

    /** "wakenudge-backup-20261021-0730.json" */
    fun suggestedFileName(now: ZonedDateTime): String =
        "wakenudge-backup-${now.format(FILE_STAMP)}.json"

    /** Writes [json] (from [export]) to a document the user picked; throws [IOException] if it cannot be written. */
    suspend fun writeTo(uri: Uri, json: String) = withContext(Dispatchers.IO) {
        val stream = openForWriting(uri) ?: throw IOException("cannot open $uri for writing")
        stream.use {
            it.write(json.toByteArray(Charsets.UTF_8))
            it.flush()
        }
    }

    /** "wt" truncates what was there; a few document providers only know plain "w". */
    private fun openForWriting(uri: Uri) = try {
        context.contentResolver.openOutputStream(uri, "wt")
    } catch (_: IllegalArgumentException) {
        context.contentResolver.openOutputStream(uri, "w")
    }

    /** [export] straight into a picked document. */
    suspend fun exportTo(uri: Uri, includeHistory: Boolean) = writeTo(uri, export(includeHistory))

    /** Reads a picked document as text; throws [IOException] (or [BackupTooLargeException]) when that is impossible. */
    suspend fun readFrom(uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri for reading")
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_BYTES) throw BackupTooLargeException()
                out.write(buffer, 0, read)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    /** [readFrom] then [import], with I/O problems reported as an [ImportResult.Failure] like every other problem. */
    suspend fun importFrom(uri: Uri): ImportResult {
        val text = try {
            readFrom(uri)
        } catch (_: BackupTooLargeException) {
            return ImportResult.Failure(ImportFailure.TooLarge)
        } catch (e: IOException) {
            return ImportResult.Failure(ImportFailure.Unreadable(e.message ?: e.javaClass.simpleName))
        } catch (e: SecurityException) {
            return ImportResult.Failure(ImportFailure.Unreadable(e.message ?: e.javaClass.simpleName))
        }
        return import(text)
    }

    private companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_CHARS = MAX_BYTES
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
    }
}
