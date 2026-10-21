package com.riyadm.wakenudge.data.repo

import android.util.Log
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.data.db.DiagnosticDao
import com.riyadm.wakenudge.data.db.DiagnosticEntity
import com.riyadm.wakenudge.domain.repository.DiagnosticRecord
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
class DiagnosticsLogImpl @Inject constructor(
    private val dao: DiagnosticDao,
    private val time: TimeSource,
) : DiagnosticsLog {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = AtomicInteger()

    override fun info(tag: String, message: String) {
        Log.i(TAG, "[$tag] $message")
        record("I", tag, message)
    }

    override fun warn(tag: String, message: String) {
        Log.w(TAG, "[$tag] $message")
        record("W", tag, message)
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        Log.e(TAG, "[$tag] $message", error)
        record("E", tag, if (error == null) message else "$message (${error::class.java.simpleName}: ${error.message})")
    }

    override fun observeRecent(limit: Int): Flow<List<DiagnosticRecord>> =
        dao.observeRecent(limit).map { list -> list.map { it.toRecord() } }

    override suspend fun recent(limit: Int): List<DiagnosticRecord> = dao.recent(limit).map { it.toRecord() }

    override suspend fun clear() = dao.clear()

    private fun record(level: String, tag: String, message: String) {
        val ts = time.nowMillis()
        scope.launch {
            runCatching {
                dao.insert(DiagnosticEntity(timestamp = ts, level = level, tag = tag, message = message))
                if (writes.incrementAndGet() % TRIM_EVERY == 0) dao.trim(KEEP)
            }
        }
    }

    private fun DiagnosticEntity.toRecord() = DiagnosticRecord(timestamp, level, tag, message)

    private companion object {
        const val TAG = "WakeNudge"
        const val KEEP = 500
        const val TRIM_EVERY = 25
    }
}
