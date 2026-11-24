package com.riyadm.wakenudge.data.sleep

import android.content.Context
import androidx.core.content.edit
import com.riyadm.wakenudge.domain.sleep.ClipPolicy
import com.riyadm.wakenudge.domain.sleep.Epoch
import com.riyadm.wakenudge.domain.sleep.Night
import com.riyadm.wakenudge.domain.sleep.SessionEnd
import com.riyadm.wakenudge.domain.sleep.SessionRules
import com.riyadm.wakenudge.domain.sleep.SleepLimits
import com.riyadm.wakenudge.domain.sleep.SleepStageEstimator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/** A kept snore clip as the screens see it. */
data class SnoreClip(
    val id: Long,
    val sessionId: Long,
    val recordedAt: Long,
    val durationMs: Long,
    val peakDb: Float,
    val aboveFloorDb: Float,
    val envelope: List<Int>,
    val fileName: String,
) {
    val loudness: ClipPolicy.Loudness get() = ClipPolicy.loudness(aboveFloorDb)
}

/** Everything a night's detail shows. */
data class NightDetail(
    val night: Night,
    val epochs: List<Epoch>,
    val clips: List<SnoreClip>,
    /** When the microphone stopped because an alarm rang, if it did. */
    val micStoppedAt: Long?,
)

/** Clip files: the app's private no-backup folder, and a cache folder for the one being shared. */
@Singleton
class ClipFiles(private val root: File, private val shareDir: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "sleep_clips"), File(context.cacheDir, "sleep_share"))

    val dir: File get() = root.apply { mkdirs() }

    fun file(name: String): File = File(dir, File(name).name)

    fun newFile(sessionId: Long, atMs: Long): File = File(dir, "snore-$sessionId-$atMs.m4a")

    fun delete(name: String) {
        runCatching { file(name).delete() }
    }

    /** The clip copied where the share sheet may read it; older shared copies are removed first. */
    fun shareCopy(name: String): File? {
        clearShared()
        val source = file(name).takeIf { it.isFile } ?: return null
        val dir = shareDir.apply { mkdirs() }
        return runCatching { source.copyTo(File(dir, source.name), overwrite = true) }.getOrNull()
    }

    fun clearShared() {
        runCatching { shareDir.listFiles()?.forEach { it.delete() } }
    }

    /** Files in the clip folder that no clip row names (a crash between writing the file and the row). */
    fun deleteOrphans(known: Set<String>) {
        runCatching { dir.listFiles()?.filter { it.name !in known }?.forEach { it.delete() } }
    }

    fun deleteAll() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        clearShared()
    }
}

/** Sleep tracking's data: sessions, their epochs and the snore clips, with the clean-up rules. */
@Singleton
class SleepRepository @Inject constructor(
    private val dao: SleepDao,
    private val files: ClipFiles,
) {
    suspend fun startSession(startedAt: Long, micUsed: Boolean): Long =
        dao.insertSession(SleepSessionEntity(startedAt = startedAt, micUsed = micUsed))

    suspend fun appendEpochs(sessionId: Long, epochs: List<Epoch>) {
        if (epochs.isNotEmpty()) dao.insertEpochs(epochs.map { SleepEpochEntity.of(sessionId, it) })
    }

    suspend fun noteMicStopped(sessionId: Long, at: Long) {
        val s = dao.session(sessionId) ?: return
        if (s.micStoppedAt == null && s.micUsed) dao.updateSession(s.copy(micStoppedAt = at))
    }

    suspend fun endSession(sessionId: Long, endedAt: Long, reason: SessionEnd) {
        val s = dao.session(sessionId) ?: return
        if (s.endedAt == null) dao.updateSession(s.copy(endedAt = endedAt.coerceAtLeast(s.startedAt), endReason = reason.name))
    }

    /** Closes sessions a dead process left open, except [runningId]. They end after their last stored epoch. */
    suspend fun closeStale(runningId: Long?) {
        for (s in dao.openSessions()) {
            if (s.id == runningId) continue
            dao.updateSession(s.copy(endedAt = SessionRules.closeStale(s.startedAt, dao.lastEpochIndex(s.id)), endReason = SessionEnd.STOPPED_BY_SYSTEM.name))
        }
    }

    private suspend fun toNight(s: SleepSessionEntity): Night {
        val end = s.endedAt ?: s.startedAt
        val epochs = dao.epochs(s.id).map { it.toEpoch() }
        return Night(s.id, s.startedAt, end, SleepStageEstimator.estimate(epochs, end - s.startedAt), s.micUsed)
    }

    /** Finished nights that ended in [fromMs, toMs), with their estimates. */
    fun observeNights(fromMs: Long, toMs: Long): Flow<List<Night>> =
        dao.observeFinishedBetween(fromMs, toMs).distinctUntilChanged().mapLatest { list -> list.map { toNight(it) } }.flowOn(Dispatchers.Default)

    fun observeLastNight(): Flow<Night?> =
        dao.observeLastFinished().distinctUntilChanged().mapLatest { it?.let { s -> toNight(s) } }.flowOn(Dispatchers.Default)

    fun observeHasAny(): Flow<Boolean> = dao.observeCount().map { it > 0 }.distinctUntilChanged()

    suspend fun night(sessionId: Long): NightDetail? = withContext(Dispatchers.Default) {
        val s = dao.session(sessionId)?.takeIf { it.endedAt != null } ?: return@withContext null
        NightDetail(toNight(s), dao.epochs(s.id).map { it.toEpoch() }, clipsOf(s.id), s.micStoppedAt)
    }

    // ---- clips ---------------------------------------------------------------------------------------------------

    private fun SnoreClipEntity.toClip() = SnoreClip(
        id, sessionId, recordedAt, durationMs, peakDb, aboveFloorDb,
        envelope.split(',').mapNotNull { it.trim().toIntOrNull()?.coerceIn(0, 100) }, fileName,
    )

    suspend fun clipsOf(sessionId: Long): List<SnoreClip> = dao.clipsOf(sessionId).map { it.toClip() }

    /** Every clip still within its seven days, newest first. */
    fun observeClips(nowMs: () -> Long): Flow<List<SnoreClip>> =
        dao.observeAllClips().map { list -> list.filterNot { ClipPolicy.expired(it.recordedAt, nowMs()) }.map { it.toClip() } }

    suspend fun addClip(clip: SnoreClipEntity): Long = dao.insertClip(clip)

    suspend fun deleteClip(id: Long) {
        val clip = dao.clip(id) ?: return
        dao.deleteClip(id)
        files.delete(clip.fileName)
    }

    suspend fun deleteAllClips() {
        dao.deleteAllClips()
        files.deleteAll()
    }

    suspend fun deleteNight(sessionId: Long) {
        val clips = dao.clipsOf(sessionId)
        dao.deleteSession(sessionId)
        clips.forEach { files.delete(it.fileName) }
    }

    fun clipFile(clip: SnoreClip): File = files.file(clip.fileName)

    fun shareCopy(clip: SnoreClip): File? = files.shareCopy(clip.fileName)

    /**
     * The retention rules: clips after [SleepLimits.CLIP_RETENTION_MS], nights after [SleepLimits.SESSION_RETENTION_MS],
     * clip files no row names, and the copy left from the last share. Returns when the next clip expires, or null.
     */
    suspend fun cleanUp(nowMs: Long, runningId: Long?): Long? {
        for (s in dao.finishedBefore(nowMs - SleepLimits.SESSION_RETENTION_MS)) deleteNight(s.id)
        val clips = dao.allClips()
        clips.filter { ClipPolicy.expired(it.recordedAt, nowMs) }.forEach { deleteClip(it.id) }
        val kept = dao.allClips()
        // While tracking runs, a clip file can exist a moment before its row: leave the folder alone then.
        if (runningId == null) files.deleteOrphans(kept.map { it.fileName }.toSet())
        files.clearShared()
        return kept.minOfOrNull { it.recordedAt }?.plus(SleepLimits.CLIP_RETENTION_MS)
    }
}

/** The one setting sleep tracking has. Credential-encrypted, like the rest of its data. */
@Singleton
class SleepPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("sleep_track", Context.MODE_PRIVATE)
    private val state = kotlinx.coroutines.flow.MutableStateFlow(prefs.getBoolean(KEY_SNORING, false))
    val recordSnoring: kotlinx.coroutines.flow.StateFlow<Boolean> = state

    fun setRecordSnoring(on: Boolean) {
        prefs.edit { putBoolean(KEY_SNORING, on) }
        state.value = on
    }

    private companion object {
        const val KEY_SNORING = "record_snoring"
    }
}
