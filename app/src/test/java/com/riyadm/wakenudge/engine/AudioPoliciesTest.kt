package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.domain.model.SoundMode
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.domain.model.SoundSource
import com.riyadm.wakenudge.domain.model.VibrationPattern
import com.riyadm.wakenudge.domain.model.VibrationSettings
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.engine.audio.BundledTones
import com.riyadm.wakenudge.engine.audio.SoundResolver
import com.riyadm.wakenudge.engine.audio.VibrationPatterns
import com.riyadm.wakenudge.engine.audio.VolumePlanner
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumePlannerTest {
    private fun plan(
        v: VolumeSettings = VolumeSettings(),
        max: Int = 7,
        current: Int = 3,
        headset: Boolean = false,
        cap: Boolean = true,
        capPercent: Int = 60,
        escalated: Boolean = false,
        lufs: Double? = -16.0,
    ) = VolumePlanner.plan(v, max, current, headset, cap, capPercent, escalated, lufs)

    @Test fun `level maps to a stream index rounded up and never to zero`() {
        assertEquals(6, plan(VolumeSettings(level = 80)).streamIndex)
        assertEquals(1, plan(VolumeSettings(level = 1)).streamIndex)
        assertEquals(7, plan(VolumeSettings(level = 100)).streamIndex)
    }

    @Test fun `system volume mode leaves the stream alone unless it is muted`() {
        assertNull(plan(VolumeSettings(level = 0), current = 4).streamIndex)
        // Muted alarm stream: never ring silently.
        assertEquals(VolumePlanner.percentToIndex(VolumePlanner.MIN_AUDIBLE_PERCENT, 7), plan(VolumeSettings(level = 0), current = 0).streamIndex)
    }

    @Test fun `max system volume option raises to the top`() {
        assertEquals(7, plan(VolumeSettings(level = 30, useMaxSystemVolume = true)).streamIndex)
    }

    @Test fun `escalation raises to max only when the alarm allows it`() {
        assertEquals(7, plan(VolumeSettings(level = 50, escalateWhenIgnored = true), escalated = true).streamIndex)
        assertEquals(4, plan(VolumeSettings(level = 50, escalateWhenIgnored = false), escalated = true).streamIndex)
    }

    @Test fun `headphones are capped even for max volume and extra loud`() {
        val p = plan(VolumeSettings(level = 100, useMaxSystemVolume = true, extraLoud = true), headset = true, escalated = true)
        assertEquals(VolumePlanner.percentToIndex(60, 7), p.streamIndex)
        assertEquals(0, p.enhancerMillibels)
        assertTrue(p.cappedForHearing)
    }

    @Test fun `hearing cap can be switched off by the user`() {
        val p = plan(VolumeSettings(level = 100), headset = true, cap = false)
        assertEquals(7, p.streamIndex)
        assertFalse(p.cappedForHearing)
    }

    @Test fun `phone speaker is never capped`() {
        assertFalse(plan(VolumeSettings(level = 100), headset = false).cappedForHearing)
    }

    @Test fun `extra loud boosts quiet bundled tones towards the target but never past the ceiling`() {
        // -16 LUFS tone -> +6 dB to reach -10 LUFS.
        assertEquals(600, plan(VolumeSettings(extraLoud = true), lufs = -16.0).enhancerMillibels)
        // An already loud tone still gets a modest, audible boost at 100 %.
        assertEquals(300, plan(VolumeSettings(extraLoud = true), lufs = -9.0).enhancerMillibels)
        // Unknown custom audio uses the conservative fixed boost.
        assertEquals(600, plan(VolumeSettings(extraLoud = true), lufs = null).enhancerMillibels)
        assertTrue(plan(VolumeSettings(extraLoud = true), lufs = -30.0, escalated = true).enhancerMillibels <= VolumePlanner.MAX_ENHANCER_MB)
    }

    @Test fun `extra loud strength scales the boost proportionally`() {
        fun boost(percent: Int, lufs: Double? = -16.0) = plan(VolumeSettings(extraLoud = true, extraLoudPercent = percent), lufs = lufs).enhancerMillibels
        assertEquals(600, boost(100))
        assertEquals(300, boost(50))
        assertEquals(60, boost(10))
        assertEquals(0, plan(VolumeSettings(extraLoud = false, extraLoudPercent = 100)).enhancerMillibels)
        // Unknown custom audio scales its fixed boost too.
        assertEquals(300, boost(50, lufs = null))
        // Never decreasing as the slider moves up, never above the ceiling.
        var last = -1
        for (p in 1..100) {
            val b = boost(p, lufs = -30.0)
            assertTrue("boost must not shrink as strength grows (at $p)", b >= last)
            assertTrue(b <= VolumePlanner.MAX_ENHANCER_MB)
            last = b
        }
    }

    @Test fun `the strength an alarm gets is zero when off and clamped when on`() {
        assertEquals(0, VolumeSettings(extraLoud = false, extraLoudPercent = 80).extraLoudStrength)
        assertEquals(100, VolumeSettings(extraLoud = true, extraLoudPercent = 100).extraLoudStrength)
        assertEquals(100, VolumeSettings(extraLoud = true, extraLoudPercent = 500).extraLoudStrength)
        assertEquals(1, VolumeSettings(extraLoud = true, extraLoudPercent = -9).extraLoudStrength)
    }

    @Test fun `extra loud stays off on headphones whatever the strength`() {
        val p = plan(VolumeSettings(level = 100, extraLoud = true, extraLoudPercent = 100), headset = true, cap = true)
        assertEquals(0, p.enhancerMillibels)
    }

    @Test fun `no boost without extra loud or escalation`() {
        assertEquals(0, plan(VolumeSettings(extraLoud = false)).enhancerMillibels)
    }

    @Test fun `ramp starts quiet, ends at full and rises monotonically`() {
        assertEquals(1f, VolumePlanner.rampGain(0, 0, 10), 0f)
        assertEquals(0.1f, VolumePlanner.rampGain(0, 60, 10), 0.001f)
        assertEquals(1f, VolumePlanner.rampGain(60_000, 60, 10), 0.001f)
        assertEquals(1f, VolumePlanner.rampGain(10 * 60_000, 60, 10), 0f)
        val samples = (0..60).map { VolumePlanner.rampGain(it * 1000L, 60, 10) }
        assertEquals(samples.sorted(), samples)
        // Geometric growth: halfway in time is the geometric mean of start and end (linear in dB).
        assertEquals(Math.sqrt(0.1).toFloat(), VolumePlanner.rampGain(30_000, 60, 10), 0.001f)
    }
}

class SoundResolverTest {
    @Test fun `single bundled tone resolves to itself and unknown ids fall back to the default`() {
        assertEquals("bell", SoundResolver.resolve(SoundSettings(bundledId = "bell")).bundledId)
        assertEquals(SoundSettings.DEFAULT_TONE_ID, SoundResolver.resolve(SoundSettings(bundledId = "nonexistent")).bundledId)
    }

    @Test fun `custom source without a uri falls back to a bundled tone`() {
        val r = SoundResolver.resolve(SoundSettings(source = SoundSource.CUSTOM, uri = null))
        assertTrue(r.isBundled)
    }

    @Test fun `system and custom uris loop`() {
        val r = SoundResolver.resolve(SoundSettings(source = SoundSource.SYSTEM, uri = "content://media/internal/audio/media/1"))
        assertEquals("content://media/internal/audio/media/1", r.uri)
        assertTrue(r.loop)
    }

    @Test fun `random bundled honours the pool and eventually uses every member`() {
        val pool = setOf("bell", "chime", "pulse")
        val s = SoundSettings(mode = SoundMode.RANDOM_BUNDLED, randomPool = pool)
        val rnd = Random(42)
        val seen = (1..200).map { SoundResolver.resolve(s, rnd).bundledId!! }.toSet()
        assertEquals(pool, seen)
    }

    @Test fun `empty random pool means every bundled tone`() {
        val s = SoundSettings(mode = SoundMode.RANDOM_BUNDLED)
        val rnd = Random(1)
        val seen = (1..500).map { SoundResolver.resolve(s, rnd).bundledId!! }.toSet()
        assertEquals(BundledTones.all.map { it.id }.toSet(), seen)
    }

    @Test fun `random custom picks from the playlist and chains without immediate repeats`() {
        val playlist = listOf("content://a", "content://b", "content://c")
        val s = SoundSettings(mode = SoundMode.RANDOM_CUSTOM, playlist = playlist)
        val rnd = Random(7)
        repeat(50) {
            val first = SoundResolver.resolve(s, rnd)
            assertTrue(first.uri in playlist)
            assertFalse(first.loop) // songs play through, then chain
            val next = SoundResolver.nextFromPlaylist(s, first.uri, rnd)!!
            assertNotEquals(first.uri, next.uri)
        }
    }

    @Test fun `a single-track playlist loops`() {
        val s = SoundSettings(mode = SoundMode.RANDOM_CUSTOM, playlist = listOf("content://only"))
        assertTrue(SoundResolver.resolve(s).loop)
        assertEquals("content://only", SoundResolver.nextFromPlaylist(s, "content://only")?.uri)
    }

    @Test fun `empty custom playlist falls back instead of staying silent`() {
        assertTrue(SoundResolver.resolve(SoundSettings(mode = SoundMode.RANDOM_CUSTOM)).isBundled)
        assertNull(SoundResolver.nextFromPlaylist(SoundSettings(mode = SoundMode.RANDOM_CUSTOM), null))
    }

    @Test fun `every selectable bundled tone has loudness metadata in a sane range`() {
        BundledTones.all.forEach { assertTrue("${it.id} ${it.lufs}", it.lufs in -20.0..-9.0) }
        assertNotNull(BundledTones.byId("siren"))
        assertEquals(12, BundledTones.all.map { it.id }.toSet().size)
    }
}

class VibrationPatternsTest {
    @Test fun `every preset yields aligned arrays with at least one vibrating step`() {
        VibrationPattern.entries.forEach { p ->
            val w = VibrationPatterns.waveform(VibrationSettings(pattern = p, customTimings = listOf(0, 300, 200, 300)), false)
            assertEquals(p.name, w.timings.size, w.amplitudes.size)
            assertTrue(p.name, w.amplitudes.any { it > 0 })
            assertTrue(p.name, w.timings.all { it >= 0 })
            assertTrue(p.name, w.amplitudes.all { it in 0..255 })
        }
    }

    @Test fun `strength scales the amplitude`() {
        val full = VibrationPatterns.waveform(VibrationSettings(pattern = VibrationPattern.CLASSIC, strength = 100), false)
        val half = VibrationPatterns.waveform(VibrationSettings(pattern = VibrationPattern.CLASSIC, strength = 50), false)
        assertEquals(255, full.amplitudes.max())
        assertEquals(128, half.amplitudes.max())
    }

    @Test fun `an intensified alarm always vibrates at full strength with shorter pauses`() {
        val s = VibrationSettings(pattern = VibrationPattern.PULSE, strength = 30)
        val normal = VibrationPatterns.waveform(s, false)
        val intense = VibrationPatterns.waveform(s, true)
        assertEquals(255, intense.amplitudes.max())
        assertTrue(intense.timings.sum() < normal.timings.sum())
    }

    @Test fun `custom patterns are sanitised`() {
        val empty = VibrationPatterns.waveform(VibrationSettings(pattern = VibrationPattern.CUSTOM, customTimings = emptyList()), false)
        assertTrue(empty.amplitudes.any { it > 0 })
        val huge = VibrationPatterns.waveform(VibrationSettings(pattern = VibrationPattern.CUSTOM, customTimings = listOf(0, 99_999, 0, 5)), false)
        assertTrue(huge.timings.all { it <= VibrationPatterns.MAX_STEP_MS })
        val long = VibrationPatterns.waveform(VibrationSettings(pattern = VibrationPattern.CUSTOM, customTimings = List(200) { 100L }), false)
        assertTrue(long.timings.size <= VibrationPatterns.MAX_CUSTOM_STEPS + 1)
    }
}
