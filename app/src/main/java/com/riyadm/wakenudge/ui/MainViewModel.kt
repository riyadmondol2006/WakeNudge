package com.riyadm.wakenudge.ui

import android.content.Intent
import android.util.Log
import android.provider.AlarmClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.MainActivity
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.usecase.AlarmActions
import com.riyadm.wakenudge.engine.TimerController
import com.riyadm.wakenudge.ui.nav.AlarmEditRoute
import com.riyadm.wakenudge.ui.nav.AlarmsRoute
import com.riyadm.wakenudge.ui.nav.SleepRoute
import com.riyadm.wakenudge.ui.nav.TimerRoute
import com.riyadm.wakenudge.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val ACTION_QS_TILE_PREFERENCES = "android.service.quicksettings.action.QS_TILE_PREFERENCES"

/** Hosts app-wide state (settings for theming) and turns external intents (assistant, shortcuts) into navigation. */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val alarmActions: AlarmActions,
    private val timers: TimerController,
    private val updates: UpdateChecker,
) : ViewModel() {

    /** Null until the first read finishes, so the splash screen can wait instead of flashing default theming. */
    val settings: StateFlow<AppSettings?> = settingsRepo.settings.map<AppSettings, AppSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _commands = Channel<Any>(Channel.BUFFERED)

    /** Destinations (route objects) requested from outside the UI. */
    val commands = _commands.receiveAsFlow()

    private val _toasts = Channel<Int>(Channel.BUFFERED)
    val toasts = _toasts.receiveAsFlow()

    /** The main screen came to the foreground: let the update checker see whether a check is due (Play: does nothing). */
    fun onForeground() {
        viewModelScope.launch { updates.checkIfDue() }
    }

    /**
     * The check waits for onboarding, and finishing it does not bring the screen to the foreground again, so look now:
     * someone who installed an old APK hears about the new one straight away, not on their next visit.
     */
    fun finishOnboarding() = viewModelScope.launch {
        settingsRepo.update { it.copy(onboardingDone = true) }
        updates.checkIfDue()
    }

    fun handleIntent(intent: Intent?) {
        intent ?: return
        viewModelScope.launch {
            // MainActivity is exported, so any app can hand it any Bundle. Reading a malformed one (for example an
            // unparcelable extra) throws, and that must never take the app down: such an intent is simply ignored.
            try {
                dispatch(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Ignored a malformed intent", t)
            }
        }
    }

    private suspend fun dispatch(intent: Intent) {
        when (intent.action) {
            MainActivity.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SHOW_ALARMS -> _commands.send(AlarmsRoute)
            MainActivity.ACTION_SHOW_TIMERS, AlarmClock.ACTION_SHOW_TIMERS -> _commands.send(TimerRoute)
            MainActivity.ACTION_SHOW_SLEEP -> _commands.send(SleepRoute)
            // A long-press on the Quick Settings tile lands on the alarm list.
            ACTION_QS_TILE_PREFERENCES -> _commands.send(AlarmsRoute)
            AlarmClock.ACTION_SET_ALARM -> setAlarmFromIntent(intent)
            AlarmClock.ACTION_SET_TIMER -> setTimerFromIntent(intent)
        }
    }

    private suspend fun setAlarmFromIntent(intent: Intent) {
        if (!intent.hasExtra(AlarmClock.EXTRA_HOUR)) {
            // "Add alarm" from the list widget (and any bare SET_ALARM) opens the empty editor.
            _commands.send(AlarmEditRoute(alarmId = 0))
            return
        }
        val hour = intent.getIntExtra(AlarmClock.EXTRA_HOUR, 7).coerceIn(0, 23)
        val minute = intent.getIntExtra(AlarmClock.EXTRA_MINUTES, 0).coerceIn(0, 59)
        val label = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty().take(60)
        val days = intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS).orEmpty()
            .filter { it in 1..7 }
            // java.util.Calendar numbers days Sunday=1…Saturday=7.
            .map { DayOfWeek.of(((it + 5) % 7) + 1) }
        val mask = Alarm.maskOf(days)
        if (intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) {
            val defaults = settingsRepo.current().defaults
            alarmActions.save(
                Alarm(
                    hour = hour, minute = minute, label = label, repeatDaysMask = mask,
                    sound = defaults.sound, volume = defaults.volume, vibration = defaults.vibration,
                    snooze = defaults.snooze, antiOversleep = defaults.antiOversleep,
                ),
            )
            _commands.send(AlarmsRoute)
        } else {
            _commands.send(AlarmEditRoute(alarmId = 0, hour = hour, minute = minute, label = label, daysMask = mask))
        }
    }

    private suspend fun setTimerFromIntent(intent: Intent) {
        val seconds = intent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0)
        if (seconds in 1..86_400) {
            timers.create(seconds * 1000L, intent.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty().take(40), startNow = true)
        }
        _commands.send(TimerRoute)
    }
}

private const val TAG = "MainViewModel"
