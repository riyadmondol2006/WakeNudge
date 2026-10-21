package com.riyadm.wakenudge.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.HiltTestActivity
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.engine.RingKind
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingSession
import com.riyadm.wakenudge.engine.RingSessionStore
import com.riyadm.wakenudge.ui.settings.AboutScreen
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import com.riyadm.wakenudge.ui.update.UpdatePromptHost
import com.riyadm.wakenudge.update.AppVersion
import com.riyadm.wakenudge.update.FailureKind
import com.riyadm.wakenudge.update.FakeReleaseClient
import com.riyadm.wakenudge.update.LookupResult
import com.riyadm.wakenudge.update.ReleaseInfo
import com.riyadm.wakenudge.update.UpdateManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The update feature wired the way the app wires it (real manager, real settings), with a scripted GitHub. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class UpdateFlowUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var manager: UpdateManager
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var client: FakeReleaseClient
    @Inject lateinit var ring: RingSessionStore

    private val newer = ReleaseInfo(
        AppVersion(1, 0, 99), "v1.0.99",
        "https://github.com/riyadmondol2006/WakeNudge/releases/tag/v1.0.99", "Brand new things.",
    )

    @Before fun setUp() {
        hilt.inject()
        runBlocking { settings.update { it.copy(onboardingDone = true) } }
    }

    @After fun tearDown() {
        ring.set(null)
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun showPrompt() = compose.setContent { WakeNudgeTheme { UpdatePromptHost() } }

    private fun scrollAboutTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    @Test fun anUpdateFoundOnStartIsAnnouncedAndSkippingItIsRemembered() {
        client.next = LookupResult.Found(newer)
        runBlocking { manager.checkIfDue() }
        showPrompt()

        waitForText("Update available")
        compose.onNodeWithText("Brand new things.").assertIsDisplayed()
        compose.onNodeWithText("Skip this version").performClick()

        compose.waitUntil(5_000) { runBlocking { settings.current().skippedUpdateVersion } == "1.0.99" }
        compose.onNodeWithText("Update available").assertDoesNotExist()
    }

    @Test fun laterClosesThePopupAndRemembersNothing() {
        client.next = LookupResult.Found(newer)
        runBlocking { manager.checkIfDue() }
        showPrompt()

        waitForText("Update available")
        compose.onNodeWithText("Later").performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Update available").fetchSemanticsNodes().isEmpty() }
        assertEquals("", runBlocking { settings.current().skippedUpdateVersion })
    }

    @Test fun thePopupWaitsWhileAnAlarmIsRinging() {
        client.next = LookupResult.Found(newer)
        runBlocking { manager.checkIfDue() }
        ring.set(RingSession(Alarm(label = "Gym"), RingKind.PRIMARY, RingPhase.RINGING, eventId = 1, scheduledAt = 0, startedAt = 0))
        showPrompt()

        compose.waitForIdle()
        compose.onNodeWithText("Update available").assertDoesNotExist()
        ring.set(null)
        waitForText("Update available")
    }

    @Test fun noPopupWhenThereIsNothingNewer() {
        client.next = LookupResult.Found(newer.copy(version = AppVersion(1, 0, 0)))
        runBlocking { manager.checkIfDue() }
        showPrompt()
        compose.waitForIdle()
        compose.onNodeWithText("Update available").assertDoesNotExist()
    }

    @Test fun theAboutScreenHasAnUpdatesSectionWithTheDisclosure() {
        compose.setContent { WakeNudgeTheme { AboutScreen(onBack = {}) } }
        scrollAboutTo("Updates")
        compose.onNodeWithText("Updates").assertIsDisplayed()
        scrollAboutTo("Check for updates")
        compose.onNodeWithText("Check for updates").assertIsDisplayed()
        scrollAboutTo("Check now")
        compose.onNodeWithText("Check now").assertIsDisplayed()
        compose.onNodeWithText("Not checked yet").assertIsDisplayed()
        scrollAboutTo("What this contacts")
        compose.onNode(hasText("api.github.com", substring = true)).assertIsDisplayed()
    }

    @Test fun checkNowReportsEachOutcomeInPlainWords() {
        compose.setContent { WakeNudgeTheme { AboutScreen(onBack = {}) } }
        scrollAboutTo("Check now")

        client.next = LookupResult.Found(newer)
        compose.onNodeWithText("Check now").performClick()
        waitForText("Version 1.0.99 is available")
        compose.onNodeWithText("Download").assertIsDisplayed()
        assertTrue("a successful check is remembered", runBlocking { settings.current().lastUpdateCheckAt } > 0)
        compose.onNodeWithText("Not checked yet").assertDoesNotExist()

        client.next = LookupResult.NoRelease("HTTP 404")
        compose.onNodeWithText("Check now").performClick()
        waitForText("You're up to date")

        client.next = LookupResult.Failed(FailureKind.OFFLINE, "UnknownHostException")
        compose.onNodeWithText("Check now").performClick()
        waitForText("Couldn't check right now")
        assertTrue(client.calls >= 3)
    }

    @Test fun theSwitchIsStoredAndStopsAutomaticChecks() {
        compose.setContent { WakeNudgeTheme { AboutScreen(onBack = {}) } }
        scrollAboutTo("Check for updates")
        compose.onNodeWithText("Check for updates").performClick()
        compose.waitUntil(5_000) { !runBlocking { settings.current().updateChecksEnabled } }

        client.next = LookupResult.Found(newer)
        runBlocking { manager.checkIfDue() }
        assertEquals("switched off: no automatic lookup", 0, client.calls)

        compose.onNodeWithText("Check for updates").performClick()
        compose.waitUntil(5_000) { runBlocking { settings.current().updateChecksEnabled } }
    }
}
