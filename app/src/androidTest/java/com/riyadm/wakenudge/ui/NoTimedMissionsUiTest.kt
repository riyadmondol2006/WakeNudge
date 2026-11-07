package com.riyadm.wakenudge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.missions.MissionInfo
import com.riyadm.wakenudge.missions.ui.MissionAccessibility
import com.riyadm.wakenudge.ui.edit.MissionListEditor
import com.riyadm.wakenudge.ui.ring.ProofCheckScreen
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "I can't do timed or physical missions": the picker leaves those out, and the still-awake check asks for sums. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NoTimedMissionsUiTest {
    // The app's receivers need a component: a system broadcast during the run (a clock or time-zone change) would
    // otherwise crash the whole process, and the run would end with no result at all.
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun title(type: MissionType) = context.getString(MissionInfo.titleRes(type))

    @After fun reset() {
        MissionAccessibility.noTimedMissions.value = false
    }

    private fun openPicker() {
        compose.setContent {
            WakeNudgeTheme {
                Column { MissionListEditor(title = "Missions", hint = "", missions = emptyList(), enabled = true, maxMissions = 5, onChange = {}) }
            }
        }
        compose.onNodeWithText(context.getString(R.string.edit_missions_add)).performClick()
        compose.waitForIdle()
    }

    @Test fun theSettingHidesTimedAndPhysicalMissionsFromThePicker() {
        MissionAccessibility.noTimedMissions.value = true
        openPicker()
        for (t in listOf(MissionType.SHAKE, MissionType.STEPS, MissionType.SQUATS, MissionType.FOCUS_TAP, MissionType.MEMORY)) {
            compose.onNodeWithText(title(t)).assertDoesNotExist()
        }
        compose.onNode(hasText(title(MissionType.QR_BARCODE))).assertExists()
        compose.onNode(hasText(title(MissionType.MATH))).assertExists()
    }

    @Test fun withoutTheSettingThePickerOffersThemAll() {
        openPicker()
        compose.onNode(hasText(title(MissionType.FOCUS_TAP))).assertExists()
    }

    @Test fun theStillAwakeCheckAsksForSumsInsteadOfFocusTap() {
        compose.setContent {
            WakeNudgeTheme { ProofCheckScreen(deadlineMillis = System.currentTimeMillis() + 60_000, sums = true, onProof = {}, onExpired = {}) }
        }
        compose.onNode(hasText(context.getString(R.string.wake_check_proof_body_sums), substring = true)).assertExists()
        compose.onNode(hasText(context.getString(R.string.wake_check_proof_body), substring = true)).assertDoesNotExist()
    }
}
