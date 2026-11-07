package com.riyadm.wakenudge.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.HiltTestActivity
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.Hardness
import com.riyadm.wakenudge.domain.model.HardnessPresets
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.usecase.AlarmActions
import com.riyadm.wakenudge.engine.RingSessionStore
import com.riyadm.wakenudge.ui.edit.AlarmEditScreen
import com.riyadm.wakenudge.ui.edit.missionSlots
import com.riyadm.wakenudge.ui.nav.AlarmEditRoute
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real editor with its real view model: each level saves as that level, and an old alarm saved untouched stays as it was. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LevelsEditorUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var alarms: AlarmRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var ring: RingSessionStore
    @Inject lateinit var actions: AlarmActions

    private var session by mutableIntStateOf(0)
    private var editing by mutableLongStateOf(0L)

    @Before fun setUp() {
        hilt.inject()
        runBlocking { settings.update { it.copy(onboardingDone = true, noMercyConfirmed = false) } }
        compose.setContent {
            WakeNudgeTheme {
                // A new navigation host for each session, so each gets a fresh editor and view model.
                key(session) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = AlarmEditRoute(alarmId = editing)) {
                        composable<AlarmEditRoute> { AlarmEditScreen(onClose = {}, missionSlots = missionSlots()) }
                    }
                }
            }
        }
    }

    /** Through the real delete, so no alarm saved here is left registered with Android to ring on the test phone. */
    @After fun tearDown() {
        ring.set(null)
        runBlocking { for (a in alarms.getAlarms()) actions.delete(a.id, overrideLock = true) }
    }

    private fun open(id: Long) {
        editing = id
        session++
        compose.waitUntil(5_000) { compose.onAllNodesWithText("How hard?").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))

    /** An hour ago, so tomorrow's ring is far off and no lock window applies while the test edits it. */
    private fun anHourAgo() = LocalTime.now().minusHours(1)

    @Test fun eachLevelSavesAsThatLevel() {
        for ((name, level) in listOf("Easy" to Hardness.EASY, "Normal" to Hardness.NORMAL, "Hard" to Hardness.HARD, "No mercy" to Hardness.NO_MERCY)) {
            val before = runBlocking { alarms.getAlarms().map { it.id }.toSet() }
            open(0)
            scrollTo(name)
            compose.onNodeWithText(name).performClick()
            if (level == Hardness.NO_MERCY) {
                // The first No mercy says what it means; nothing else asks.
                compose.onNodeWithText("Use No mercy").performClick()
            }
            compose.waitForIdle()
            compose.onNodeWithText(name).assertIsSelected()
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(5_000) { runBlocking { alarms.getAlarms().any { it.id !in before } } }
            val saved = runBlocking { alarms.getAlarms().first { it.id !in before } }
            assertEquals(name, level, HardnessPresets.levelOf(saved))
        }
    }

    @Test fun aNewAlarmStartsAtNormalWithTheMixedRotation() {
        open(0)
        scrollTo("Normal")
        compose.onNodeWithText("Normal").assertIsSelected()
        scrollTo("Mixed, changes each morning")
    }

    @Test fun anOldAlarmOpenedAndSavedWithoutEditsIsUnchanged() {
        val at = anHourAgo()
        val old = Alarm(
            label = "old", hour = at.hour, minute = at.minute, repeatDaysMask = Alarm.WEEKDAYS_MASK,
            missions = listOf(MissionConfig(MissionType.MATH), MissionConfig(MissionType.TYPING)),
            snooze = SnoozeSettings(enabled = true, minutes = 7, maxCount = 0),
            volume = VolumeSettings(level = 0, rampSeconds = 45, extraLoud = true, extraLoudPercent = 60),
            antiOversleep = AntiOversleepSettings(ringTimeoutMinutes = 17, holdToDismissSeconds = 4, editLockMinutes = 30, emergencyStopEnabled = false, wayOutDelayMinutes = 3),
        )
        val id = runBlocking { alarms.save(old) }
        val stored = runBlocking { alarms.getAlarm(id)!! }
        open(id)
        scrollTo("Custom")
        compose.onNodeWithText("Custom").assertIsSelected()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        Thread.sleep(500)
        val after = runBlocking { alarms.getAlarm(id)!! }
        assertEquals(stored.copy(updatedAt = 0, createdAt = 0), after.copy(updatedAt = 0, createdAt = 0))
    }

    @Test fun anOldAlarmThatMatchesALevelShowsThatLevel() {
        val at = anHourAgo()
        val id = runBlocking { alarms.save(HardnessPresets.apply(Hardness.HARD, Alarm(hour = at.hour, minute = at.minute))) }
        open(id)
        scrollTo("Hard")
        compose.onNodeWithText("Hard").assertIsSelected()
    }
}
