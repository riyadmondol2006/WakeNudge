package com.riyadm.wakenudge.domain.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic nights written as one character per 30-second epoch:
 * `R` restless (turning over), `t` a small twitch, `.` still, `_` no readings, `s` still with a snore.
 */
class SleepStageEstimatorTest {
    private val epochMinutes = 0.5

    private fun night(pattern: String, scale: Float = 1f, sound: Boolean = false, loudAt: Set<Int> = emptySet()): List<Epoch> =
        pattern.mapIndexed { i, c ->
            val wobble = 1f + ((i * 7) % 5 - 2) * 0.05f // small variation like a real sensor
            when (c) {
                'R' -> Epoch(i, 300, 0.20f * scale, 1.5f, 4, soundDb(sound, i, loudAt), peak(sound, i, loudAt))
                't' -> Epoch(i, 300, 0.012f * scale * wobble, 0.12f, 1, soundDb(sound, i, loudAt), peak(sound, i, loudAt))
                '.' -> Epoch(i, 300, 0.008f * scale * wobble, 0.03f, 0, soundDb(sound, i, loudAt), peak(sound, i, loudAt))
                's' -> Epoch(i, 300, 0.008f * scale * wobble, 0.03f, 0, -50f, -35f, snores = 2)
                '_' -> Epoch(i, 0, 0f, 0f, 0)
                else -> error("unknown epoch $c")
            }
        }

    private fun soundDb(on: Boolean, i: Int, loud: Set<Int>) = if (!on) null else if (i in loud) -40f else -62f
    private fun peak(on: Boolean, i: Int, loud: Set<Int>) = if (!on) null else if (i in loud) -25f else -58f

    private fun inBed(pattern: String) = pattern.length * SleepLimits.EPOCH_MS

    private fun minutes(ms: Long) = ms / 60_000.0

    // 15 min falling asleep, 10 min light, 30 min very still, light, an awake bout, more light, a quiet twitchy stretch
    // late in the night, then getting up.
    private val fallingAsleep = "RtR".repeat(10)
    private val settling = "..t.".repeat(5)
    private val stillRun = ".".repeat(60)
    private val light = "..t...t...".repeat(6)
    private val awakeBout = "RRRRRR"
    private val lateLight = "...t......".repeat(4)
    private val remLike = "t.t..t.t..".repeat(4)
    private val gettingUp = "RRRRRRRR"
    private val fullNight = fallingAsleep + settling + stillRun + light + awakeBout + lateLight + remLike + gettingUp

    @Test fun `a typical night gives latency, deep early, REM late and the awake bout`() {
        val s = SleepStageEstimator.estimate(night(fullNight), inBed(fullNight))
        assertEquals(NightVerdict.OK, s.verdict)
        assertEquals("latency is the falling-asleep part", 15.0, minutes(s.latencyMs!!), 0.01)
        // Deep only inside the long still run, after its lead-in and before its tail.
        // The still run is the 60 still epochs plus the still epochs touching it on either side.
        var runStart = fallingAsleep.length + settling.length
        while (fullNight[runStart - 1] == '.') runStart--
        var runEnd = fallingAsleep.length + settling.length + stillRun.length - 1
        while (fullNight[runEnd + 1] == '.') runEnd++
        val deepIdx = s.stages.indices.filter { s.stages[it] == SleepStage.DEEP }
        assertEquals((runStart + SleepStageEstimator.DEEP_LEAD_IN..runEnd - SleepStageEstimator.DEEP_TAIL).toList(), deepIdx)
        // REM only an hour or more after falling asleep, in the twitchy quiet stretch.
        val remIdx = s.stages.indices.filter { s.stages[it] == SleepStage.REM }
        assertTrue("REM found", remIdx.isNotEmpty())
        assertTrue("REM no earlier than an hour after onset", remIdx.all { it - 30 >= SleepStageEstimator.REM_EARLIEST })
        // The 3-minute awake bout is time awake after falling asleep, exactly.
        assertEquals(3.0, minutes(s.awakeAfterOnsetMs), 0.01)
        // Before falling asleep and after getting up is awake.
        assertTrue((0 until 30).all { s.stages[it] == SleepStage.AWAKE })
        assertTrue((fullNight.length - 6 until fullNight.length).all { s.stages[it] == SleepStage.AWAKE })
        assertEquals(s.deepMs + s.lightMs + s.remMs, s.asleepMs)
        assertEquals(s.inBedMs, s.asleepMs + s.awakeMs)
        assertFalse("over an hour is not short", s.short)
    }

    @Test fun `stages do not depend on how noisy this phone's sensor is`() {
        val quiet = SleepStageEstimator.estimate(night(fullNight), inBed(fullNight))
        val noisy = SleepStageEstimator.estimate(night(fullNight, scale = 3f), inBed(fullNight))
        assertEquals(quiet.stages, noisy.stages)
    }

    @Test fun `ten minutes of readings is the least that gives an estimate`() {
        val few = ".".repeat(19)
        assertEquals(NightVerdict.TOO_LITTLE_DATA, SleepStageEstimator.estimate(night(few), inBed(few)).verdict)
        val enough = "t" + ".".repeat(19)
        assertEquals(NightVerdict.OK, SleepStageEstimator.estimate(night(enough), inBed(enough)).verdict)
    }

    @Test fun `readings for less than half the time in bed say too little data instead of guessing`() {
        val gappy = fullNight.take(200) + "_".repeat(300)
        val s = SleepStageEstimator.estimate(night(gappy), inBed(gappy))
        assertEquals(NightVerdict.TOO_LITTLE_DATA, s.verdict)
        assertTrue(s.stages.isEmpty())
        assertEquals(0L, s.asleepMs)
        assertNull(s.latencyMs)
    }

    @Test fun `a session cut short by the process dying counts its time in bed, not missing epochs`() {
        // Epochs only for the first third; the session's time in bed says three times as long.
        val s = SleepStageEstimator.estimate(night(fullNight.take(100)), 3 * inBed(fullNight.take(100)))
        assertEquals(NightVerdict.TOO_LITTLE_DATA, s.verdict)
        // Exactly half is still enough.
        assertEquals(NightVerdict.OK, SleepStageEstimator.estimate(night(fullNight.take(100)), 2 * inBed(fullNight.take(100))).verdict)
    }

    @Test fun `gaps inside an otherwise good night get no stage and count towards nothing`() {
        val withGap = fullNight.substring(0, 150) + "_".repeat(20) + fullNight.substring(170)
        val s = SleepStageEstimator.estimate(night(withGap), inBed(withGap))
        assertEquals(NightVerdict.OK, s.verdict)
        assertTrue((150 until 170).all { s.stages[it] == null })
        val staged = s.stages.count { it != null } * SleepLimits.EPOCH_MS
        assertEquals(staged, s.asleepMs + s.awakeMs)
    }

    @Test fun `no movement at all for a long time means the phone was not on the bed`() {
        val flat = ".".repeat(400)
        val s = SleepStageEstimator.estimate(night(flat), inBed(flat))
        assertEquals(NightVerdict.NO_MOVEMENT, s.verdict)
        assertEquals(0L, s.deepMs)
    }

    @Test fun `never settling gives no sleep and no latency`() {
        val restless = "RRtR".repeat(60)
        val s = SleepStageEstimator.estimate(night(restless), inBed(restless))
        assertEquals(NightVerdict.OK, s.verdict)
        assertEquals(0L, s.asleepMs)
        assertNull(s.latencyMs)
        assertTrue(s.stages.all { it == SleepStage.AWAKE })
    }

    @Test fun `a single turn over in sleep stays sleep`() {
        val p = fallingAsleep + ".".repeat(30) + "R" + ".".repeat(30) + gettingUp
        val s = SleepStageEstimator.estimate(night(p), inBed(p))
        assertEquals(SleepStage.LIGHT, s.stages[60])
        assertEquals(0L, s.awakeAfterOnsetMs)
    }

    @Test fun `sessions under an hour are kept and marked short`() {
        val nap = "RtR".repeat(4) + "..t.".repeat(20)
        val s = SleepStageEstimator.estimate(night(nap), inBed(nap))
        assertTrue(s.short)
        assertEquals(NightVerdict.OK, s.verdict)
        assertTrue(s.asleepMs > 0)
    }

    @Test fun `snoring rules out REM where it would otherwise be`() {
        val snoring = remLike.replace('.', 's')
        val p = fallingAsleep + settling + stillRun + light + lateLight + snoring + gettingUp
        val withSnores = SleepStageEstimator.estimate(night(p, sound = true), inBed(p))
        val start = p.length - gettingUp.length - snoring.length
        assertTrue((start until start + snoring.length).none { withSnores.stages[it] == SleepStage.REM })
        val plain = fallingAsleep + settling + stillRun + light + lateLight + remLike + gettingUp
        val without = SleepStageEstimator.estimate(night(plain, sound = true), inBed(plain))
        assertTrue((start until start + remLike.length).any { without.stages[it] == SleepStage.REM })
    }

    @Test fun `a twitch during a loud sound that is not snoring counts as restless`() {
        val p = fallingAsleep + ".".repeat(40) + "ttt" + ".".repeat(40) + gettingUp
        val loud = setOf(70, 71, 72)
        val byIndex = night(p, sound = true, loudAt = loud).toTypedArray<Epoch?>()
        val motion = SleepStageEstimator.motions(byIndex)
        assertTrue(loud.all { motion[it] == SleepStageEstimator.Motion.RESTLESS })
        val s = SleepStageEstimator.estimate(night(p, sound = true, loudAt = loud), inBed(p))
        assertTrue("talking for a minute and a half is awake", loud.all { s.stages[it] == SleepStage.AWAKE })
        val quiet = SleepStageEstimator.estimate(night(p, sound = true), inBed(p))
        assertTrue(loud.none { quiet.stages[it] == SleepStage.AWAKE })
    }

    @Test fun `percentile is nearest rank`() {
        assertEquals(1f, SleepStageEstimator.percentile(listOf(5f, 1f, 3f, 2f, 4f), 0.1), 0f)
        assertEquals(5f, SleepStageEstimator.percentile(listOf(5f, 1f, 3f, 2f, 4f), 1.0), 0f)
        assertEquals(0f, SleepStageEstimator.percentile(emptyList(), 0.5), 0f)
    }
}
