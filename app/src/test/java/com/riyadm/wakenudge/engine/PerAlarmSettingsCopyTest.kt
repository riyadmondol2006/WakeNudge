package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmDefaults
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.VolumeKeyAction
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAlarmSettingsCopyTest {
    private val alarms = MemoryAlarms()
    private val runtime = realRuntime()
    private val settings = MemorySettings(
        AppSettings(volumeKeyAction = VolumeKeyAction.DISMISS, keepRingScreenFront = false, defaults = AlarmDefaults(antiOversleep = AntiOversleepSettings(backupCount = 2))),
    )
    private val copy = PerAlarmSettingsCopy(alarms, settings, runtime, SilentLog())

    @Test fun `the global choices reach every alarm and the defaults, and nothing else changes`() = runBlocking {
        val before = alarms.add(Alarm(label = "a", antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 0, emergencyStopEnabled = false, wayOutDelayMinutes = 3)))
        assertTrue(copy.runOnce())
        val after = alarms.getAlarm(before.id)!!
        assertEquals(VolumeKeyAction.DISMISS, after.antiOversleep.volumeKeyAction)
        assertFalse(after.antiOversleep.returnToAlarm)
        assertEquals(before.copy(antiOversleep = before.antiOversleep.copy(volumeKeyAction = VolumeKeyAction.DISMISS, returnToAlarm = false)), after)
        val defaults = settings.current().defaults.antiOversleep
        assertEquals(VolumeKeyAction.DISMISS, defaults.volumeKeyAction)
        assertFalse(defaults.returnToAlarm)
        assertEquals(2, defaults.backupCount)
        assertEquals("the old settings stay for older apps reading a backup", VolumeKeyAction.DISMISS, settings.current().volumeKeyAction)
    }

    @Test fun `it runs once, so a later per-alarm choice is not overwritten`() = runBlocking {
        val a = alarms.add(Alarm(label = "a"))
        assertTrue(copy.runOnce())
        alarms.save(alarms.getAlarm(a.id)!!.let { it.copy(antiOversleep = it.antiOversleep.copy(volumeKeyAction = VolumeKeyAction.NOTHING)) })
        assertFalse(copy.runOnce())
        assertEquals(VolumeKeyAction.NOTHING, alarms.getAlarm(a.id)!!.antiOversleep.volumeKeyAction)
    }

    @Test fun `a failure never reaches the caller and it is tried again at the next start`() = runBlocking {
        val a = alarms.add(Alarm(label = "a"))
        alarms.failReads = IOException("database unreadable")
        assertFalse(copy.runOnce())
        assertFalse(runtime.perAlarmSettingsCopied)
        alarms.failReads = null
        assertTrue(copy.runOnce())
        assertEquals(VolumeKeyAction.DISMISS, alarms.getAlarm(a.id)!!.antiOversleep.volumeKeyAction)
    }

    @Test fun `a fresh install has nothing to copy and is marked done`() = runBlocking {
        val fresh = MemorySettings()
        assertTrue(PerAlarmSettingsCopy(alarms, fresh, runtime, SilentLog()).runOnce())
        assertEquals(AppSettings().defaults, fresh.current().defaults)
        assertTrue(runtime.perAlarmSettingsCopied)
    }
}
