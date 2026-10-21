package com.riyadm.wakenudge.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
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

/** Choosing a sound plays it at once, and the alarm options group into cards that summarise themselves. */
@RunWith(AndroidJUnit4::class)
class CategoriesAndPickerUiTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val previewer = SoundPreviewer(ApplicationProvider.getApplicationContext())

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
                    icon = AlarmCategory.SOUND.icon, title = "Sound and light", summary = "Sunrise · Pulse",
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
            antiOversleep = AntiOversleepSettings(holdToDismissSeconds = 3, backupEnabled = true, backupDelayMinutes = 12),
        )
        val bare = Alarm(snooze = SnoozeSettings(enabled = false), volume = VolumeSettings(extraLoud = false))
        val summaries = mutableMapOf<AlarmCategory, String>()
        val plain = mutableMapOf<AlarmCategory, String>()
        compose.setContent {
            WakeNudgeTheme {
                AlarmCategory.entries.forEach {
                    summaries[it] = categorySummary(it, alarm, perAlarm = true)
                    plain[it] = categorySummary(it, bare, perAlarm = true)
                }
            }
        }
        compose.waitForIdle()
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.WAKE_UP).startsWith("2 missions"))
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.VOLUME).contains("70%"))
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.VOLUME).contains("Extra loud 60%"))
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.SNOOZE).contains("7 min"))
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.SNOOZE).contains("up to 2 times"))
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.PROTECTION).contains("Backup alarm after 12 min"))
        assertTrue(summaries.toString(), summaries.getValue(AlarmCategory.PROTECTION).contains("Hold 3 s to dismiss"))

        // A switched-off snooze and an alarm with no missions say so plainly.
        assertEquals("Off", plain.getValue(AlarmCategory.SNOOZE))
        assertEquals("No missions", plain.getValue(AlarmCategory.WAKE_UP))
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
