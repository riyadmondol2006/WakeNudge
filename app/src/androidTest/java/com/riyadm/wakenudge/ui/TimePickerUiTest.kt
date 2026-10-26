package com.riyadm.wakenudge.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.ui.components.AlarmTimePicker
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The alarm time wheels. A time typed into the "Type a time" dialog moves the wheels by themselves; the rows they pass
 * on the way used to be reported as choices, so a typed 06:45 over 07:00 was saved as 06:58 or so.
 */
@RunWith(AndroidJUnit4::class)
class TimePickerUiTest {
    @get:Rule val compose = createComposeRule()

    private val reported = mutableListOf<Pair<Int, Int>>()
    private var hour by mutableIntStateOf(7)
    private var minute by mutableIntStateOf(0)
    private var enabled by mutableStateOf(true)

    private fun setPicker(direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WakeNudgeTheme {
                    AlarmTimePicker(hour, minute, is24 = true, enabled = enabled, onChange = { h, m ->
                        reported += h to m
                        hour = h
                        minute = m
                    })
                }
            }
        }
    }

    private fun shown(description: String): String =
        compose.onNodeWithContentDescription(description).fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    @Test fun aTypedTimeIsKeptExactlyWhileTheWheelsGlideToIt() {
        setPicker()
        compose.waitForIdle()
        // What the "Type a time" dialog does: set both values at once from outside.
        compose.runOnIdle {
            hour = 6
            minute = 45
        }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertEquals("the wheels' own glide is not a choice", emptyList<Pair<Int, Int>>(), reported)
        assertEquals(6 to 45, hour to minute)
        assertEquals("06", shown("Hour"))
        assertEquals("45", shown("Minute"))
    }

    @Test fun aBigJumpOnTheMinuteWheelAlsoLandsOnTheTypedValue() {
        setPicker()
        compose.waitForIdle()
        compose.runOnIdle { minute = 31 }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertTrue(reported.isEmpty())
        assertEquals(7 to 31, hour to minute)
    }

    @Test fun turningAWheelByHandIsStillReported() {
        setPicker()
        compose.onNodeWithContentDescription("Minute").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("a swipe changes the minute", reported.isNotEmpty() && minute != 0)
    }

    @Test fun aLockedWheelCannotBeTurned() {
        enabled = false
        setPicker()
        compose.onNodeWithContentDescription("Minute").performTouchInput { swipeUp() }
        compose.onNodeWithContentDescription("Hour").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(reported.isEmpty())
        assertEquals("00", shown("Minute"))
        assertEquals("07", shown("Hour"))
    }

    @Test fun hoursStayLeftOfMinutesInRightToLeftLanguages() {
        setPicker(LayoutDirection.Rtl)
        val hourBox = compose.onNodeWithContentDescription("Hour").getUnclippedBoundsInRoot()
        val minuteBox = compose.onNodeWithContentDescription("Minute").getUnclippedBoundsInRoot()
        assertTrue("hour at ${hourBox.left}, minute at ${minuteBox.left}", hourBox.left < minuteBox.left)
    }
}
