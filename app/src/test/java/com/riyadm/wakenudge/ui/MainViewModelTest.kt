package com.riyadm.wakenudge.ui

import com.riyadm.wakenudge.domain.model.AppSettings
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

    private fun viewModel() = MainViewModel(settings, mockk(relaxed = true), mockk(relaxed = true), updates)

    @Test fun `finishing onboarding looks for an update once setup is marked done`() {
        viewModel().finishOnboarding()
        assertEquals(listOf(true), checks)
    }

    @Test fun `coming to the foreground looks for an update`() {
        viewModel().onForeground()
        assertTrue(checks.isNotEmpty())
    }
}
