package com.riyadm.wakenudge.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import com.riyadm.wakenudge.ui.update.UpdateAvailableDialog
import com.riyadm.wakenudge.update.AppVersion
import com.riyadm.wakenudge.update.ReleaseInfo
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The popup on its own: what it says, what its buttons do, and that it survives big fonts and right-to-left. */
@RunWith(AndroidJUnit4::class)
class UpdateDialogUiTest {
    @get:Rule val compose = createComposeRule()

    private val release = ReleaseInfo(
        version = AppVersion(1, 0, 43),
        tag = "v1.0.43",
        downloadUrl = "https://github.com/riyadmondol2006/WakeNudge/releases/tag/v1.0.43",
        notes = "Fixes the snooze button.\n• Faster alarms",
    )

    private class Taps {
        val download = AtomicInteger()
        val later = AtomicInteger()
        val skip = AtomicInteger()
    }

    private fun show(
        release: ReleaseInfo = this.release,
        taps: Taps = Taps(),
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ): Taps {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale), LocalLayoutDirection provides direction) {
                WakeNudgeTheme {
                    UpdateAvailableDialog(
                        release = release, installedVersion = "1.0.42",
                        onDownload = { taps.download.incrementAndGet() },
                        onLater = { taps.later.incrementAndGet() },
                        onSkip = { taps.skip.incrementAndGet() },
                    )
                }
            }
        }
        return taps
    }

    @Test fun showsTheTitleTheVersionsTheNotesAndThreeButtons() {
        show()
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithText("WakeNudge 1.0.43 is available. You have 1.0.42.").assertIsDisplayed()
        compose.onNodeWithText("What's new").assertIsDisplayed()
        compose.onNodeWithText("Fixes the snooze button.\n• Faster alarms").assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("Later").assertIsDisplayed()
        compose.onNodeWithText("Skip this version").assertIsDisplayed()
    }

    @Test fun withoutNotesThereIsNoEmptyWhatsNewSection() {
        show(release.copy(notes = ""))
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithText("What's new").assertDoesNotExist()
    }

    @Test fun downloadCallsBackOnceAndOnlyDownload() {
        val taps = show()
        compose.onNodeWithText("Download").performClick()
        assertEquals(1, taps.download.get())
        assertEquals(0, taps.later.get())
        assertEquals(0, taps.skip.get())
    }

    @Test fun laterCallsBackOnceAndOnlyLater() {
        val taps = show()
        compose.onNodeWithText("Later").performClick()
        assertEquals(0, taps.download.get())
        assertEquals(1, taps.later.get())
        assertEquals(0, taps.skip.get())
    }

    @Test fun skipCallsBackOnceAndOnlySkip() {
        val taps = show()
        compose.onNodeWithText("Skip this version").performClick()
        assertEquals(0, taps.download.get())
        assertEquals(0, taps.later.get())
        assertEquals(1, taps.skip.get())
    }

    @Test fun theBackButtonMeansLater() {
        val taps = show()
        compose.waitForIdle()
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(1, taps.later.get())
        assertEquals(0, taps.skip.get())
        assertEquals(0, taps.download.get())
    }

    @Test fun everyButtonStaysReachableWithTheLongestNotesAtTheLargestFont() {
        val longNotes = "A fairly long line of release notes that goes on. ".repeat(12).take(599) + "…"
        val taps = show(release.copy(notes = longNotes), fontScale = 2f)
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("Later").assertIsDisplayed()
        compose.onNodeWithText("Skip this version").assertIsDisplayed().performClick()
        assertEquals(1, taps.skip.get())
    }

    @Test fun worksRightToLeft() {
        val taps = show(direction = LayoutDirection.Rtl, fontScale = 1.5f)
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNode(hasText("Download")).assertIsDisplayed().performClick()
        assertEquals(1, taps.download.get())
    }
}
