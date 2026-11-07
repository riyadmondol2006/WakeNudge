package com.riyadm.wakenudge.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
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
import com.riyadm.wakenudge.missions.ChainStyle
import com.riyadm.wakenudge.missions.MissionBackup
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
        val call = AtomicInteger(); val wayOut = AtomicInteger()
        fun actions() = RingActions(
            onSnooze = { snooze.incrementAndGet() }, onDismiss = { dismiss.incrementAndGet() },
            onEmergencyStop = { emergency.incrementAndGet() }, onAddMinute = {}, onHide = {}, onInteraction = {},
            onMissionProgress = { progress.set(it) }, onEmergencyCall = { call.incrementAndGet() }, onWayOut = { wayOut.incrementAndGet() },
        )
    }

    private fun session(alarm: Alarm, snoozeCount: Int = 0, startedAt: Long = 0) = RingSession(
        alarm = alarm, kind = RingKind.PRIMARY, phase = RingPhase.RINGING, eventId = 1, scheduledAt = 0, startedAt = startedAt, snoozeCount = snoozeCount,
    )

    private val math = listOf(MissionConfig(MissionType.MATH, Difficulty.EASY, 1))

    private fun show(session: RingSession, counters: Counters) = compose.setContent {
        WakeNudgeTheme {
            RingScreen(
                session = session, settings = AppSettings(), actions = counters.actions(),
                missionContent = { missions, onInteraction, onProgress, onComplete, first -> RingMissionContent(missions, onInteraction, onProgress, onComplete, first) },
            )
        }
    }

    @Test fun plainAlarmOffersSnoozeDismissAndEmergency() {
        val c = Counters()
        show(session(Alarm(label = "Gym", snooze = SnoozeSettings(minutes = 7))), c)
        compose.onNodeWithText("Gym").assertIsDisplayed()
        compose.onNodeWithText("Snooze · 7 min").assertIsDisplayed()
        compose.onNodeWithText("Slide to dismiss").assertIsDisplayed()
        compose.onNodeWithText("Emergency").assertIsDisplayed()
        // It ends with one tap, so there is no way out to hold: it would have no purpose.
        compose.onNodeWithText("Way out without the mission").assertDoesNotExist()
        compose.onNodeWithText("Snooze · 7 min").performClick()
        assertEquals(1, c.snooze.get())
    }

    @Test fun screenReaderUsersCanDismissWithoutAGesture() {
        val c = Counters()
        show(session(Alarm()), c)
        compose.onNodeWithContentDescription("Dismiss alarm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, c.dismiss.get())
    }

    @Test fun theWayOutForScreenReadersAsksForConfirmation() {
        val c = Counters()
        show(session(Alarm(missions = math)), c)
        compose.onNodeWithContentDescription("Way out without the mission").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Stop without the mission?").assertIsDisplayed()
        assertEquals("nothing happens before confirming", 0, c.emergency.get())
        compose.onNodeWithText("Stop alarm").performClick()
        assertEquals(1, c.emergency.get())
    }

    @Test fun emergencyOpensAPanelAndCallsOnlyWhenAsked() {
        val c = Counters()
        show(session(Alarm(missions = math)), c)
        compose.onNodeWithText("Emergency").performClick()
        compose.onNodeWithText("Call or use phone").assertIsDisplayed()
        assertEquals("opening the panel pauses nothing", 0, c.call.get())
        compose.onNodeWithText("Call or use phone").performClick()
        assertEquals(1, c.call.get())
        assertEquals("Emergency never ends the alarm", 0, c.emergency.get() + c.dismiss.get())
    }

    @Test fun emergencyIsOnTheMissionScreenToo() {
        val c = Counters()
        show(session(Alarm(missions = math)), c)
        compose.onNodeWithText("Solve to dismiss").performClick()
        compose.onNodeWithText("Emergency").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Call or use phone").performClick()
        assertEquals(1, c.call.get())
    }

    @Test fun aStrictWayOutCountsDownThenOpensItsTasks() {
        val strict = AntiOversleepSettings(emergencyStopEnabled = false, wayOutDelayMinutes = 3, wayOutCount = 10)
        val c = Counters()
        show(session(Alarm(missions = math, antiOversleep = strict), startedAt = System.currentTimeMillis()), c)
        compose.onNode(hasText("Way out opens in", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Way out without the mission").assertDoesNotExist()
    }

    @Test fun anOpenStrictWayOutLeadsToItsTasks() {
        val strict = AntiOversleepSettings(emergencyStopEnabled = false, wayOutDelayMinutes = 3, wayOutCount = 10)
        val c = Counters()
        show(session(Alarm(missions = math, antiOversleep = strict), startedAt = System.currentTimeMillis() - 4 * 60_000L), c)
        compose.onNodeWithText("Way out: 10 tasks").performClick()
        compose.onNode(hasText("Way out without the mission", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Emergency").assertIsDisplayed()
        assertEquals(0, c.wayOut.get())
    }

    /** A mission that cannot be done is never passed: no Continue, and a backup mission with a keypad takes its place. */
    @Test fun anUnusableMissionInARingGetsABackupMissionNotContinue() {
        var completed = 0
        compose.setContent {
            WakeNudgeTheme {
                MissionHost(
                    listOf(MissionConfig(MissionType.QR_BARCODE)),
                    backup = MissionBackup(AntiOversleepSettings(fallbackCount = 3), style = ChainStyle.SUMS_AND_TYPING),
                    onComplete = { completed++ },
                )
            }
        }
        compose.onNodeWithText("Backup mission").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertDoesNotExist()
        compose.onNodeWithContentDescription("Submit answer").assertIsDisplayed()
        assertEquals(0, completed)
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
        answer.toString().forEach { pressKey(it) }
        compose.onNodeWithContentDescription("Submit answer").performClick()
        compose.waitForIdle()
        assertTrue("mission completed with the correct answer ($problem = $answer)", completed == 1)
    }

    @Test fun aWrongMathAnswerStaysVisibleAndTheNextKeyStartsAgain() {
        var completed = 0
        compose.setContent {
            WakeNudgeTheme { MissionHost(listOf(MissionConfig(MissionType.MATH, Difficulty.EASY, 1)), onComplete = { completed++ }) }
        }
        val problem = compose.onNode(hasContentDescriptionMatching(Regex("[\\d()]+.*[+−×÷-].*\\d+.*")))
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()
        val answer = evaluate(problem)

        // Submitting nothing is not an attempt.
        compose.onNodeWithContentDescription("Submit answer").performClick()
        compose.onAllNodesWithText("Not quite, try again").assertCountEquals(0)

        val wrong = (answer + 1).toString()
        wrong.forEach { pressKey(it) }
        compose.onNodeWithContentDescription("Submit answer").performClick()
        compose.onNodeWithText("Not quite, try again").assertIsDisplayed()
        compose.onNode(hasText(wrong) and hasClickAction().not()).assertIsDisplayed()
        assertEquals(0, completed)

        // Typing again replaces the wrong answer instead of adding to it.
        answer.toString().forEach { pressKey(it) }
        compose.onAllNodesWithText("Not quite, try again").assertCountEquals(0)
        compose.onNodeWithContentDescription("Submit answer").performClick()
        compose.waitForIdle()
        assertEquals("solved after the retry ($problem = $answer)", 1, completed)
    }

    /** A keypad key, not the answer shown above it, which can hold the same digit. */
    private fun pressKey(digit: Char) = compose.onNode(hasText(digit.toString()) and hasClickAction()).performClick()

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
