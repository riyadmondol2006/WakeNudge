package com.riyadm.wakenudge.engine

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.engine.notify.NotificationIds
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The evening check through the real trigger path, with the real permission state of this install. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TonightCheckInstrumentedTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var alarms: AlarmRepository
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var handler: TriggerHandler
    @Inject lateinit var pending: PendingAlarms
    @Inject lateinit var runtime: RuntimeState
    @Inject lateinit var time: TimeSource

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        hilt.inject()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        runtime.tonightCheckedFor = 0
        notifications.cancel(NotificationIds.TONIGHT_PROBLEM)
    }

    @After fun tearDown() = runBlocking {
        for (alarm in alarms.getAlarms().filter { it.label == LABEL }) {
            scheduler.cancel(alarm.id)
            alarms.delete(alarm.id)
        }
        pending.cancel(TriggerKind.BATTERY_CHECK, 0)
        pending.cancel(TriggerKind.BATTERY_CHECK, 0, TonightCheckPolicy.REARM_INDEX)
        notifications.cancel(NotificationIds.TONIGHT_PROBLEM)
        runtime.tonightCheckedFor = 0
    }

    private fun trigger(index: Int) = Intent(Triggers.action(TriggerKind.BATTERY_CHECK)).putExtra(Triggers.EXTRA_ID, 0L)
        .putExtra(Triggers.EXTRA_AT, time.nowMillis()).putExtra(Triggers.EXTRA_INDEX, index)

    /** A QR alarm about 8 hours from now. */
    private suspend fun qrAlarmTonight(): Long {
        val at = time.now().plusHours(8)
        val id = alarms.save(
            Alarm(
                label = LABEL, hour = at.hour, minute = at.minute, repeatDaysMask = Alarm.EVERY_DAY_MASK,
                missions = listOf(MissionConfig(MissionType.QR_BARCODE, params = mapOf(MissionConfig.PARAM_CODE to "tonight-check"))),
            ),
        )
        scheduler.schedule(id)
        return id
    }

    @Test fun theEveningTriggerWithoutCameraAccessPostsOneNoticeNamingTheMission() = runBlocking {
        // Taking a permission away from a running app ends its process, test included: this needs an install that never had it.
        assumeTrue(
            "camera access must not be granted to the app under test",
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED,
        )
        val id = qrAlarmTonight()
        val next = scheduler.nextAlarm()
        assumeTrue("the test alarm must be the next one", next?.alarm?.id == id)

        handler.handle(trigger(index = 0))

        val posted = notifications.activeNotifications.singleOrNull { it.id == NotificationIds.TONIGHT_PROBLEM }
        assertNotNull("the evening check posted its notice", posted)
        val text = posted!!.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        val mission = context.getString(R.string.mission_title_qr)
        assertTrue("names the mission or says there is more: $text", text.contains(mission) || text.contains("Alarm Health"))
        assertEquals("the alarm is not looked at twice", next!!.at.toInstant().toEpochMilli(), runtime.tonightCheckedFor)
    }

    @Test fun theSilentReArmPutsBackALostTriggerAndPostsNothing() = runBlocking {
        val id = qrAlarmTonight()
        assumeTrue("the test alarm must be the next one", scheduler.nextAlarm()?.alarm?.id == id)
        pending.cancel(TriggerKind.ALARM, id)

        handler.handle(trigger(index = TonightCheckPolicy.REARM_INDEX))

        assertTrue("the trigger is back", pending.isScheduled(TriggerKind.ALARM, id))
        assertTrue(notifications.activeNotifications.none { it.id == NotificationIds.TONIGHT_PROBLEM })
    }

    private companion object {
        const val LABEL = "tonight check test"
    }
}
