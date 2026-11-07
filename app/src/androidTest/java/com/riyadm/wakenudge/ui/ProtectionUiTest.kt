package com.riyadm.wakenudge.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.health.HealthFixKind
import com.riyadm.wakenudge.ui.protection.OverlayStage
import com.riyadm.wakenudge.ui.protection.ProtectionActions
import com.riyadm.wakenudge.ui.protection.ProtectionInputs
import com.riyadm.wakenudge.ui.protection.ProtectionUiState
import com.riyadm.wakenudge.ui.protection.RehearsalChoice
import com.riyadm.wakenudge.ui.protection.protectionContent
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Protection setup drawn from a faked state, as the screen draws it after each return from Settings: every step
 * shows what the state says, the restricted-settings steps follow the overlay attempt, and the device admin row with its
 * Turn off is there in every state.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ProtectionUiTest {
    // The app's receivers need a component: a system broadcast during the run (a clock or time-zone change) would
    // otherwise crash the whole process, and the run would end with no result at all.
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createComposeRule()

    private val res = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val calls = mutableListOf<String>()
    private val actions = ProtectionActions(
        openFix = { calls += "fix:$it" },
        overlayOpened = { calls += "overlay" },
        appInfoOpened = { calls += "appInfo" },
        rehearse = { calls += "rehearse:${it.id}" },
        turnOnAdmin = { calls += "adminOn" },
        turnOffAdmin = { calls += "adminOff" },
    )
    private val nothingDone = ProtectionInputs(
        sdkInt = 37, overlayGranted = false, notificationsOk = false, fullScreenOk = false, batteryExempt = false,
        anyRehearsalFinished = false, adminActive = false, ringingOrSnoozed = false,
    )
    private val alarm = Alarm(id = 9, hour = 7, missions = listOf(MissionConfig(MissionType.MATH)))
    private var state by mutableStateOf(ProtectionUiState(nothingDone, listOf(RehearsalChoice(alarm, rehearsed = false))))

    private fun show() = compose.setContent {
        WakeNudgeTheme { LazyColumn(Modifier.testTag("list")) { protectionContent(state, actions) } }
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("list").performScrollToNode(hasTestTag(tag))

    @Test fun eachReturnFromSettingsShowsTheNewState() {
        show()
        compose.onNodeWithTag("protection_status").assertTextContains("6", substring = true)
        compose.onNodeWithText(res.getString(R.string.protection_overlay_open)).performClick()
        assertEquals("overlay", calls.last())

        // Back from the overlay setting without the grant: the restricted-settings help appears.
        state = state.copy(inputs = nothingDone.copy(overlayStage = OverlayStage.OPENED))
        compose.onNodeWithText(res.getString(R.string.protection_overlay_next)).performClick()
        assertEquals("appInfo", calls.last())

        // Back from App info: the setting again.
        state = state.copy(inputs = nothingDone.copy(overlayStage = OverlayStage.APP_INFO_OPENED))
        compose.onNodeWithText(res.getString(R.string.protection_overlay_again)).performClick()
        assertEquals("overlay", calls.last())

        // Granted this time, and the battery fixed too.
        state = state.copy(inputs = nothingDone.copy(overlayGranted = true, batteryExempt = true))
        compose.onNodeWithTag("protection_status").assertTextContains("4", substring = true)
    }

    @Test fun deviceAdminWaitsForARehearsalThenOffersTurnOnAndTurnOff() {
        show()
        scrollTo("step_admin")
        compose.onNodeWithTag("admin_turn_on").assertIsNotEnabled()
        compose.onNodeWithText(res.getString(R.string.protection_admin_locked)).assertIsDisplayed()

        scrollTo("step_rehearsal")
        compose.onNodeWithText(res.getString(R.string.protection_rehearsal_try)).performClick()
        assertEquals("rehearse:9", calls.last())

        state = state.copy(inputs = nothingDone.copy(anyRehearsalFinished = true))
        scrollTo("step_admin")
        compose.onNodeWithTag("admin_turn_on").assertIsEnabled().performClick()
        assertEquals("adminOn", calls.last())

        state = state.copy(inputs = nothingDone.copy(anyRehearsalFinished = true, adminActive = true))
        scrollTo("step_admin")
        compose.onNodeWithTag("admin_turn_off").assertIsEnabled().performClick()
        assertEquals("adminOff", calls.last())
    }

    @Test fun turnOffIsStillThereDuringARingAndSaysWhyItWaits() {
        state = state.copy(inputs = nothingDone.copy(anyRehearsalFinished = true, adminActive = true, ringingOrSnoozed = true))
        show()
        scrollTo("step_admin")
        compose.onNodeWithTag("admin_turn_off").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(res.getString(R.string.protection_admin_wait)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.protection_admin_system_route)).assertIsDisplayed()
    }

    @Test fun theLimitsSayPowerOffIsNotBlocked() {
        show()
        scrollTo("limits_power")
        compose.onNodeWithTag("limits_power").assertTextContains(res.getString(R.string.protection_limits_power))
    }

    @Test fun aFixButtonOpensItsSetting() {
        show()
        scrollTo("step_battery")
        // The overlay step has its own buttons, so the first Fix is the battery's.
        compose.onAllNodes(hasText(res.getString(R.string.protection_fix)))[0].performClick()
        assertEquals("fix:${HealthFixKind.BATTERY_OPTIMIZATION}", calls.last())
    }
}
