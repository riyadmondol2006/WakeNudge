package com.riyadm.wakenudge.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.ui.alarms.AlarmsScreen
import com.riyadm.wakenudge.ui.backup.BackupScreen
import com.riyadm.wakenudge.ui.clock.WorldClockScreen
import com.riyadm.wakenudge.ui.edit.AlarmEditScreen
import com.riyadm.wakenudge.ui.edit.missionSlots
import com.riyadm.wakenudge.ui.health.HealthBadge
import com.riyadm.wakenudge.ui.health.HealthBanner
import com.riyadm.wakenudge.ui.health.HealthScreen
import com.riyadm.wakenudge.ui.morning.MorningScreen
import com.riyadm.wakenudge.ui.nav.AboutRoute
import com.riyadm.wakenudge.ui.nav.AlarmEditRoute
import com.riyadm.wakenudge.ui.nav.AlarmsRoute
import com.riyadm.wakenudge.ui.nav.AppearanceSettingsRoute
import com.riyadm.wakenudge.ui.nav.AudioSettingsRoute
import com.riyadm.wakenudge.ui.nav.BackupRoute
import com.riyadm.wakenudge.ui.nav.DefaultsRoute
import com.riyadm.wakenudge.ui.nav.ExceptionsRoute
import com.riyadm.wakenudge.ui.nav.GroupsRoute
import com.riyadm.wakenudge.ui.nav.HealthRoute
import com.riyadm.wakenudge.ui.nav.MorningRoute
import com.riyadm.wakenudge.ui.nav.RingingSettingsRoute
import com.riyadm.wakenudge.ui.nav.SettingsRoute
import com.riyadm.wakenudge.ui.nav.SleepRoute
import com.riyadm.wakenudge.ui.nav.StopwatchRoute
import com.riyadm.wakenudge.ui.nav.TimerRoute
import com.riyadm.wakenudge.ui.nav.TimerSettingsRoute
import com.riyadm.wakenudge.ui.nav.WorldClockRoute
import com.riyadm.wakenudge.ui.onboarding.OnboardingScreen
import com.riyadm.wakenudge.ui.settings.AboutScreen
import com.riyadm.wakenudge.ui.settings.AppearanceSettingsScreen
import com.riyadm.wakenudge.ui.settings.AudioSettingsScreen
import com.riyadm.wakenudge.ui.settings.DefaultsScreen
import com.riyadm.wakenudge.ui.settings.ExceptionsScreen
import com.riyadm.wakenudge.ui.settings.GroupsScreen
import com.riyadm.wakenudge.ui.settings.RingingSettingsScreen
import com.riyadm.wakenudge.ui.settings.SettingsNav
import com.riyadm.wakenudge.ui.settings.SettingsScreen
import com.riyadm.wakenudge.ui.settings.TimerSettingsScreen
import com.riyadm.wakenudge.ui.sleep.SleepScreen
import com.riyadm.wakenudge.ui.stopwatch.StopwatchScreen
import com.riyadm.wakenudge.ui.theme.WakeMotion
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import com.riyadm.wakenudge.ui.timer.TimerScreen
import com.riyadm.wakenudge.ui.update.UpdatePromptHost
import kotlin.reflect.KClass

private enum class TopLevel(val route: Any, val routeClass: KClass<*>, val label: Int, val icon: ImageVector) {
    Alarms(AlarmsRoute, AlarmsRoute::class, R.string.nav_alarms, Icons.Outlined.Alarm),
    Clock(WorldClockRoute, WorldClockRoute::class, R.string.nav_clock, Icons.Outlined.Public),
    Timer(TimerRoute, TimerRoute::class, R.string.nav_timer, Icons.Outlined.HourglassBottom),
    Stopwatch(StopwatchRoute, StopwatchRoute::class, R.string.nav_stopwatch, Icons.Outlined.Timer),
    Morning(MorningRoute, MorningRoute::class, R.string.nav_morning, Icons.Outlined.WbSunny),
}

@Composable
fun WakeNudgeRoot(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    WakeNudgeTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor, haptics = s.haptics) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!s.onboardingDone) {
                OnboardingScreen(onFinish = viewModel::finishOnboarding)
            } else {
                MainNav(viewModel)
                // After onboarding only; empty in the Play build. The alarm screen is a separate activity and never sees it.
                UpdatePromptHost()
            }
        }
    }
}

@Composable
private fun MainNav(viewModel: MainViewModel) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val resources = LocalResources.current
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val current = TopLevel.entries.firstOrNull { tab -> destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true }

    LaunchedEffect(viewModel, nav) {
        viewModel.commands.collect { route ->
            // Single-top would hand a new "add alarm" request to an editor that is already open for another alarm
            // (same destination, different arguments), and saving it would change that alarm instead. Editors stack.
            nav.navigate(route) { launchSingleTop = route !is AlarmEditRoute }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.toasts.collect { Toast.makeText(context, resources.getString(it), Toast.LENGTH_SHORT).show() }
    }

    NavigationSuiteScaffold(
        layoutType = if (current == null) NavigationSuiteType.None
        else NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo()),
        navigationSuiteItems = {
            TopLevel.entries.forEach { tab ->
                item(
                    selected = tab == current,
                    onClick = {
                        nav.navigate(tab.route) {
                            popUpTo(AlarmsRoute) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { androidx.compose.material3.Icon(tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                )
            }
        },
    ) {
        AppNavHost(nav)
    }
}

private val tabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { fadeIn(WakeMotion.defaultEffects()) + scaleIn(WakeMotion.defaultSpatial(), initialScale = 0.96f) }
private val tabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { fadeOut(WakeMotion.fastEffects()) }

@Composable
private fun AppNavHost(nav: NavHostController) {
    NavHost(
        navController = nav,
        startDestination = AlarmsRoute,
        enterTransition = {
            slideInHorizontally(WakeMotion.defaultSpatial()) { it / 5 } + fadeIn(WakeMotion.defaultEffects())
        },
        exitTransition = { fadeOut(WakeMotion.fastEffects()) + scaleOut(WakeMotion.defaultSpatial(), targetScale = 0.96f) },
        popEnterTransition = { fadeIn(WakeMotion.defaultEffects()) + scaleIn(WakeMotion.defaultSpatial(), initialScale = 0.96f) },
        popExitTransition = {
            slideOutHorizontally(WakeMotion.defaultSpatial()) { it / 5 } + fadeOut(WakeMotion.fastEffects())
        },
    ) {
        composable<AlarmsRoute>(enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) {
            AlarmsScreen(
                onEditAlarm = onceResumed<Long> { nav.navigate(AlarmEditRoute(it)) },
                onOpenSleep = dropUnlessResumed { nav.navigate(SleepRoute) },
                onOpenHealth = dropUnlessResumed { nav.navigate(HealthRoute) },
                onOpenSettings = dropUnlessResumed { nav.navigate(SettingsRoute) },
                onOpenGroups = dropUnlessResumed { nav.navigate(GroupsRoute) },
                healthBadge = { HealthBadge() },
                healthBanner = { HealthBanner() },
            )
        }
        composable<WorldClockRoute>(enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) { WorldClockScreen() }
        composable<TimerRoute>(enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) { TimerScreen() }
        composable<StopwatchRoute>(enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) { StopwatchScreen() }
        composable<MorningRoute>(enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) { MorningScreen() }
        composable<AlarmEditRoute> { entry ->
            // Closes this editor only while it is still the one on top: a second call (double tap) finds another screen
            // there and does nothing. Not "only while resumed": after a save the editor must close even if a ringing
            // alarm or a system dialog has just covered it, or Save would look dead.
            AlarmEditScreen(onClose = { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack() }, missionSlots = missionSlots())
        }
        composable<SleepRoute> {
            SleepScreen(
                onBack = dropUnlessResumed { nav.popBackStack() },
                onCreateAlarm = onceResumed2 { h, m -> nav.navigate(AlarmEditRoute(alarmId = 0, hour = h, minute = m)) },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                SettingsNav(
                    back = dropUnlessResumed { nav.popBackStack() },
                    defaults = dropUnlessResumed { nav.navigate(DefaultsRoute) },
                    groups = dropUnlessResumed { nav.navigate(GroupsRoute) },
                    exceptions = dropUnlessResumed { nav.navigate(ExceptionsRoute) },
                    sleep = dropUnlessResumed { nav.navigate(SleepRoute) },
                    health = dropUnlessResumed { nav.navigate(HealthRoute) },
                    backup = dropUnlessResumed { nav.navigate(BackupRoute) },
                    about = dropUnlessResumed { nav.navigate(AboutRoute) },
                    appearance = dropUnlessResumed { nav.navigate(AppearanceSettingsRoute) },
                    ringing = dropUnlessResumed { nav.navigate(RingingSettingsRoute) },
                    audio = dropUnlessResumed { nav.navigate(AudioSettingsRoute) },
                    timers = dropUnlessResumed { nav.navigate(TimerSettingsRoute) },
                ),
            )
        }
        composable<DefaultsRoute> { DefaultsScreen(onBack = dropUnlessResumed { nav.popBackStack() }) }
        composable<GroupsRoute> { GroupsScreen(onBack = dropUnlessResumed { nav.popBackStack() }) }
        composable<ExceptionsRoute> { ExceptionsScreen(onBack = dropUnlessResumed { nav.popBackStack() }) }
        composable<AboutRoute> { AboutScreen(onBack = dropUnlessResumed { nav.popBackStack() }) }
        composable<HealthRoute> { HealthScreen(onBack = dropUnlessResumed { nav.popBackStack() }) }
        composable<BackupRoute> { BackupScreen(onBack = dropUnlessResumed { nav.popBackStack() }) }
        composable<AppearanceSettingsRoute> { AppearanceSettingsScreen(onBack = dropUnlessResumed { nav.popBackStack() }) }
        composable<RingingSettingsRoute> { RingingSettingsScreen(onBack = dropUnlessResumed { nav.popBackStack() }) }
        composable<AudioSettingsRoute> { AudioSettingsScreen(onBack = dropUnlessResumed { nav.popBackStack() }) }
        composable<TimerSettingsRoute> { TimerSettingsScreen(onBack = dropUnlessResumed { nav.popBackStack() }) }
    }
}

/**
 * Runs a navigation only while this screen is the resumed one. The first tap starts the transition and the screen
 * stops being resumed, so a double tap cannot open two editors, save twice or pop the last screen off the stack.
 */
@Composable
private fun <T> onceResumed(action: (T) -> Unit): (T) -> Unit {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(action)
    return remember(owner) { { value -> if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) current(value) } }
}

@Composable
private fun onceResumed2(action: (Int, Int) -> Unit): (Int, Int) -> Unit {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(action)
    return remember(owner) { { a, b -> if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) current(a, b) } }
}
