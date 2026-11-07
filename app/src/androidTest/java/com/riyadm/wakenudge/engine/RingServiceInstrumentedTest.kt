package com.riyadm.wakenudge.engine

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.riyadm.wakenudge.RecordingStarter
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.VibrationSettings
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.DiagnosticRecord
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.engine.audio.AlarmAudioEngine
import com.riyadm.wakenudge.engine.policy.RingProof
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real [RingService] (real foreground service, real audio, quiet) with no ring screen anywhere, which is also
 * what a crashed or swiped-away ring screen looks like to the service, and checks what the service leaves behind.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RingServiceInstrumentedTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var audio: AlarmAudioEngine
    @Inject lateinit var store: RingSessionStore
    @Inject lateinit var ring: RingController
    @Inject lateinit var locks: RingWakeLocks
    @Inject lateinit var runtime: RuntimeState
    @Inject lateinit var pending: PendingAlarms
    @Inject lateinit var alarms: AlarmRepository
    @Inject lateinit var history: HistoryRepository
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var handler: TriggerHandler
    @Inject lateinit var starter: RecordingStarter
    @Inject lateinit var time: TimeSource
    @Inject lateinit var systemEvents: SystemEventHandler

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private var originalAlarmVolume = 0

    @Before fun setUp() {
        hilt.inject()
        originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        runtime.setRegisteredTriggers(emptyMap())
        runtime.clearRingCrashes()
        runtime.clearDurableRing()
    }

    @After fun tearDown() = runBlocking {
        context.stopService(Intent(context, RingService::class.java))
        store.set(null)
        locks.releaseAll()
        runtime.clearDurableRing()
        pending.cancel(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID)
        val ids = alarms.getAlarms().map { it.id } + history.recent(100).map { it.alarmId } + RingIds.TEST_ALARM_ID
        for (id in ids.toSet()) for (kind in TriggerKind.entries) for (i in 0..3) if (pending.isScheduled(kind, id, i)) pending.cancel(kind, id, i)
        runtime.testAlarmJson = null
        runtime.setRegisteredTriggers(emptyMap())
        runtime.clearRingCrashes()
        // The last test's service can be cut off before it restores the volume, and this phone is someone's alarm clock.
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0)
        runtime.savedAlarmVolume = -1
    }

    /** A ring set to Never can outlast the ring lock's timeout, so the service renews it; renewing must push the timeout back. */
    @Test fun renewingTheRingLockKeepsItPastItsTimeout() {
        locks.acquireRing(1_500)
        Thread.sleep(1_000)
        locks.acquireRing(1_500)
        Thread.sleep(1_000)
        assertTrue("still held 2 s in, past the first 1.5 s timeout", locks.ringHeld)
        Thread.sleep(1_200)
        assertFalse("released once renewals stop", locks.ringHeld)
    }

    private fun quietAlarm(id: Long = RingIds.TEST_ALARM_ID) = Alarm(
        id = id, label = "service test",
        volume = VolumeSettings(level = 1, rampSeconds = 0, rampStartPercent = 100, escalateWhenIgnored = false),
        vibration = VibrationSettings(enabled = false),
    )

    private fun startService(request: RingRequest) {
        val intent = Intent(context, RingService::class.java).setAction(RingService.ACTION_BEGIN)
            .putExtra(RingService.EXTRA_ALARM_ID, request.alarmId)
            .putExtra(RingService.EXTRA_EVENT_ID, request.eventId)
            .putExtra(RingService.EXTRA_SCHEDULED_AT, request.scheduledAt)
            .putExtra(RingService.EXTRA_KIND, request.kind.name)
            .putExtra(RingService.EXTRA_SNOOZE_COUNT, request.snoozeCount)
            .putExtra(RingService.EXTRA_PHASE, request.phase.name)
            .putExtra(RingService.EXTRA_TIMER_ID, request.timerId)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("timed out waiting for: $what")
    }

    private fun shell(command: String): String {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        return BufferedReader(InputStreamReader(android.os.ParcelFileDescriptor.AutoCloseInputStream(fd))).use { it.readText() }
    }

    private fun trigger(kind: TriggerKind, id: Long, at: Long, eventId: Long = -1, index: Int = 0) =
        Intent(Triggers.action(kind)).putExtra(Triggers.EXTRA_ID, id).putExtra(Triggers.EXTRA_AT, at)
            .putExtra(Triggers.EXTRA_EVENT_ID, eventId).putExtra(Triggers.EXTRA_INDEX, index)

    // ---- sound and locks do not depend on any screen ---------------------------------------------------------------

    @Test fun theSoundKeepsPlayingWithNoRingScreenAndEverythingIsReleasedWhenTheRingEnds() = runBlocking {
        runtime.testAlarmJson = WakeJson.instance.encodeToString(Alarm.serializer(), quietAlarm())
        startService(RingRequest(RingIds.TEST_ALARM_ID, -1, time.nowMillis(), RingKind.TEST))

        await("the service to start sounding") { store.current != null && audio.isAudible }
        assertTrue("the ring wake lock is held while ringing", locks.ringHeld)
        // The service opens the ring screen itself, so make "nobody is looking" real the way a user would: go home.
        shell("input keyevent KEYCODE_HOME")
        await("the ring screen to leave the foreground") { !store.isUiVisible.value }
        Thread.sleep(2_500) // the ring screen is absent for the whole time: the sound must not depend on it
        assertTrue(audio.isAudible)
        assertNotNull(store.current)

        ring.dismiss()
        await("silence after dismissal") { !audio.isAudible }
        await("the ring lock to be released") { !locks.ringHeld }
        assertFalse(locks.handoffHeld)
        assertNull(store.current)
    }

    @Test fun theServiceAsksTheSystemToBringItBackIfItIsKilledMidRing() = runBlocking<Unit> {
        runtime.testAlarmJson = WakeJson.instance.encodeToString(Alarm.serializer(), quietAlarm())
        startService(RingRequest(RingIds.TEST_ALARM_ID, -1, time.nowMillis(), RingKind.TEST))
        await("the service to start sounding") { store.current != null && audio.isAudible }

        val dump = shell("dumpsys activity services ${context.packageName}/com.riyadm.wakenudge.engine.RingService")
        assertTrue("service record present:\n$dump", dump.contains("RingService"))
        assumeTrue("this Android version words the service dump differently", dump.contains("startCommandResult="))
        // START_REDELIVER_INTENT is 3. The system then keeps the delivered start ("Delivered Starts") and, after a kill,
        // restarts the service with that very intent. (stopIfKilled=true is how AOSP records it, so it is no evidence
        // either way.) START_NOT_STICKY, the previous behaviour, is 2 and keeps nothing.
        assertTrue("the service must ask to be restarted with its intent:\n$dump", dump.contains("startCommandResult=3"))
        assertTrue("the delivered ring request must be kept for redelivery:\n$dump", dump.contains("Delivered Starts") && dump.contains("RING_BEGIN"))
        ring.dismiss()
    }

    // ---- coming back to the ring screen ---------------------------------------------------------------------------

    private val device: UiDevice by lazy { UiDevice.getInstance(instrumentation) }

    private fun allowOverlays(allow: Boolean) = shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW ${if (allow) "allow" else "deny"}")

    private fun startReturningRing() {
        // Unlocked, which these tests need: on a locked phone the ring screen is only ever alerted, never pulled back.
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        runtime.testAlarmJson = WakeJson.instance.encodeToString(
            Alarm.serializer(), quietAlarm().copy(antiOversleep = AntiOversleepSettings(returnToAlarm = true, forceReturn = true)),
        )
        startService(RingRequest(RingIds.TEST_ALARM_ID, -1, time.nowMillis(), RingKind.TEST))
        await("the service to start sounding") { store.current != null && audio.isAudible }
        await("the ring screen in front", 5_000) { store.isUiInFront }
    }

    /** Leaves the ring screen with [leave], then returns how long it took to be in front again. */
    private fun timeToComeBack(leave: () -> Unit): Long {
        leave()
        await("the ring screen to leave the front", 3_000) { !store.isUiInFront }
        val left = SystemClock.elapsedRealtime()
        await("the ring screen back in front", 6_000) { store.isUiInFront }
        return SystemClock.elapsedRealtime() - left
    }

    @Test fun withDisplayOverOtherAppsHomeAndRecentsBringTheRingScreenBackWithinTwoSeconds() = runBlocking {
        val nav = shell("settings get secure navigation_mode").trim()
        allowOverlays(true)
        try {
            startReturningRing()
            for ((how, leave) in listOf<Pair<String, () -> Unit>>(
                "Home" to { device.pressHome() },
                "Recents" to { device.pressRecentApps() },
                "Recents, then a swipe up on the card in the middle" to {
                    device.pressRecentApps()
                    Thread.sleep(600)
                    device.swipe(device.displayWidth / 2, device.displayHeight / 2, device.displayWidth / 2, device.displayHeight / 10, 12)
                },
            )) {
                val took = timeToComeBack(leave)
                assertTrue("$how (navigation mode $nav): back after $took ms", took <= 2_000)
                assertTrue("$how: the sound never stopped", audio.isAudible)
                // Stay a little, as someone would, so the next try is not counted against the backoff.
                Thread.sleep(1_500)
            }
        } finally {
            allowOverlays(false)
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            ring.dismiss()
        }
    }

    @Test fun withoutDisplayOverOtherAppsTheAlarmKeepsRingingButItsScreenStaysAway() = runBlocking {
        allowOverlays(false)
        try {
            runtime.testAlarmJson = WakeJson.instance.encodeToString(
                Alarm.serializer(), quietAlarm().copy(antiOversleep = AntiOversleepSettings(returnToAlarm = true, forceReturn = true)),
            )
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            startService(RingRequest(RingIds.TEST_ALARM_ID, -1, time.nowMillis(), RingKind.TEST))
            await("the service to start sounding") { store.current != null && audio.isAudible }
            device.pressHome()
            Thread.sleep(4_000)
            assertFalse("Android does not let it start the screen from the background", store.isUiInFront)
            assertTrue(audio.isAudible)
        } finally {
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            ring.dismiss()
        }
    }

    // ---- the service dying while ringing -----------------------------------------------------------------------

    @Test fun aServiceDestroyedMidRingArmsARescueAndDoesNotLeaveAnOrphanedSessionBlockingLaterAlarms() = runBlocking {
        val id = alarms.save(quietAlarm(0).copy(label = "rescue me"))
        val request = ring.onAlarmFired(id, time.nowMillis())!!
        startService(request)
        await("sound") { store.current?.alarm?.id == id && audio.isAudible }

        context.stopService(Intent(context, RingService::class.java)) // destroyed from outside, not by a dismissal
        await("the session to be dropped") { store.current == null }
        assertFalse(audio.isAudible)
        assertTrue("a rescue ring is armed", pending.isScheduled(TriggerKind.RING_RETRY, id))
        assertTrue(runtime.ringCrashTimes().isNotEmpty())

        // When the rescue trigger arrives (here: delivered by hand) the same alarm rings again, for the same event.
        starter.requests.clear()
        pending.cancel(TriggerKind.RING_RETRY, id)
        handler.handle(trigger(TriggerKind.RING_RETRY, id, request.scheduledAt, request.eventId, RingRetry.encode(RingKind.PRIMARY, 0)))
        val again = starter.requests.single()
        assertEquals(id, again.alarmId)
        assertEquals(request.eventId, again.eventId)
        assertEquals(RingKind.PRIMARY, again.kind)
        assertEquals(1, again.attempt)
    }

    // ---- a ring outlives its process ---------------------------------------------------------------------------

    private fun strictAlarm() = quietAlarm(0).copy(
        label = "keeps coming back",
        antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = AntiOversleepSettings.RING_TIMEOUT_NEVER, resumeWindowMinutes = 720),
    )

    @Test fun aKilledRingIsBroughtBackByTheSentinelWithItsSavedState() = runBlocking {
        val id = alarms.save(strictAlarm().copy(missions = listOf(MissionConfig(MissionType.MATH), MissionConfig(MissionType.MATH))))
        val request = ring.onAlarmFired(id, time.nowMillis())!!
        startService(request)
        await("sound") { store.current?.alarm?.id == id && audio.isAudible }
        assertTrue("the sentinel is armed while it rings", pending.isScheduled(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID))
        ring.noteMissionDone(1)

        // Taken down from outside, with no dismissal: what Stop in Active apps leaves behind.
        context.stopService(Intent(context, RingService::class.java))
        await("the session to be dropped") { store.current == null }
        assertEquals("the saved ring outlives the session", 1, DurableRingStore(runtime, NoLog).ring()!!.missionsDone)

        starter.requests.clear()
        handler.handle(trigger(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID, time.nowMillis()))
        val back = starter.requests.single()
        assertEquals(id, back.alarmId)
        assertEquals(request.eventId, back.eventId)

        startService(back)
        await("sound again") { store.current?.alarm?.id == id && audio.isAudible }
        assertEquals("progress came back with it", 1, store.current!!.missionsDone)
        assertEquals(2, store.current!!.alarm.missions.size)

        store.update { it.copy(missionsDone = 2) }
        assertTrue(ring.dismiss(proof = RingProof.MissionsComplete))
        await("silence") { !audio.isAudible }
        assertNull("a proper dismissal leaves nothing to bring back", DurableRingStore(runtime, NoLog).ring())
        assertFalse(pending.isScheduled(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID))
    }

    @Test fun theServiceRunsUnderTheBestTypeTheSystemAllows() = runBlocking {
        runtime.testAlarmJson = WakeJson.instance.encodeToString(Alarm.serializer(), quietAlarm())
        startService(RingRequest(RingIds.TEST_ALARM_ID, -1, time.nowMillis(), RingKind.TEST))
        await("the service to start sounding") { store.current != null && audio.isAudible }
        val expected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        assertEquals(expected, RingService.foregroundType)
        assertTrue(ring.dismiss())
    }

    @Test fun aBootBroadcastWithASavedRingArmsItsReturnAndDoesNotThrow() = runBlocking {
        val id = alarms.save(strictAlarm())
        val session = ring.begin(ring.onAlarmFired(id, time.nowMillis())!!)!!
        pending.cancel(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID)
        store.set(null) // the restart took the process and everything Android held

        systemEvents.handle(Intent.ACTION_BOOT_COMPLETED)
        assertTrue("a trigger brings it back, not this receiver", pending.isScheduled(TriggerKind.RING_SENTINEL, RingIds.SENTINEL_TRIGGER_ID))
        assertEquals(AlarmOutcome.RINGING, history.get(session.eventId)!!.outcome)
        assertEquals(id, DurableRingStore(runtime, NoLog).ring()!!.alarmId)
    }

    private object NoLog : DiagnosticsLog {
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String) = Unit
        override fun error(tag: String, message: String, error: Throwable?) = Unit
        override fun observeRecent(limit: Int): Flow<List<DiagnosticRecord>> = flowOf(emptyList())
        override suspend fun recent(limit: Int): List<DiagnosticRecord> = emptyList()
        override suspend fun clear() = Unit
    }
}
