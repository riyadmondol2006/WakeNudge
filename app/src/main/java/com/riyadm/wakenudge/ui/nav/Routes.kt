package com.riyadm.wakenudge.ui.nav

import kotlinx.serialization.Serializable

/** Top-level tabs. */
@Serializable data object AlarmsRoute
@Serializable data object WorldClockRoute
@Serializable data object TimerRoute
@Serializable data object StopwatchRoute
@Serializable data object MorningRoute

/** [alarmId] 0 creates a new alarm; hour/minute/label/daysMask prefill it (set-alarm intents, the sleep planner). */
@Serializable
data class AlarmEditRoute(
    val alarmId: Long = 0,
    val hour: Int = -1,
    val minute: Int = -1,
    val label: String = "",
    val daysMask: Int = -1,
)

@Serializable data object SleepRoute
@Serializable data object HealthRoute
@Serializable data object SettingsRoute
@Serializable data object DefaultsRoute
@Serializable data object GroupsRoute
@Serializable data object ExceptionsRoute
@Serializable data object BackupRoute
@Serializable data object AboutRoute
@Serializable data object AppearanceSettingsRoute
@Serializable data object RingingSettingsRoute
@Serializable data object AudioSettingsRoute
@Serializable data object TimerSettingsRoute
