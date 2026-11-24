package com.riyadm.wakenudge.sleeptrack

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.data.sleep.ClipFiles
import com.riyadm.wakenudge.data.sleep.SleepDatabase
import com.riyadm.wakenudge.data.sleep.SleepRepository
import com.riyadm.wakenudge.data.sleep.SnoreClipEntity
import com.riyadm.wakenudge.domain.sleep.Epoch
import com.riyadm.wakenudge.domain.sleep.SessionEnd
import com.riyadm.wakenudge.domain.sleep.SleepLimits
import com.riyadm.wakenudge.engine.sleeptrack.ClipEncoder
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Sleep tracking's storage on a real device: the database, the retention rules with real files, and the clip encoder. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SleepStorageInstrumentedTest {
    // The app's receivers need a component, as in the other instrumented tests.
    @get:Rule val hilt = HiltAndroidRule(this)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SleepDatabase
    private lateinit var files: ClipFiles
    private lateinit var repo: SleepRepository
    private val day = 24 * 60 * 60_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SleepDatabase::class.java).build()
        // Scratch folders, so the app's own clips on the test phone are never touched.
        val scratch = File(context.cacheDir, "sleep-storage-test").apply { deleteRecursively() }
        files = ClipFiles(File(scratch, "clips"), File(scratch, "share"))
        repo = SleepRepository(db.sleepDao(), files)
    }

    @After fun tearDown() {
        File(context.cacheDir, "sleep-storage-test").deleteRecursively()
        db.close()
    }

    private fun epochs(n: Int) = (0 until n).map { Epoch(it, 300, if (it % 7 == 0) 0.2f else 0.008f, 0.5f, if (it % 7 == 0) 3 else 0) }

    private suspend fun clip(sessionId: Long, at: Long): Long {
        val file = files.newFile(sessionId, at).apply { writeBytes(ByteArray(16)) }
        return repo.addClip(SnoreClipEntity(sessionId = sessionId, recordedAt = at, durationMs = 10_000, peakDb = -30f, aboveFloorDb = 20f, fileName = file.name, envelope = "1,2,3"))
    }

    @Test fun aNightIsStoredEstimatedAndDeletedWithItsClips() = runBlocking {
        val start = 1_000_000_000_000L
        val id = repo.startSession(start, micUsed = true)
        repo.appendEpochs(id, epochs(600))
        repo.endSession(id, start + 600 * SleepLimits.EPOCH_MS, SessionEnd.USER)
        val clipId = clip(id, start + 60_000)
        val detail = repo.night(id)!!
        assertEquals(600, detail.epochs.size)
        assertEquals(listOf(clipId), detail.clips.map { it.id })
        assertEquals(listOf(1, 2, 3), detail.clips.single().envelope)
        assertTrue(repo.clipFile(detail.clips.single()).isFile)
        repo.deleteNight(id)
        assertNull(repo.night(id))
        assertTrue(db.sleepDao().allClips().isEmpty())
        assertFalse(File(files.dir, detail.clips.single().fileName).exists())
    }

    @Test fun clipsGoAfterSevenDaysNightsAfterAYearAndStrayFilesAtOnce() = runBlocking {
        val now = 2_000_000_000_000L
        val old = repo.startSession(now - 400 * day, micUsed = false).also { repo.endSession(it, now - 400 * day + 8 * 3_600_000L, SessionEnd.USER) }
        val recent = repo.startSession(now - 8 * day, micUsed = true).also { repo.endSession(it, now - 8 * day + 8 * 3_600_000L, SessionEnd.USER) }
        val expired = clip(recent, now - 8 * day)
        val fresh = clip(recent, now - 2 * day)
        File(files.dir, "stray.m4a").writeBytes(ByteArray(4))
        val next = repo.cleanUp(now, runningId = null)
        assertNull("a night over a year old is gone", db.sleepDao().session(old))
        assertNotNull(db.sleepDao().session(recent))
        assertEquals(listOf(fresh), db.sleepDao().allClips().map { it.id })
        assertFalse(db.sleepDao().allClips().any { it.id == expired })
        assertEquals(setOf(db.sleepDao().allClips().single().fileName), files.dir.listFiles()!!.map { it.name }.toSet())
        assertEquals("the next run is when the fresh clip expires", now - 2 * day + SleepLimits.CLIP_RETENTION_MS, next)
    }

    @Test fun aSessionLeftOpenByADeadProcessEndsAfterItsLastEpoch() = runBlocking {
        val start = 1_500_000_000_000L
        val id = repo.startSession(start, micUsed = false)
        repo.appendEpochs(id, epochs(40))
        repo.closeStale(runningId = null)
        val s = db.sleepDao().session(id)!!
        assertEquals(start + 40 * SleepLimits.EPOCH_MS, s.endedAt)
        assertEquals(SessionEnd.STOPPED_BY_SYSTEM.name, s.endReason)
        assertEquals(id, repo.observeLastNight().first()!!.sessionId)
    }

    @Test fun deletingAllClipsKeepsTheNights() = runBlocking {
        val id = repo.startSession(1_000L, micUsed = true).also { repo.endSession(it, 3_700_000L, SessionEnd.USER) }
        clip(id, 2_000L)
        clip(id, 400_000L)
        repo.deleteAllClips()
        assertTrue(db.sleepDao().allClips().isEmpty())
        assertTrue(files.dir.listFiles().isNullOrEmpty())
        assertNotNull(db.sleepDao().session(id))
    }

    @Test fun theEncoderWritesAPlayableAacClip() {
        val rate = 16_000
        val pcm = ShortArray(rate * 2) { (8_000 * sin(2 * PI * 180 * it / rate)).toInt().toShort() }
        val out = File(context.cacheDir, "encoder-test.m4a").apply { delete() }
        assertTrue(ClipEncoder.encode(pcm, rate, out))
        assertTrue("compressed: ${out.length()} bytes", out.length() in 1..(pcm.size * 2 / 4))
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(out.path)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            assertTrue("about two seconds: $durationUs", durationUs in 1_800_000L..2_200_000L)
        } finally {
            extractor.release()
            out.delete()
        }
    }
}
