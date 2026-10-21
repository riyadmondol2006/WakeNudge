package com.riyadm.wakenudge.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.AudioRoute
import com.riyadm.wakenudge.domain.model.SoundMode
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.domain.model.SoundSource
import com.riyadm.wakenudge.domain.model.VibrationSettings
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.engine.audio.AlarmAudioEngine
import com.riyadm.wakenudge.engine.audio.VolumePlanner
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.IOException
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays real alarms on the device, quietly (alarm-stream volume 1), and attacks them the way a real morning does:
 * another app grabbing audio focus, the media player dying or erroring, a slow or missing sound file, a ring that ends
 * while its sound is still loading, headphones plugged and pulled. Whatever happens, the alarm must stay audible.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AudioResilienceInstrumentedTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var audio: AlarmAudioEngine
    @Inject lateinit var runtime: RuntimeState

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private var originalAlarmVolume = 0

    @Before fun setUp() {
        hilt.inject()
        originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        runtime.lastAudioProblem = ""
        runtime.clearRingCrashes()
    }

    @After fun tearDown() {
        onMain { audio.stop() }
        audio.prepareHook = null
        audio.random = Random.Default
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0)
        runtime.savedAlarmVolume = -1
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private fun quiet(sound: SoundSettings = SoundSettings(), level: Int = 1) = Alarm(
        id = 7001, label = "audio test", sound = sound,
        volume = VolumeSettings(level = level, rampSeconds = 0, rampStartPercent = 100, escalateWhenIgnored = false),
        vibration = VibrationSettings(enabled = false),
    )

    /** SYSTEM_DEFAULT so that a pretend headset really counts as the route (the phone-speaker option would pin it away). */
    private val settings = AppSettings(audioRoute = AudioRoute.SYSTEM_DEFAULT, hearingSafetyCap = true, headphoneCapPercent = 30)

    private fun startRing(alarm: Alarm = quiet(), appSettings: AppSettings = settings, kind: RingKind = RingKind.PRIMARY) =
        runBlocking(Dispatchers.Main) { audio.start(alarm, appSettings, kind, false) }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(what: String, timeoutMs: Long = 6_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(40)
        }
        fail("timed out waiting for: $what")
    }

    private fun stays(what: String, durationMs: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < end) {
            assertTrue("lost: $what", condition())
            Thread.sleep(40)
        }
    }

    private fun resourceTone(rawRes: Int) = "android.resource://${context.packageName}/$rawRes"

    // ---- the basics ------------------------------------------------------------------------------------------

    @Test fun anAlarmStartsAudibleAndStopsCompletelyAndPutsTheVolumeBack() {
        startRing()
        await("sound") { audio.isAudible }
        assertEquals("quiet but not muted", 1, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
        onMain { audio.stop() }
        assertFalse("silent once stopped", audio.isAudible)
        assertEquals("alarm volume restored", originalAlarmVolume, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test fun aRingThatEndsWhileItsSoundIsStillLoadingNeverPlaysAfterwards() {
        audio.prepareHook = { Thread.sleep(900) } // slow storage: the sound opens after the ring is already over
        val starting = CoroutineScope(Dispatchers.Main).launch { audio.start(quiet(), settings, RingKind.PRIMARY, false) }
        Thread.sleep(250)
        onMain { audio.stop() }
        runBlocking { starting.join() }
        Thread.sleep(1_500)
        assertFalse("a late arrival must not start playing for an alarm that is over", audio.isAudible)
        assertFalse(audio.isPlaying)
    }

    // ---- other apps, calls and assistants ---------------------------------------------------------------------

    @Test fun anotherAppTakingAudioFocusDoesNotSilenceTheAlarm() {
        startRing()
        await("sound") { audio.isAudible }
        val thief = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }
            .build()
        audioManager.requestAudioFocus(thief)
        try {
            stays("alarm sound while another app holds focus", 1_500) { audio.isAudible }
        } finally {
            audioManager.abandonAudioFocusRequest(thief)
        }
        stays("alarm sound after focus came back", 1_000) { audio.isAudible }
    }

    @Test fun aPlayerThatSomethingPausedIsResumedByTheHeartbeat() {
        startRing()
        await("sound") { audio.isAudible }
        onMain { audio.pauseForTest() }
        await("heartbeat resumes the paused player", 4_000) { audio.isAudible }
    }

    @Test fun aDeadPlayerIsRebuiltInsteadOfLeavingTheAlarmSilent() {
        startRing()
        await("sound") { audio.isAudible }
        onMain { audio.killPlayerForTest() }
        await("playback rebuilt after the player died", 6_000) { audio.isAudible }
        // The new player is the audible one a few statements before the diagnostics note is written, so wait for the note.
        await("the fallback to be recorded", 3_000) { runtime.lastAudioProblem.contains("fell back") }
    }

    // ---- the media player misbehaves ---------------------------------------------------------------------------

    @Test fun anErrorOnTheDefaultToneStillEndsUpAudible() {
        startRing() // the bundled default tone: the case that used to give up and leave the alarm silent
        await("sound") { audio.isAudible }
        onMain { audio.simulatePlayerError() }
        await("sound after a MediaPlayer error", 6_000) { audio.isAudible }
        assertEquals("sunrise", audio.activeSoundId)
    }

    @Test fun aPlayerThatKeepsFailingEndsOnTheEmergencyBeeperInsteadOfLoopingForever() {
        startRing()
        await("sound") { audio.isAudible }
        repeat(7) {
            onMain { audio.simulatePlayerError() }
            Thread.sleep(700)
        }
        await("emergency beeper", 6_000) { runtime.lastAudioProblem.contains("emergency beeper") }
        assertTrue("the beeper counts as sound", audio.isAudible)
    }

    @Test fun ifNothingCanBeOpenedTheBeeperSoundsAnyway() {
        audio.prepareHook = { throw IOException("storage gone") }
        startRing()
        await("beeper") { audio.isAudible }
        assertTrue(runtime.lastAudioProblem.contains("emergency beeper"))
    }

    @Test fun aSoundThatNeverFinishesOpeningFallsBackInsteadOfWaiting() {
        audio.prepareHook = { sound -> if (sound.uri == "content://slow.cloud/song") Thread.sleep(25_000) }
        val slow = quiet(SoundSettings(source = SoundSource.CUSTOM, uri = "content://slow.cloud/song"))
        val started = SystemClock.elapsedRealtime()
        startRing(slow)
        await("sound") { audio.isAudible }
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        assertTrue("fell back after ~4 s instead of waiting 25 s (took ${seconds}s)", seconds < 9)
        assertEquals("sunrise", audio.activeSoundId)
        await("the fallback to be noted", 3_000) { audio.lastNote == "Fell back to a built-in sound" }
    }

    @Test fun aMissingCustomSoundFallsBackToTheBundledTone() {
        startRing(quiet(SoundSettings(source = SoundSource.CUSTOM, uri = "content://no.such.provider/song/1")))
        await("sound") { audio.isAudible }
        assertEquals("sunrise", audio.activeSoundId)
        await("the fallback to be recorded", 3_000) { runtime.lastAudioProblem.contains("fell back") }
    }

    @Test fun aLoweredVolumeForAnAnnouncementIsRestoredEvenIfTheSpeechEngineNeverFinishes() {
        audio.maxDuckMs = 1_200 // a text-to-speech engine that died mid-sentence never reports "done"
        startRing()
        await("sound") { audio.isAudible }
        onMain { audio.setDucked(true) }
        assertTrue(audio.isDucked)
        await("the duck to expire", 5_000) { !audio.isDucked }
        assertTrue(audio.isAudible)
    }

    // ---- playlists ---------------------------------------------------------------------------------------------

    @Test fun aPlaylistTrackThatCannotBeOpenedIsSkippedWhenTheNextOneStarts() {
        val good = resourceTone(R.raw.tone_bell)
        val bad = "content://no.such.provider/song/1"
        // A repeatable "random" that always picks the first entry, so the order is good, then bad, then good again.
        audio.random = object : Random() {
            override fun nextBits(bitCount: Int) = 0
        }
        val playlist = quiet(SoundSettings(mode = SoundMode.RANDOM_CUSTOM, source = SoundSource.CUSTOM, playlist = listOf(good, bad)))
        startRing(playlist)
        await("first track") { audio.isAudible }
        assertEquals(good, audio.activeSoundId)
        onMain { audio.simulateTrackCompleted() }
        // The next pick is the broken track; it used to end the sound for good. Now the loop falls through to a track that opens.
        await("a track that opens after the broken one", 6_000) { audio.isAudible && audio.activeSoundId == good }
    }

    @Test fun aLoopingSoundThatIsReportedFinishedStartsAgain() {
        startRing()
        await("sound") { audio.isAudible }
        onMain { audio.pauseForTest(); audio.simulateTrackCompleted() }
        await("restart after a bogus completion", 4_000) { audio.isAudible }
    }

    // ---- headphones --------------------------------------------------------------------------------------------

    @Test fun headphonesPluggedInMidRingCapTheVolumeAndPullingThemRestoresIt() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        startRing(quiet(level = 60))
        await("sound") { audio.isAudible }
        val onSpeaker = VolumePlanner.percentToIndex(60, max)
        assertEquals("speaker volume as asked", onSpeaker, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))

        onMain { audio.refreshRoute(headsetConnected = true, reason = "test: headset connected") }
        assertEquals("capped for hearing safety once a headset is the route", VolumePlanner.percentToIndex(30, max), audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
        assertTrue("and it keeps ringing", audio.isAudible)
        assertNotNull(audio.lastNote)

        onMain { audio.refreshRoute(headsetConnected = false, reason = "test: headset removed") }
        assertEquals("the cap is lifted again when the headset goes", onSpeaker, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
        stays("sound across both route changes", 800) { audio.isAudible }
    }

    @Test fun theSpeakerOptionKeepsTheAlarmOutOfAHeadsetSoNoCapIsNeeded() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val speakerOnly = settings.copy(audioRoute = AudioRoute.PHONE_SPEAKER)
        startRing(quiet(level = 60), speakerOnly)
        await("sound") { audio.isAudible }
        onMain { audio.refreshRoute(headsetConnected = true, reason = "test: headset connected") }
        assertEquals("not capped: the alarm is pinned to the phone speaker", VolumePlanner.percentToIndex(60, max), audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
        assertTrue(audio.isAudible)
    }

    @Test fun theOriginalVolumeIsRestoredEvenAfterTheRouteChangedMidRing() {
        startRing(quiet(level = 60))
        await("sound") { audio.isAudible }
        onMain { audio.refreshRoute(true, "test") }
        onMain { audio.refreshRoute(false, "test") }
        onMain { audio.stop() }
        assertEquals(originalAlarmVolume, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
    }
}
