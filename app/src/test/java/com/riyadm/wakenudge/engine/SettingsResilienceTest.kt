package com.riyadm.wakenudge.engine

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import com.riyadm.wakenudge.data.prefs.SettingsRepositoryImpl
import com.riyadm.wakenudge.domain.model.AppSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** A damaged settings file must never stop an alarm from being armed or from ringing: it reads as the defaults. */
class SettingsResilienceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File

    @Before fun setUp() {
        file = File.createTempFile("settings", ".preferences_pb").also { it.deleteOnExit() }
        file.writeBytes(byteArrayOf(0x7f, 0x13, 0x00, 0x42, 0x55, 0x66, 0x77, 0x01, 0x02, 0x03)) // not a valid preferences file
    }

    @After fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test fun `a file that cannot be parsed reads as the default settings instead of throwing`() = runBlocking {
        val repo = SettingsRepositoryImpl(PreferenceDataStoreFactory.create(scope = scope) { file })
        assertEquals(AppSettings(), repo.current())
    }

    @Test fun `with the corruption handler the store heals itself and accepts new settings`() = runBlocking {
        val store = PreferenceDataStoreFactory.create(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }, scope = scope) { file }
        val repo = SettingsRepositoryImpl(store)
        assertEquals(AppSettings(), repo.current())
        repo.update { it.copy(haptics = false) }
        assertFalse(repo.current().haptics)
    }
}
