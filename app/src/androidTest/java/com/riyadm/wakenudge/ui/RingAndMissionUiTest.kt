package com.riyadm.wakenudge.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.engine.RingKind
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingSession
import com.riyadm.wakenudge.missions.MissionHost
import com.riyadm.wakenudge.ui.ring.RingActions
import com.riyadm.wakenudge.ui.ring.RingMissionContent
import com.riyadm.wakenudge.ui.ring.RingScreen
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RingAndMissionUiTest {
    @get:Rule val compose = createComposeRule()

    private class Counters {
        val snooze = AtomicInteger(); val dismiss = AtomicInteger(); val emergency = AtomicInteger(); val progress = AtomicInteger(-1)
        fun actions() = RingActions(
            onSnooze = { snooze.incrementAndGet() }, onDismiss = { dismiss.incrementAndGet() },
            onEmergencyStop = { emergency.incrementAndGet() }, onAddMinute = {}, onHide = {}, onInteraction = {},
            onMissionProgress = { progress.set(it) },
        )
    }

    private fun session(alarm: Alarm, snoozeCount: Int = 0) = RingSession(
        alarm = alarm, kind = RingKind.PRIMARY, phase = RingPhase.RINGING, eventId = 1, scheduledAt = 0, startedAt = 0, snoozeCount = snoozeCount,
    )

    private fun show(session: RingSession, counters: Counters) = compose.setContent {
        WakeNudgeTheme {
            RingScreen(
                session = session, settings = AppSettings(), actions = counters.actions(),
                missionContent = { missions, onInteraction, onProgress, onComplete -> RingMissionContent(missions, onInteraction, onProgress, onComplete) },
            )
        }
    }

    @Test fun plainAlarmOffersSnoozeDismissAndEmergencyStop() {
        val c = Counters()
        show(session(Alarm(label = "Gym", snooze = SnoozeSettings(minutes = 7))), c)
        compose.onNodeWithText("Gym").assertIsDisplayed()
        compose.onNodeWithText("Snooze · 7 min").assertIsDisplayed()
        compose.onNodeWithText("Slide to dismiss").assertIsDisplayed()
        compose.onNodeWithText("Emergency stop").assertIsDisplayed()
        compose.onNodeWithText("Snooze · 7 min").performClick()
        assertEquals(1, c.snooze.get())
    }

    @Test fun screenReaderUsersCanDismissWithoutAGesture() {
        val c = Counters()
        show(session(Alarm()), c)
        compose.onNodeWithContentDescription("Dismiss alarm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, c.dismiss.get())
    }

    @Test fun emergencyStopForScreenReadersAsksForConfirmation() {
        val c = Counters()
        show(session(Alarm()), c)
        compose.onNodeWithContentDescription("Emergency stop").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Stop the alarm now?").assertIsDisplayed()
        assertEquals("nothing happens before confirming", 0, c.emergency.get())
        compose.onNodeWithText("Stop alarm").performClick()
        assertEquals(1, c.emergency.get())
    }

    @Test fun snoozeButtonDisappearsAfterTheLimit() {
        val c = Counters()
        show(session(Alarm(snooze = SnoozeSettings(maxCount = 2)), snoozeCount = 2), c)
        compose.onNodeWithText("No more snoozes — time to get up!").assertIsDisplayed()
    }

    @Test fun holdToDismissReplacesTheSlider() {
        val c = Counters()
        show(session(Alarm(antiOversleep = AntiOversleepSettings(holdToDismissSeconds = 3))), c)
        compose.onNodeWithText("Hold 3 s to dismiss").assertIsDisplayed()
    }

    @Test fun missionsGateTheDismissButton() {
        val c = Counters()
        show(session(Alarm(missions = listOf(MissionConfig(MissionType.MATH, Difficulty.EASY, 1)))), c)
        compose.onNodeWithText("Solve to dismiss").performClick()
        compose.onNodeWithText("Complete to dismiss").assertIsDisplayed()
        assertEquals("not dismissed yet", 0, c.dismiss.get())
    }

    @Test fun mathMissionAcceptsOnlyTheRightAnswerThenCompletes() {
        var completed = 0
        compose.setContent {
            WakeNudgeTheme { MissionHost(listOf(MissionConfig(MissionType.MATH, Difficulty.EASY, 1)), onComplete = { completed++ }) }
        }
        // Read the problem, compute its answer, type it on the keypad.
        val problem = compose.onNode(hasContentDescriptionMatching(Regex("[\\d()]+.*[+−×÷-].*\\d+.*")))
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()
        val answer = evaluate(problem)
        answer.toString().forEach { compose.onNodeWithText(it.toString()).performClick() }
        compose.onNodeWithContentDescription("Submit answer").performClick()
        compose.waitForIdle()
        assertTrue("mission completed with the correct answer ($problem = $answer)", completed == 1)
    }

    @Test fun previewModeCanAlwaysBeSkipped() {
        var completed = 0
        compose.setContent {
            WakeNudgeTheme { MissionHost(listOf(MissionConfig(MissionType.TYPING)), previewMode = true, onComplete = { completed++ }) }
        }
        compose.onNodeWithText("Skip").performClick()
        compose.waitForIdle()
        assertEquals(1, completed)
    }
}

private fun hasContentDescriptionMatching(regex: Regex) = SemanticsMatcher("content description matches $regex") { node ->
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { regex.matches(it) } == true &&
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.none { it.startsWith("Problem") } == true
}

/** Evaluates the app's displayed arithmetic: + − × ÷ with normal precedence and parentheses. */
private fun evaluate(expression: String): Long {
    val tokens = Regex("\\d+|[()+\\-−×÷*/]").findAll(expression).map { it.value }.toList()
    var pos = 0
    fun parseExpr(): Long {
        fun parseTerm(): Long {
            fun parseFactor(): Long {
                val t = tokens[pos++]
                if (t == "(") { val v = parseExpr(); pos++; return v }
                return t.toLong()
            }
            var v = parseFactor()
            while (pos < tokens.size && tokens[pos] in listOf("×", "*", "÷", "/")) {
                val op = tokens[pos++]; val r = parseFactor()
                v = if (op == "×" || op == "*") v * r else v / r
            }
            return v
        }
        var v = parseTerm()
        while (pos < tokens.size && tokens[pos] in listOf("+", "-", "−")) {
            val op = tokens[pos++]; val r = parseTerm()
            v = if (op == "+") v + r else v - r
        }
        return v
    }
    return parseExpr()
}
