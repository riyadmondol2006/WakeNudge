package com.riyadm.wakenudge.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.ui.components.SwitchRow
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A settings row with a switch is one big toggle: tapping the text works, and so does tapping the switch itself. The
 * switch used to be a disabled control of its own that swallowed the tap, so only the text responded.
 */
@RunWith(AndroidJUnit4::class)
class SwitchRowUiTest {
    @get:Rule val compose = createComposeRule()

    private val title = "Keep the alarm screen in front"
    private val changes = mutableListOf<Boolean>()

    private fun setRow(enabled: Boolean = true, initial: Boolean = false) {
        compose.setContent {
            WakeNudgeTheme {
                var on by remember { mutableStateOf(initial) }
                SwitchRow(
                    title = title,
                    subtitle = "If you leave the screen while an alarm rings, bring it back.",
                    checked = on,
                    onCheckedChange = { on = it; changes += it },
                    shape = RoundedCornerShape(28.dp),
                    enabled = enabled,
                )
            }
        }
    }

    @Test fun tappingTheSwitchItselfTogglesTheRow() {
        setRow()
        compose.onNode(isToggleable()).assertIsOff()
        // The switch is the right-hand end of the row: 20 dp padding, then the 64 dp track, so its middle is 52 dp in.
        compose.onNode(isToggleable()).performTouchInput { click(Offset(width - 52.dp.toPx(), centerY)) }
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNode(isToggleable()).performTouchInput { click(Offset(width - 52.dp.toPx(), centerY)) }
        compose.onNode(isToggleable()).assertIsOff()
        assertEquals("one change per tap on the switch", listOf(true, false), changes)
    }

    @Test fun tappingTheTextStillTogglesTheRow() {
        setRow(initial = true)
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText(title).performTouchInput { click(Offset(24.dp.toPx(), centerY)) }
        compose.onNode(isToggleable()).assertIsOff()
        assertEquals(listOf(false), changes)
    }

    @Test fun theRowIsOneSwitchWithItsTitleNotASwitchPlusADisabledControl() {
        setRow()
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNode(isToggleable())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertTextContains(title)
    }

    @Test fun aDisabledRowIgnoresTapsOnTheSwitchAndOnTheText() {
        setRow(enabled = false)
        compose.onNodeWithText(title).performTouchInput { click(Offset(width - 52.dp.toPx(), centerY)) }
        compose.onNodeWithText(title).performTouchInput { click(Offset(24.dp.toPx(), centerY)) }
        assertTrue("a disabled row must not change", changes.isEmpty())
    }
}
