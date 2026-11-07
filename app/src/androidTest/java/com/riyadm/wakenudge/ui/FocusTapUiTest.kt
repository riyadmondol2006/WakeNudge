package com.riyadm.wakenudge.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.missions.focus.FocusCue
import com.riyadm.wakenudge.missions.focus.FocusGame
import com.riyadm.wakenudge.missions.focus.FocusMission
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Focus Tap as a person sees it: by touch, by screen reader, right to left and at double font size. */
@RunWith(AndroidJUnit4::class)
class FocusTapUiTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val config = MissionConfig(MissionType.FOCUS_TAP, Difficulty.EASY, count = 1)

    private fun FocusCue.label(): String =
        context.getString(context.resources.getIdentifier("mission_focus_${color.name.lowercase()}_${shape.name.lowercase()}", "string", context.packageName))

    /** The same seed the mission is given asks for the same shape first. */
    private fun targetOf(seed: Int) = FocusGame.forDifficulty(Difficulty.EASY, 1, Random(seed)).target.label()

    private fun show(seed: Int, screenReader: Boolean = false, fontScale: Float = 1f, direction: LayoutDirection = LayoutDirection.Ltr, onComplete: () -> Unit) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale), LocalLayoutDirection provides direction) {
                WakeNudgeTheme { FocusMission(config, onInteraction = {}, onComplete = onComplete, random = Random(seed), screenReader = screenReader) }
            }
        }
    }

    private fun shapeShown(name: String) = hasContentDescription(name) and hasClickAction()

    private fun anyShapeBut(name: String) = SemanticsMatcher("a shape that is not $name") { node ->
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        description != null && description != name && node.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
            Regex("(blue|orange) (circle|square|triangle|diamond)").matches(description)
    }

    private fun tapTargetTimes(target: String, times: Int) {
        repeat(times) {
            compose.waitUntil(15_000) { compose.onAllNodes(shapeShown(target)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(shapeShown(target)).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(shapeShown(target)).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test fun onlyTheShapeAskedForCountsAndThreeOfThemFinishAnEasyRound() {
        var done = 0
        val target = targetOf(11)
        show(11) { done++ }
        compose.onNodeWithText("Tap only the $target").assertIsDisplayed()
        tapTargetTimes(target, 3)
        compose.waitUntil(5_000) { done == 1 }
        assertEquals(1, done)
    }

    @Test fun aWrongTapSaysSoAndStartsTheRoundAgain() {
        var done = 0
        val target = targetOf(12)
        show(12) { done++ }
        tapTargetTimes(target, 1)
        compose.onNodeWithText("1 of 3").assertIsDisplayed()
        compose.waitUntil(15_000) { compose.onAllNodes(anyShapeBut(target)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(anyShapeBut(target)).performClick()
        compose.onNodeWithText("Not that one. This round starts again.").assertIsDisplayed()
        assertEquals(0, done)
    }

    @Test fun aScreenReaderUserAnswersEachAnnouncedShapeWithNothingTimed() {
        var done = 0
        val target = targetOf(13)
        show(13, screenReader = true) { done++ }
        var guard = 0
        while (done == 0 && guard++ < 200) {
            compose.waitUntil(5_000) { done > 0 || compose.onAllNodes(hasTextStarting("Now: ")).fetchSemanticsNodes().isNotEmpty() }
            if (done > 0) break
            val now = compose.onNode(hasTextStarting("Now: ")).fetchSemanticsNode().config[SemanticsProperties.Text].first().text
            // Waiting changes nothing: the shape stays until it is answered.
            Thread.sleep(1_600)
            compose.onNodeWithText(now).assertIsDisplayed()
            compose.onNodeWithText(if (now == "Now: $target") "That's it" else "Not this one").performClick()
            compose.waitUntil(5_000) { done > 0 || compose.onAllNodes(hasTextStarting("Now: ")).fetchSemanticsNodes().isEmpty() }
        }
        compose.waitUntil(5_000) { done == 1 }
    }

    @Test fun rightToLeftAtDoubleFontSizeStillFitsAndCanBeFinished() {
        var done = 0
        val target = targetOf(14)
        show(14, fontScale = 2f, direction = LayoutDirection.Rtl) { done++ }
        compose.onNodeWithText("Tap only the $target").assertIsDisplayed()
        tapTargetTimes(target, 3)
        compose.waitUntil(5_000) { done == 1 }
    }

    private fun hasTextStarting(prefix: String) = SemanticsMatcher("text starting with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(prefix) } == true
    }
}
