package com.riyadm.wakenudge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.center
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.engine.RingKind
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingSession
import com.riyadm.wakenudge.ui.alarms.AlarmCard
import com.riyadm.wakenudge.ui.alarms.AlarmItem
import com.riyadm.wakenudge.ui.ring.RingActions
import com.riyadm.wakenudge.ui.ring.RingMissionContent
import com.riyadm.wakenudge.ui.ring.RingScreen
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The screen as real people see it: biggest accessibility fonts, tiny phones, right-to-left languages, silly labels. */
@RunWith(AndroidJUnit4::class)
class RobustUiTest {
    @get:Rule val compose = createComposeRule()

    private fun session(alarm: Alarm = Alarm(label = "Gym")) =
        RingSession(alarm = alarm, kind = RingKind.PRIMARY, phase = RingPhase.RINGING, eventId = 1, scheduledAt = 0, startedAt = 0)

    private fun actions(dismissed: AtomicInteger = AtomicInteger()) = RingActions(
        onSnooze = {}, onDismiss = { dismissed.incrementAndGet() }, onEmergencyStop = {}, onAddMinute = {}, onHide = {},
        onInteraction = {}, onMissionProgress = {},
    )

    private fun showRing(fontScale: Float = 1f, dir: LayoutDirection = LayoutDirection.Ltr, dismissed: AtomicInteger = AtomicInteger(), width: Int = 360, height: Int = 640) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale),
                LocalLayoutDirection provides dir,
            ) {
                WakeNudgeTheme {
                    Box(Modifier.size(width.dp, height.dp)) {
                        RingScreen(
                            session = session(), settings = AppSettings(), actions = actions(dismissed),
                            missionContent = { m, i, p, c, f -> RingMissionContent(m, i, p, c, f) },
                        )
                    }
                }
            }
        }
    }

    @Test fun everyRingControlIsReachableAtTheLargestFontOnASmallPhone() {
        showRing(fontScale = 2f, width = 320, height = 480)
        for (label in listOf("Slide to dismiss", "Emergency stop")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }

    private fun fontScaleKeepsEveryControlReachable(scale: Float) {
        showRing(fontScale = scale)
        compose.onNodeWithText("Emergency stop").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Slide to dismiss").performScrollTo().assertIsDisplayed()
    }

    @Test fun smallestFontScale() = fontScaleKeepsEveryControlReachable(0.85f)
    @Test fun largeFontScale() = fontScaleKeepsEveryControlReachable(1.3f)
    @Test fun veryLargeFontScale() = fontScaleKeepsEveryControlReachable(1.5f)
    @Test fun largestFontScale() = fontScaleKeepsEveryControlReachable(2f)

    @Test fun slideToDismissWorksLeftToRight() {
        val dismissed = AtomicInteger()
        showRing(dismissed = dismissed)
        compose.onNodeWithContentDescription("Dismiss alarm").performTouchInput {
            swipe(start = androidx.compose.ui.geometry.Offset(60f, center.y), end = androidx.compose.ui.geometry.Offset(width - 8f, center.y), durationMillis = 400)
        }
        compose.waitForIdle()
        assertEquals(1, dismissed.get())
    }

    @Test fun slideToDismissWorksInRightToLeftLanguages() {
        val dismissed = AtomicInteger()
        showRing(dir = LayoutDirection.Rtl, dismissed = dismissed)
        // In Arabic/Hebrew the thumb starts on the right and travels left.
        compose.onNodeWithContentDescription("Dismiss alarm").performTouchInput {
            swipe(start = androidx.compose.ui.geometry.Offset(width - 60f, center.y), end = androidx.compose.ui.geometry.Offset(8f, center.y), durationMillis = 400)
        }
        compose.waitForIdle()
        assertEquals("RTL users must be able to dismiss", 1, dismissed.get())
    }

    @Test fun aMissionThatCannotBeDoneNeverTrapsTheUser() {
        var done = 0
        compose.setContent {
            WakeNudgeTheme {
                // A QR mission whose code was never registered (setup skipped, or the alarm was restored from a backup).
                com.riyadm.wakenudge.missions.MissionHost(
                    listOf(com.riyadm.wakenudge.domain.model.MissionConfig(com.riyadm.wakenudge.domain.model.MissionType.QR_BARCODE)),
                    onComplete = { done++ },
                )
            }
        }
        compose.onNodeWithText("Not set up yet").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertEquals(1, done)
    }

    @Test fun alarmCardCopesWithAHostileLabelAndHugeFonts() {
        val item = AlarmItem(
            alarm = Alarm(label = "مرحبا ".repeat(40) + "👩‍👩‍👧‍👦".repeat(10) + "x".repeat(400), hour = 7, minute = 5),
            group = null, nextAt = ZonedDateTime.now().plusHours(5), suppression = null, lockedMinutes = null,
        )
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                WakeNudgeTheme {
                    Box(Modifier.size(320.dp, 600.dp)) {
                        AlarmCard(item, AppSettings(), ZonedDateTime.now(), {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("More options").assertIsDisplayed()
    }
}
