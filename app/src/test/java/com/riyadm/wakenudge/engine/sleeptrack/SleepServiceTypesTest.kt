package com.riyadm.wakenudge.engine.sleeptrack

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** The foreground-service types the tracking service asks for on each Android version. */
class SleepServiceTypesTest {
    private val health = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
    private val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    @Test fun `Android 14 and later get health always and microphone only while recording`() {
        for (sdk in listOf(34, 35, 36, 37)) {
            assertEquals(health, SleepTrackService.types(mic = false, sdk = sdk))
            assertEquals(health or mic, SleepTrackService.types(mic = true, sdk = sdk))
        }
    }

    @Test fun `Android 11 to 13 have no health type, the microphone type still applies`() {
        for (sdk in 30..33) {
            assertEquals(0, SleepTrackService.types(mic = false, sdk = sdk))
            assertEquals(mic, SleepTrackService.types(mic = true, sdk = sdk))
        }
    }

    @Test fun `before Android 11 no type is passed`() {
        for (sdk in 26..29) assertEquals(0, SleepTrackService.types(mic = true, sdk = sdk))
    }

    @Test fun `ids stay clear of the alarm engine's notifications and request codes`() {
        val engineFixed = 1001..1005
        val enginePerAlarm = 20_000..(60_000 + 0xFFFF)
        for (id in listOf(SleepTrackIds.NOTIFICATION, SleepTrackIds.REQUEST_STOP, SleepTrackIds.REQUEST_OPEN, SleepTrackIds.REQUEST_CLEANUP)) {
            assert(id !in engineFixed && id !in enginePerAlarm && id !in 0..5) { "$id collides" }
        }
    }
}
