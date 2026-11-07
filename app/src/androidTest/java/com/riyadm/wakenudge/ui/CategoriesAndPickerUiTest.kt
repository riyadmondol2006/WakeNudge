package com.riyadm.wakenudge.ui

import androidx.compose.material3.Text
import com.riyadm.wakenudge.engine.audio.AlarmVibrator
import com.riyadm.wakenudge.ui.ring.RingScreen
import com.riyadm.wakenudge.ui.ring.RingMissionContent
import com.riyadm.wakenudge.ui.ring.RingActions
import com.riyadm.wakenudge.ui.edit.rememberExpandedCategories
import com.riyadm.wakenudge.ui.edit.alarmCategories
import com.riyadm.wakenudge.ui.edit.CategoryOptions
import com.riyadm.wakenudge.ui.edit.HowHardCard
import com.riyadm.wakenudge.ui.edit.ProtectionLink
import com.riyadm.wakenudge.ui.edit.StoppingSection
import com.riyadm.wakenudge.ui.edit.VolumeSection
import com.riyadm.wakenudge.domain.model.Hardness
import com.riyadm.wakenudge.domain.model.HardnessPresets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.riyadm.wakenudge.engine.RingSession
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingKind
import com.riyadm.wakenudge.domain.model.AppSettings
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.domain.model.SoundSource
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.engine.audio.BundledTones
import com.riyadm.wakenudge.engine.audio.SoundPreviewer
import com.riyadm.wakenudge.ui.edit.AlarmCategory
import com.riyadm.wakenudge.ui.edit.CategoryCard
import com.riyadm.wakenudge.ui.edit.SoundPickerSheet
import com.riyadm.wakenudge.ui.edit.categorySummary
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Choosing a sound plays it at once; "How hard?" sets and names each level; More options groups into cards that summarise
 * themselves, and every option fits a 360 dp phone at the largest font.
 */
@RunWith(AndroidJUnit4::class)
class CategoriesAndPickerUiTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val previewer = SoundPreviewer(ApplicationProvider.getApplicationContext())
    private val vibratorForTest = AlarmVibrator(ApplicationProvider.getApplicationContext())

    @After fun silence() = previewer.stop()

    private fun toneName(id: String) = context.getString(BundledTones.byId(id)!!.nameRes)

    @Test fun choosingATonePlaysItImmediatelyAndSwitchesWhenAnotherIsChosen() {
        var chosen = SoundSettings()
        compose.setContent {
            WakeNudgeTheme { SoundPickerSheet(sound = chosen, onChange = { chosen = it }, onDismiss = {}, previewer = previewer) }
        }
        compose.onNodeWithText(toneName("ember")).performClick()
        compose.waitForIdle()
        assertEquals("the chosen tone plays without pressing the play button", "ember", previewer.playing.value)
        assertEquals("ember", chosen.bundledId)
        assertEquals(SoundSource.BUNDLED, chosen.source)

        compose.onNodeWithText(toneName("chime")).performClick()
        compose.waitForIdle()
        assertEquals("only the newest tone plays", "chime", previewer.playing.value)
        assertEquals("chime", chosen.bundledId)

        // Choosing the same tone again restarts it instead of toggling it off.
        compose.onNodeWithText(toneName("chime")).performClick()
        compose.waitForIdle()
        assertEquals("chime", previewer.playing.value)
    }

    @Test fun thePreviewNeverOutlivesThePicker() {
        var show by mutableStateOf(true)
        compose.setContent {
            WakeNudgeTheme {
                if (show) SoundPickerSheet(sound = SoundSettings(), onChange = {}, onDismiss = {}, previewer = previewer)
            }
        }
        compose.onNodeWithText(toneName("ember")).performClick()
        compose.waitForIdle()
        assertEquals("ember", previewer.playing.value)

        show = false
        compose.waitForIdle()
        assertNull("closing the picker silences the preview", previewer.playing.value)
    }

    @Test fun aCategoryCardShowsItsSummaryUntilOpenedAndThenItsOptions() {
        var open by mutableStateOf(false)
        compose.setContent {
            WakeNudgeTheme {
                CategoryCard(
                    icon = AlarmCategory.VOLUME.icon, title = "Sound and light", summary = "Sunrise · Pulse",
                    expanded = open, onToggle = { open = !open },
                ) { Text("Alarm sound options") }
            }
        }
        compose.onNodeWithText("Sunrise · Pulse").assertIsDisplayed()
        compose.onNodeWithText("Alarm sound options").assertDoesNotExistOrHidden()

        compose.onNodeWithText("Sound and light").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Alarm sound options").assertIsDisplayed()
        assertTrue(open)

        compose.onNodeWithText("Sound and light").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sunrise · Pulse").assertIsDisplayed()
    }

    @Test fun summariesTellTheCurrentSettingsInOneLine() {
        val alarm = Alarm(
            missions = listOf(MissionConfig(MissionType.MATH), MissionConfig(MissionType.TYPING)),
            volume = VolumeSettings(level = 70, rampSeconds = 30, extraLoud = true, extraLoudPercent = 60),
            snooze = SnoozeSettings(enabled = true, minutes = 7, maxCount = 2),
            antiOversleep = AntiOversleepSettings(holdToDismissSeconds = 3, backupEnabled = true, backupDelayMinutes = 12, volumeFloorPercent = 50),
        )
        val bare = Alarm(snooze = SnoozeSettings(enabled = false), volume = VolumeSettings(level = 0, extraLoud = false))
        val summaries = mutableMapOf<AlarmCategory, String>()
        val plain = mutableMapOf<AlarmCategory, String>()
        compose.setContent {
            WakeNudgeTheme {
                AlarmCategory.entries.forEach {
                    summaries[it] = categorySummary(it, alarm)
                    plain[it] = categorySummary(it, bare)
                }
            }
        }
        compose.waitForIdle()
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.VOLUME).contains("70%"))
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.VOLUME).contains("Extra loud 60%"))
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.VOLUME).contains("Never below 50%"))
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.SNOOZE).contains("7 min"))
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.SNOOZE).contains("up to 2 times"))
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.STOPPING).contains("Rings again after 12 min"))
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.STOPPING).contains("Hold 3 s to stop"))

        // A switched-off snooze, no check and the phone's own volume say so plainly.
        assertEquals("Off", plain.getValue(AlarmCategory.SNOOZE))
        assertEquals("Off", plain.getValue(AlarmCategory.STILL_UP))
        assertTrue(plain.toString(), plain.getValue(AlarmCategory.VOLUME).startsWith("Phone's volume"))
    }

    // ---- How hard? ------------------------------------------------------------------------------------------------

    @Test fun pickingALevelSetsItAndTheCardReadsThatLevel() {
        var alarm by mutableStateOf(Alarm())
        compose.setContent {
            WakeNudgeTheme {
                HowHardCard(selected = HardnessPresets.levelOf(alarm), enabled = true, onPick = { alarm = HardnessPresets.apply(it, alarm) })
            }
        }
        compose.onNodeWithText("Easy").assertIsSelected()
        compose.onNodeWithText("Custom").assertDoesNotExist()
        for ((name, level) in listOf("Normal" to Hardness.NORMAL, "Hard" to Hardness.HARD, "No mercy" to Hardness.NO_MERCY, "Easy" to Hardness.EASY)) {
            compose.onNodeWithText(name).performClick()
            compose.waitForIdle()
            assertEquals(name, level, HardnessPresets.levelOf(alarm))
            compose.onNodeWithText(name).assertIsSelected()
        }
        // Every level says what it does, without opening anything.
        compose.onNodeWithText("Swipe to stop. You can snooze.").assertIsDisplayed()
        compose.onNodeWithText("Hard, with two checks and the strictest limits.").assertIsDisplayed()
    }

    @Test fun aChangedDetailShowsCustomAndPuttingItBackShowsTheLevelAgain() {
        var alarm by mutableStateOf(HardnessPresets.apply(Hardness.HARD, Alarm()))
        compose.setContent {
            WakeNudgeTheme {
                HowHardCard(selected = HardnessPresets.levelOf(alarm), enabled = true, onPick = { alarm = HardnessPresets.apply(it, alarm) })
            }
        }
        compose.onNodeWithText("Hard").assertIsSelected()
        alarm = alarm.copy(antiOversleep = alarm.antiOversleep.copy(muteBudget = 4))
        compose.waitForIdle()
        compose.onNodeWithText("Custom").assertIsSelected()
        compose.onNodeWithText("You changed the details in More options.").assertIsDisplayed()
        alarm = alarm.copy(antiOversleep = alarm.antiOversleep.copy(muteBudget = 2))
        compose.waitForIdle()
        compose.onNodeWithText("Hard").assertIsSelected()
        compose.onNodeWithText("Custom").assertDoesNotExist()
    }

    @Test fun theProtectionLineShowsUnderHardAndOpensTheSetup() {
        var opened = 0
        var level by mutableStateOf(Hardness.NORMAL)
        compose.setContent {
            WakeNudgeTheme { HowHardCard(selected = level, enabled = true, onPick = { level = it }, stepsLeft = 2, onOpenProtection = { opened++ }) }
        }
        compose.onNodeWithText("2 steps left to stop you leaving the alarm").assertDoesNotExist()
        level = Hardness.HARD
        compose.waitForIdle()
        compose.onNodeWithText("2 steps left to stop you leaving the alarm").performClick()
        assertEquals(1, opened)
    }

    // ---- More options ---------------------------------------------------------------------------------------------

    @Test fun thePhonesVolumeIsItsOwnSwitchAndTheSliderNeverReachesIt() {
        var alarm by mutableStateOf(Alarm(volume = VolumeSettings(level = 80)))
        compose.setContent {
            WakeNudgeTheme { androidx.compose.foundation.lazy.LazyColumn { item { VolumeSection(alarm, enabled = true, onChange = { f -> alarm = f(alarm) }) } } }
        }
        // Dragged all the way down, the slider is at its quietest, not "leave the phone's volume" (which could be loud).
        compose.onNodeWithContentDescription("How loud, 80%").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.waitForIdle()
        assertEquals(10, alarm.volume.level)
        compose.onNodeWithText("Use the phone's alarm volume").performClick()
        compose.waitForIdle()
        assertEquals(0, alarm.volume.level)
        compose.onNodeWithText("How loud").assertDoesNotExist()
        compose.onNodeWithText("Use the phone's alarm volume").performClick()
        compose.waitForIdle()
        assertEquals(80, alarm.volume.level)
    }

    @Test fun keepRingingIsASwitchAndStopAfterNeverStepsBelowOneMinute() {
        var alarm by mutableStateOf(Alarm(antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 2)))
        compose.setContent {
            WakeNudgeTheme { androidx.compose.foundation.lazy.LazyColumn { item { StoppingSection(alarm, enabled = true, onChange = { f -> alarm = f(alarm) }, protection = null) } } }
        }
        repeat(3) { runCatching { compose.onNodeWithContentDescription("Decrease Stop ringing after").performClick() } }
        compose.waitForIdle()
        assertEquals("one minute is the shortest, never Never", 1, alarm.antiOversleep.ringTimeoutMinutes)
        compose.onNodeWithText("Keep ringing until I finish the mission").performClick()
        compose.waitForIdle()
        assertEquals(AntiOversleepSettings.RING_TIMEOUT_NEVER, alarm.antiOversleep.ringTimeoutMinutes)
        compose.onNodeWithText("Stop ringing after").assertDoesNotExist()
        compose.onNodeWithText("Keep ringing until I finish the mission").performClick()
        compose.waitForIdle()
        assertEquals(10, alarm.antiOversleep.ringTimeoutMinutes)
    }

    @Test fun theWayOutIsHoldOrTasksAndNoneNeedsARehearsalAndAWarning() {
        var rehearsed by mutableStateOf(false)
        var alarm by mutableStateOf(Alarm(missions = listOf(MissionConfig(MissionType.MATH))))
        compose.setContent {
            WakeNudgeTheme {
                androidx.compose.foundation.lazy.LazyColumn {
                    item { StoppingSection(alarm, enabled = true, onChange = { f -> alarm = f(alarm) }, protection = ProtectionLink({ rehearsed }, {})) }
                }
            }
        }
        compose.onNodeWithText("None").assertDoesNotExist()
        compose.onNodeWithText("Tasks").performClick()
        compose.waitForIdle()
        assertEquals(false, alarm.antiOversleep.emergencyStopEnabled)
        compose.onNodeWithText("Tasks open after").assertIsDisplayed()
        compose.onNodeWithText("Hold 10 s").performClick()
        compose.waitForIdle()
        assertTrue(alarm.antiOversleep.emergencyStopEnabled)

        rehearsed = true
        compose.waitForIdle()
        compose.onNodeWithText("None").performClick()
        compose.waitForIdle()
        assertEquals("nothing changes before the warning is confirmed", false, alarm.antiOversleep.wayOutRemoved)
        compose.onNodeWithText("Remove it").performClick()
        compose.waitForIdle()
        assertTrue(alarm.antiOversleep.wayOutRemoved)
        compose.onNodeWithText("Hold 10 s").performClick()
        compose.waitForIdle()
        assertEquals("putting it back needs nothing", false, alarm.antiOversleep.wayOutRemoved)
    }

    @Test fun everyLevelAndEveryOptionFitsAt360dpWithTheLargestFont() {
        var alarm by mutableStateOf(HardnessPresets.apply(Hardness.HARD, Alarm()))
        val titles = listOf(
            "No mercy", "Volume and vibration", "Snooze", "Stopping it", "If I try to get out of it", "Voice and light", "Still up?", "Schedule rules",
        )
        compose.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, 2f)) {
                WakeNudgeTheme {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        val expanded = rememberExpandedCategories()
                        androidx.compose.foundation.lazy.LazyColumn {
                            item { HowHardCard(selected = HardnessPresets.levelOf(alarm), enabled = true, onPick = { alarm = HardnessPresets.apply(it, alarm) }, stepsLeft = 3, onOpenProtection = {}) }
                            alarmCategories(alarm, true, expanded, CategoryOptions(protection = ProtectionLink({ true }, {})), vibratorForTest) { f -> alarm = f(alarm) }
                        }
                    }
                }
            }
        }
        for (title in titles) {
            compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("No mercy"))
        compose.onNodeWithText("No mercy").performClick()
        compose.waitForIdle()
        assertEquals(Hardness.NO_MERCY, HardnessPresets.levelOf(alarm))
    }

    // ---- strict alarms: the way out and snooze are per-alarm switches ----------------------------------------------

    // Started now: the way out's delay counts from the start of the ring on the wall clock, so a ring from 1970 has it open.
    private fun ringSession(alarm: Alarm): RingSession {
        val now = System.currentTimeMillis()
        return RingSession(alarm = alarm, kind = RingKind.PRIMARY, phase = RingPhase.RINGING, eventId = 1, scheduledAt = now, startedAt = now)
    }

    private fun showRing(alarm: Alarm) {
        compose.setContent {
            WakeNudgeTheme {
                Box(Modifier.size(393.dp, 851.dp)) {
                    RingScreen(
                        session = ringSession(alarm), settings = AppSettings(),
                        actions = RingActions(
                            onSnooze = {}, onDismiss = {}, onEmergencyStop = {}, onAddMinute = {}, onHide = {},
                            onInteraction = {}, onMissionProgress = {},
                        ),
                        missionContent = { m, i, p, c, f -> RingMissionContent(m, i, p, c, f) },
                    )
                }
            }
        }
    }

    @Test fun theRingScreenOffersTheHoldWayOutByDefault() {
        showRing(Alarm(label = "Gym", missions = listOf(MissionConfig(MissionType.MATH))))
        compose.onNodeWithText("Way out without the mission").assertIsDisplayed()
        compose.onNodeWithText("Emergency").assertIsDisplayed()
    }

    @Test fun aStrictAlarmHasASlowWayOutAndEmergencyInsteadOfAHold() {
        showRing(Alarm(label = "Flight", missions = listOf(MissionConfig(MissionType.MATH)), antiOversleep = AntiOversleepSettings(emergencyStopEnabled = false, wayOutDelayMinutes = 3)))
        compose.onNodeWithText("Way out without the mission").assertDoesNotExist()
        compose.onNode(hasText("Way out opens in", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Emergency").assertIsDisplayed()
    }

    @Test fun anAlarmThatEndsWithOneTapHasNoWayOutButStillEmergency() {
        showRing(Alarm(label = "Nap"))
        compose.onNodeWithText("Way out without the mission").assertDoesNotExist()
        compose.onNodeWithText("Slide to dismiss").assertIsDisplayed()
        compose.onNodeWithText("Emergency").assertIsDisplayed()
    }

    @Test fun theSnoozeSwitchOnItsCardWorksWithoutOpeningTheCard() {
        var alarm by mutableStateOf(Alarm())
        compose.setContent {
            WakeNudgeTheme {
                val expanded = rememberExpandedCategories()
                androidx.compose.foundation.lazy.LazyColumn {
                    alarmCategories(
                        alarm = alarm, enabled = true, expanded = expanded, options = CategoryOptions(),
                        vibrator = vibratorForTest, onChange = { f -> alarm = f(alarm) },
                    )
                }
            }
        }
        assertTrue(alarm.snooze.enabled)
        compose.onNodeWithContentDescription("Allow snooze").performClick()
        compose.waitForIdle()
        assertEquals("the switch turns snooze off", false, alarm.snooze.enabled)
        // The collapsed Snooze card now says so. "Voice and light" and "Still up?" also read Off for a default alarm, so the
        // summary is looked for in the Snooze card's header, which is one merged node with its title.
        compose.onNode(hasText("Snooze") and hasText("Off")).assertIsDisplayed()
        compose.onNodeWithText("Snooze length").assertDoesNotExist() // and it stayed closed
    }
}

/** The collapsed content is either not composed or not visible; both mean "not shown". */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrHidden() {
    val exists = runCatching { assertExists() }.isSuccess
    if (exists) {
        val shown = runCatching { assertIsDisplayed() }.isSuccess
        assertTrue("collapsed content must not be displayed", !shown)
    }
}
