package com.riyadm.wakenudge.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.data.backup.BackupManager
import com.riyadm.wakenudge.data.backup.ImportResult
import com.riyadm.wakenudge.domain.model.ThemeMode
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Update-check state describes the installation, so restoring a backup (new, old or hand-edited) must neither switch
 * checks on or off nor forget the skipped version, while the rest of the settings do come from the file.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class UpdateStateRestoreInstrumentedTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var backup: BackupManager

    private lateinit var before: com.riyadm.wakenudge.domain.model.AppSettings

    @Before fun setUp() {
        hilt.inject()
        before = runBlocking { settings.current() }
    }

    /** The settings are the phone's real ones: put back what these tests switched off or restored from a file. */
    @After fun tearDown() = runBlocking { settings.update { before } }

    private fun restore(json: String) = runBlocking { assertTrue(backup.import(json) is ImportResult.Success) }

    @Test fun restoringAnOldBackupDoesNotSwitchUpdateChecksBackOn() = runBlocking {
        settings.update { it.copy(updateChecksEnabled = false, lastUpdateCheckAt = 1_800_000_000_000, skippedUpdateVersion = "1.0.9") }

        // Written by a version that did not know about update checks at all.
        restore("""{"app":"WakeNudge","formatVersion":1,"settings":{"onboardingDone":true,"themeMode":"AMOLED"},"alarms":[]}""")

        val after = settings.current()
        assertEquals("the rest of the settings come from the file", ThemeMode.AMOLED, after.themeMode)
        assertFalse(after.updateChecksEnabled)
        assertEquals(1_800_000_000_000, after.lastUpdateCheckAt)
        assertEquals("1.0.9", after.skippedUpdateVersion)
    }

    @Test fun aFileCannotTurnUpdateChecksOffOrPlantASkippedVersion() = runBlocking {
        settings.update { it.copy(updateChecksEnabled = true, skippedUpdateVersion = "") }

        restore("""{"app":"WakeNudge","formatVersion":1,"settings":{"updateChecksEnabled":false,"skippedUpdateVersion":"999.0.0","lastUpdateCheckAt":4102444800000},"alarms":[]}""")

        val after = settings.current()
        assertTrue(after.updateChecksEnabled)
        assertEquals("", after.skippedUpdateVersion)
        assertEquals(0L, after.lastUpdateCheckAt)
    }

    @Test fun anExportCarriesNoUpdateState() = runBlocking {
        // A skipped version that cannot be part of this build's own version (1.0.N), which the export does carry.
        settings.update { it.copy(updateChecksEnabled = false, lastUpdateCheckAt = 1_777_000_000_123, skippedUpdateVersion = "7.7.7777") }
        val json = backup.export(includeHistory = false)
        assertFalse("the skipped version is not exported:\n$json", json.contains("7.7.7777"))
        assertFalse("the last check time is not exported:\n$json", json.contains("1777000000123"))
        assertTrue("and exporting changes nothing here", !settings.current().updateChecksEnabled)
    }
}
