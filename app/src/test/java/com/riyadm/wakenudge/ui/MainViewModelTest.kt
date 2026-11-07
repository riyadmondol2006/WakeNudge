package com.riyadm.wakenudge.ui

import android.content.Intent
import android.provider.AlarmClock
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmDefaults
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.Hardness
import com.riyadm.wakenudge.domain.model.HardnessPresets
import com.riyadm.wakenudge.domain.usecase.ActionResult
import com.riyadm.wakenudge.domain.usecase.AlarmActions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.slot
import com.riyadm.wakenudge.engine.MemorySettings
import com.riyadm.wakenudge.update.UpdateChecker
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The update check waits for onboarding; finishing it must not leave a new install unaware of a newer release. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val settings = MemorySettings(AppSettings(onboardingDone = false))

    /** Records whether onboarding was already marked done when each check ran, as the real checker reads it. */
    private val checks = mutableListOf<Boolean>()
    private val updates = object : UpdateChecker {
        override suspend fun checkIfDue() { checks += settings.current().onboardingDone }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private val actions = mockk<AlarmActions>(relaxed = true)

    private fun viewModel() = MainViewModel(settings, actions, mockk(relaxed = true), updates)

    @Test fun `a new install does not get the card about levels, which is for people who had alarms before them`() {
        viewModel().finishOnboarding()
        assertTrue(kotlinx.coroutines.runBlocking { settings.current() }.levelsCardSeen)
    }

    @Test fun `an alarm set by voice without the editor is Easy, whatever the default level`() {
        kotlinx.coroutines.runBlocking { settings.replace(AppSettings(onboardingDone = true, defaults = AlarmDefaults(level = Hardness.NO_MERCY))) }
        val saved = slot<Alarm>()
        coEvery { actions.save(capture(saved), any()) } returns ActionResult.Done(1L)
        val intent = mockk<Intent>(relaxed = true) {
            every { action } returns AlarmClock.ACTION_SET_ALARM
            every { hasExtra(AlarmClock.EXTRA_HOUR) } returns true
            every { getIntExtra(AlarmClock.EXTRA_HOUR, any()) } returns 6
            every { getIntExtra(AlarmClock.EXTRA_MINUTES, any()) } returns 30
            every { getStringExtra(AlarmClock.EXTRA_MESSAGE) } returns "Gym"
            every { getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS) } returns null
            every { getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, any()) } returns true
        }
        viewModel().handleIntent(intent)
        assertEquals(Hardness.EASY, HardnessPresets.levelOf(saved.captured))
        assertEquals(6 to 30, saved.captured.hour to saved.captured.minute)
        assertEquals("Gym", saved.captured.label)
    }

    @Test fun `finishing onboarding looks for an update once setup is marked done`() {
        viewModel().finishOnboarding()
        assertEquals(listOf(true), checks)
    }

    @Test fun `coming to the foreground looks for an update`() {
        viewModel().onForeground()
        assertTrue(checks.isNotEmpty())
    }
}
