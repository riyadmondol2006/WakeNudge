package com.riyadm.wakenudge.engine

import android.content.Intent
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.NextAlarm
import com.riyadm.wakenudge.engine.notify.Notifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOUR = 3_600_000L
private const val MINUTE = 60_000L

class LowBatteryPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun `a morning alarm is checked eight hours before it, around bedtime`() {
        assertEquals(now + 1 * HOUR, LowBatteryPolicy.checkAt(now + 9 * HOUR, now))
    }

    @Test fun `an alarm set less than eight hours ahead is checked straight away`() {
        assertEquals(now + MINUTE, LowBatteryPolicy.checkAt(now + 5 * HOUR, now))
    }

    @Test fun `no check without an alarm or when the alarm is about to ring anyway`() {
        assertNull(LowBatteryPolicy.checkAt(null, now))
        assertNull(LowBatteryPolicy.checkAt(now + 29 * MINUTE, now))
        assertNull("an alarm in the past", LowBatteryPolicy.checkAt(now - HOUR, now))
    }

    @Test fun `low and not charging before tonight's alarm is worth a warning`() {
        val alarm = now + 8 * HOUR
        assertTrue(LowBatteryPolicy.shouldWarn(BatteryReading(20, charging = false), alarm, now))
        assertTrue(LowBatteryPolicy.shouldWarn(BatteryReading(3, charging = false), alarm, now))
    }

    @Test fun `charging, enough charge, an unknown battery or no alarm is not`() {
        val alarm = now + 8 * HOUR
        assertFalse("plugged in", LowBatteryPolicy.shouldWarn(BatteryReading(5, charging = true), alarm, now))
        assertFalse("21% is enough for a night", LowBatteryPolicy.shouldWarn(BatteryReading(21, charging = false), alarm, now))
        assertFalse("the phone would not say", LowBatteryPolicy.shouldWarn(null, alarm, now))
        assertFalse("no alarm", LowBatteryPolicy.shouldWarn(BatteryReading(5, charging = false), null, now))
    }

    @Test fun `an alarm too far off or about to ring gets no warning`() {
        val low = BatteryReading(10, charging = false)
        assertFalse("a whole day to charge", LowBatteryPolicy.shouldWarn(low, now + 15 * HOUR, now))
        assertFalse("rings in 10 minutes anyway", LowBatteryPolicy.shouldWarn(low, now + 10 * MINUTE, now))
        assertTrue(LowBatteryPolicy.shouldWarn(low, now + 14 * HOUR, now))
        assertTrue(LowBatteryPolicy.shouldWarn(low, now + 30 * MINUTE, now))
    }
}

/** The trigger itself: reads the setting, the next alarm and the battery, and notifies only when all three say so. */
class LowBatteryTriggerTest {
    private val clock = TestClock()
    private val notifier = mockk<Notifier>(relaxed = true)
    private val scheduler = mockk<AlarmScheduler>(relaxed = true)

    private fun handler(settings: AppSettings = AppSettings(), reading: BatteryReading?) = TriggerHandler(
        mockk(relaxed = true), mockk(relaxed = true), RingStarter { }, scheduler, notifier,
        MemorySettings(settings), realRuntime(), clock, SilentLog(), BatteryGauge { reading },
    )

    private fun trigger(): Intent = mockk {
        every { action } returns Triggers.action(TriggerKind.BATTERY_CHECK)
        every { getLongExtra(Triggers.EXTRA_ID, any()) } returns 0
        every { getLongExtra(Triggers.EXTRA_AT, any()) } returns clock.now
        every { getLongExtra(Triggers.EXTRA_EVENT_ID, any()) } returns -1
        every { getIntExtra(Triggers.EXTRA_INDEX, any()) } returns 0
    }

    private fun nextAlarmIn(ms: Long) {
        val at = Instant.ofEpochMilli(clock.now + ms).atZone(ZoneId.of("UTC"))
        coEvery { scheduler.nextAlarm() } returns NextAlarm(Alarm(id = 3, hour = at.hour, minute = at.minute), at)
    }

    @Test fun `a low battery before tonight's alarm posts the warning with the alarm time`() = runBlocking {
        nextAlarmIn(8 * HOUR)
        handler(reading = BatteryReading(12, charging = false)).handle(trigger())
        verify(exactly = 1) { notifier.showLowBattery(12, any(), clock.now + 8 * HOUR) }
    }

    @Test fun `nothing is posted while charging, with the warning switched off, or with no alarm`() = runBlocking {
        nextAlarmIn(8 * HOUR)
        handler(reading = BatteryReading(12, charging = true)).handle(trigger())
        handler(settings = AppSettings(lowBatteryWarning = false), reading = BatteryReading(12, charging = false)).handle(trigger())
        coEvery { scheduler.nextAlarm() } returns null
        handler(reading = BatteryReading(12, charging = false)).handle(trigger())
        verify(exactly = 0) { notifier.showLowBattery(any(), any(), any()) }
    }

    @Test fun `the warning is on for everyone by default`() {
        assertTrue(AppSettings().lowBatteryWarning)
    }
}
