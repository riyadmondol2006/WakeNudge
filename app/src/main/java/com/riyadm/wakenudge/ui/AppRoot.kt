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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.riyadm.wakenudge.ui.edit.AlarmEditScreen
import com.riyadm.wakenudge.ui.edit.missionSlots
import com.riyadm.wakenudge.ui.backup.BackupScreen
import com.riyadm.wakenudge.ui.clock.WorldClockScreen
import com.riyadm.wakenudge.ui.health.HealthBadge
import com.riyadm.wakenudge.ui.health.HealthScreen
import com.riyadm.wakenudge.ui.morning.MorningScreen
import com.riyadm.wakenudge.ui.onboarding.OnboardingScreen
import com.riyadm.wakenudge.ui.settings.AboutScreen
import com.riyadm.wakenudge.ui.settings.DefaultsScreen
import com.riyadm.wakenudge.ui.settings.ExceptionsScreen
import com.riyadm.wakenudge.ui.settings.GroupsScreen
import com.riyadm.wakenudge.ui.settings.SettingsNav
import com.riyadm.wakenudge.ui.settings.SettingsScreen
import com.riyadm.wakenudge.ui.sleep.SleepScreen
import com.riyadm.wakenudge.ui.stopwatch.StopwatchScreen
import com.riyadm.wakenudge.ui.timer.TimerScreen
import com.riyadm.wakenudge.ui.nav.AboutRoute
import com.riyadm.wakenudge.ui.nav.AlarmEditRoute
import com.riyadm.wakenudge.ui.nav.BackupRoute
import com.riyadm.wakenudge.ui.nav.DefaultsRoute
import com.riyadm.wakenudge.ui.nav.ExceptionsRoute
import com.riyadm.wakenudge.ui.nav.GroupsRoute
import com.riyadm.wakenudge.ui.nav.HealthRoute
import com.riyadm.wakenudge.ui.nav.SettingsRoute
import com.riyadm.wakenudge.ui.nav.SleepRoute
import com.riyadm.wakenudge.ui.nav.AlarmsRoute
import com.riyadm.wakenudge.ui.nav.MorningRoute
import com.riyadm.wakenudge.ui.nav.StopwatchRoute
import com.riyadm.wakenudge.ui.nav.TimerRoute
import com.riyadm.wakenudge.ui.nav.WorldClockRoute
import com.riyadm.wakenudge.ui.theme.WakeMotion
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
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
            if (!s.onboardingDone) OnboardingScreen(onFinish = viewModel::finishOnboarding) else MainNav(viewModel)
        }
    }
}

@Composable
private fun MainNav(viewModel: MainViewModel) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val current = TopLevel.entries.firstOrNull { tab -> destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true }

    LaunchedEffect(viewModel, nav) {
        viewModel.commands.collect { route ->
            nav.navigate(route) { launchSingleTop = true }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.toasts.collect { Toast.makeText(context, context.getString(it), Toast.LENGTH_SHORT).show() }
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
                onEditAlarm = { nav.navigate(AlarmEditRoute(it)) },
                onOpenSleep = { nav.navigate(SleepRoute) },
                onOpenHealth = { nav.navigate(HealthRoute) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onOpenGroups = { nav.navigate(GroupsRoute) },
                healthBadge = { HealthBadge() },
            )
        }
        composable<WorldClockRoute>(enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) { WorldClockScreen() }
        composable<TimerRoute>(enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) { TimerScreen() }
        composable<StopwatchRoute>(enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) { StopwatchScreen() }
        composable<MorningRoute>(enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) { MorningScreen() }
        composable<AlarmEditRoute> {
            AlarmEditScreen(onClose = { nav.popBackStack() }, missionSlots = missionSlots())
        }
        composable<SleepRoute> {
            SleepScreen(
                onBack = { nav.popBackStack() },
                onCreateAlarm = { h, m -> nav.navigate(AlarmEditRoute(alarmId = 0, hour = h, minute = m)) },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                SettingsNav(
                    back = { nav.popBackStack() },
                    defaults = { nav.navigate(DefaultsRoute) },
                    groups = { nav.navigate(GroupsRoute) },
                    exceptions = { nav.navigate(ExceptionsRoute) },
                    sleep = { nav.navigate(SleepRoute) },
                    health = { nav.navigate(HealthRoute) },
                    backup = { nav.navigate(BackupRoute) },
                    about = { nav.navigate(AboutRoute) },
                ),
            )
        }
        composable<DefaultsRoute> { DefaultsScreen(onBack = { nav.popBackStack() }) }
        composable<GroupsRoute> { GroupsScreen(onBack = { nav.popBackStack() }) }
        composable<ExceptionsRoute> { ExceptionsScreen(onBack = { nav.popBackStack() }) }
        composable<AboutRoute> { AboutScreen(onBack = { nav.popBackStack() }) }
        composable<HealthRoute> { HealthScreen(onBack = { nav.popBackStack() }) }
        composable<BackupRoute> { BackupScreen(onBack = { nav.popBackStack() }) }
    }
}
