package com.riyadm.wakenudge.ui.sounds

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
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
        compose.setContent {
            val context = LocalContext.current
            val store = androidx.compose.runtime.remember {
                SleepMixStore(androidx.room.Room.inMemoryDatabaseBuilder(context, SleepSoundsDatabase::class.java).build()).also { it.save(SleepMix(emptyMap())) }
            }
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { SleepSoundsScreen({}, vm = SleepSoundsViewModel(store, context)) }
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
