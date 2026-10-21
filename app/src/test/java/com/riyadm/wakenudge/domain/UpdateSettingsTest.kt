package com.riyadm.wakenudge.domain

import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSettingsTest {

    private fun decode(json: String) = WakeJson.instance.decodeFromString(AppSettings.serializer(), json)

    @Test fun `update checks are on by default with nothing recorded`() {
        val fresh = AppSettings()
        assertTrue(fresh.updateChecksEnabled)
        assertEquals(0L, fresh.lastUpdateCheckAt)
        assertEquals("", fresh.skippedUpdateVersion)
    }

    @Test fun `settings stored before update checks existed still load with the defaults`() {
        val stored = decode("""{"onboardingDone":true,"themeMode":"AMOLED","showSeconds":true}""")
        assertTrue(stored.onboardingDone)
        assertEquals(ThemeMode.AMOLED, stored.themeMode)
        assertTrue(stored.updateChecksEnabled)
        assertEquals(0L, stored.lastUpdateCheckAt)
        assertEquals("", stored.skippedUpdateVersion)
    }

    @Test fun `the three fields survive a round trip`() {
        val settings = AppSettings(updateChecksEnabled = false, lastUpdateCheckAt = 1_800_000_000_000, skippedUpdateVersion = "1.0.43")
        val again = decode(WakeJson.instance.encodeToString(AppSettings.serializer(), settings))
        assertEquals(settings, again)
    }

    @Test fun `unusable stored values fall back instead of failing the whole document`() {
        val stored = decode("""{"updateChecksEnabled":null,"skippedUpdateVersion":null,"onboardingDone":true}""")
        assertTrue(stored.updateChecksEnabled)
        assertEquals("", stored.skippedUpdateVersion)
        assertTrue(stored.onboardingDone)
    }

    @Test fun `a restore keeps this installation's update state and takes everything else from the file`() {
        val installed = AppSettings(updateChecksEnabled = false, lastUpdateCheckAt = 42, skippedUpdateVersion = "1.0.9", themeMode = ThemeMode.LIGHT)
        val fromFile = AppSettings(updateChecksEnabled = true, lastUpdateCheckAt = 7, skippedUpdateVersion = "", themeMode = ThemeMode.DARK, showSeconds = true)
        val merged = fromFile.withUpdateStateOf(installed)
        assertFalse(merged.updateChecksEnabled)
        assertEquals(42L, merged.lastUpdateCheckAt)
        assertEquals("1.0.9", merged.skippedUpdateVersion)
        assertEquals(ThemeMode.DARK, merged.themeMode)
        assertTrue(merged.showSeconds)
    }

    @Test fun `a backup records update state at its defaults`() {
        val recorded = AppSettings(updateChecksEnabled = false, lastUpdateCheckAt = 42, skippedUpdateVersion = "1.0.9", showSeconds = true).withoutUpdateState()
        assertEquals(AppSettings(showSeconds = true), recorded)
    }
}
