package com.riyadm.wakenudge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.ui.onboarding.OnboardingScreen
import com.riyadm.wakenudge.ui.settings.AboutContent
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The project's links, present in every build: the welcome screen's "Open source" line and the About screen's rows. */
@RunWith(AndroidJUnit4::class)
class ProjectLinksUiTest {
    @get:Rule val compose = createComposeRule()

    private val opened = CopyOnWriteArrayList<String>()

    @Test fun theWelcomeScreenLinksToTheSourceCodeAndTheWebsite() {
        compose.setContent { WakeNudgeTheme { OnboardingScreen(onFinish = {}, openLink = { opened += it }) } }
        compose.onNodeWithText("Wake up on purpose").assertIsDisplayed()
        compose.onNodeWithText("Open source · github.com/riyadmondol2006/WakeNudge").assertIsDisplayed().performClick()
        compose.onNodeWithText("wakenudge.site").assertIsDisplayed().performClick()
        assertEquals(listOf("https://github.com/riyadmondol2006/WakeNudge", "https://wakenudge.site"), opened.toList())
    }

    @Test fun theWelcomeLinkStaysReachableOnASmallPhoneAtTheLargestFont() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                WakeNudgeTheme { Box(Modifier.size(320.dp, 480.dp)) { OnboardingScreen(onFinish = {}, openLink = { opened += it }) } }
            }
        }
        compose.onNodeWithText("Open source · github.com/riyadmondol2006/WakeNudge").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("wakenudge.site").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("https://github.com/riyadmondol2006/WakeNudge", "https://wakenudge.site"), opened.toList())
    }

    @Test fun bothWelcomeLinksFitOnANarrowScreenWithoutClipping() {
        compose.setContent {
            WakeNudgeTheme { Box(Modifier.size(320.dp, 640.dp)) { OnboardingScreen(onFinish = {}, openLink = { opened += it }) } }
        }
        compose.onNodeWithText("Open source · github.com/riyadmondol2006/WakeNudge").assertIsDisplayed()
        compose.onNodeWithText("wakenudge.site").assertIsDisplayed()
    }

    @Test fun onlyTheWelcomePageCarriesTheLink() {
        compose.setContent { WakeNudgeTheme { OnboardingScreen(onFinish = {}, openLink = { opened += it }) } }
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rings when it must").assertIsDisplayed()
        compose.onNode(hasText("Open source", substring = true)).assertDoesNotExist()
    }

    @Test fun theAboutScreenListsTheSourceIssuesAndPrivacyPolicyLinks() {
        compose.setContent { WakeNudgeTheme { AboutContent(onBack = {}, openLink = { opened += it }) } }
        val rows = listOf(
            "Website" to "https://wakenudge.site",
            "Source code on GitHub" to "https://github.com/riyadmondol2006/WakeNudge",
            "Report a problem" to "https://github.com/riyadmondol2006/WakeNudge/issues",
            "Privacy policy" to "https://github.com/riyadmondol2006/WakeNudge/blob/main/PRIVACY.md",
        )
        for ((title, url) in rows) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNode(hasText(url.removePrefix("https://"))).assertIsDisplayed()
            compose.onNodeWithText(title).performClick()
        }
        assertEquals(rows.map { it.second }, opened.toList())
    }

    @Test fun theWebsiteRowSitsAboveTheSourceCodeRow() {
        compose.setContent { WakeNudgeTheme { AboutContent(onBack = {}, openLink = { opened += it }) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Source code on GitHub"))
        compose.onNodeWithText("wakenudge.site").assertIsDisplayed()
        val website = compose.onNodeWithText("Website").fetchSemanticsNode().boundsInRoot.top
        val source = compose.onNodeWithText("Source code on GitHub").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Website ($website) is above Source code ($source)", website < source)
    }
}
