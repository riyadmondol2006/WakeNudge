package com.riyadm.wakenudge.ui.sounds

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.riyadm.wakenudge.engine.sounds.*
import org.junit.Rule
import org.junit.Test

class SleepSoundsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun mixLimitAtLargeFontAndRtl() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, SleepSoundsDatabase::class.java).build()
        val store = SleepMixStore(database).also { it.save(SleepMix(emptyMap())) }
        val vm = SleepSoundsViewModel(store, context)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { SleepSoundsScreen({}, vm = vm) }
            }
        }
        compose.onNodeWithContentDescription("White noise").performClick()
        compose.onNodeWithContentDescription("Pink noise").performClick()
        compose.onNodeWithContentDescription("Brown noise").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Rain").assertIsNotEnabled()
        compose.onNodeWithContentDescription("White noise").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Rain").assertIsEnabled()
    }
}
