package com.riyadm.wakenudge.engine

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.wakenudge.RecordingStarter
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.VibrationSettings
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.engine.audio.AlarmAudioEngine
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Before fun setUp() = hilt.inject()

    @After fun tearDown() = runBlocking {
        context.stopService(Intent(context, RingService::class.java))
        store.set(null)
        locks.releaseAll()
        val ids = alarms.getAlarms().map { it.id } + history.recent(100).map { it.alarmId } + RingIds.TEST_ALARM_ID
        for (id in ids.toSet()) for (kind in TriggerKind.entries) for (i in 0..3) if (pending.isScheduled(kind, id, i)) pending.cancel(kind, id, i)
        runtime.testAlarmJson = null
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
        assertFalse("nobody is looking at a ring screen", store.isUiVisible.value)
        Thread.sleep(2_500) // the ring screen is absent for the whole time: the sound must not depend on it
        assertTrue(audio.isAudible)
        assertNotNull(store.current)

        ring.dismiss()
        await("silence after dismissal") { !audio.isAudible }
        await("the ring lock to be released") { !locks.ringHeld }
        assertFalse(locks.handoffHeld)
        assertNull(store.current)
    }

    @Test fun theServiceAsksTheSystemToBringItBackIfItIsKilledMidRing() = runBlocking {
        runtime.testAlarmJson = WakeJson.instance.encodeToString(Alarm.serializer(), quietAlarm())
        startService(RingRequest(RingIds.TEST_ALARM_ID, -1, time.nowMillis(), RingKind.TEST))
        await("the service to start sounding") { store.current != null && audio.isAudible }

        val dump = shell("dumpsys activity services ${context.packageName}/com.riyadm.wakenudge.engine.RingService")
        assertTrue("service record present:\n$dump", dump.contains("RingService"))
        // START_REDELIVER_INTENT (and START_STICKY) leave stopIfKilled=false: after a kill the system restarts the
        // service and delivers the original request again. START_NOT_STICKY, the previous behaviour, says true.
        assertTrue("the service must not be marked 'stop if killed':\n$dump", dump.contains("stopIfKilled=false"))
        ring.dismiss()
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
}
