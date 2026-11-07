package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.domain.model.withGlobalsOf
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Volume keys and keeping the ring screen in front used to be one setting for all alarms. Once, after the update that
 * made them per alarm, the choices the user had made are copied onto every alarm and onto the defaults for new ones.
 * Nothing rings differently because of it. If it fails it is tried again at the next start.
 */
@Singleton
class PerAlarmSettingsCopy @Inject constructor(
    private val alarms: AlarmRepository,
    private val settings: SettingsRepository,
    private val runtime: RuntimeState,
    private val diagnostics: DiagnosticsLog,
) {
    /** Returns whether the copy ran this time. Never throws: this runs at the process start an alarm may have caused. */
    suspend fun runOnce(): Boolean {
        return try {
            if (runtime.perAlarmSettingsCopied) return false
            val current = settings.current()
            for (id in alarms.getAlarms().map { it.id }) {
                // Read again right before writing: the alarm that woke this process may be switching itself off meanwhile.
                val alarm = alarms.getAlarm(id) ?: continue
                val copied = alarm.antiOversleep.withGlobalsOf(current)
                if (copied != alarm.antiOversleep) alarms.save(alarm.copy(antiOversleep = copied))
            }
            settings.update { it.copy(defaults = it.defaults.copy(antiOversleep = it.defaults.antiOversleep.withGlobalsOf(it))) }
            runtime.perAlarmSettingsCopied = true
            true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            diagnostics.error("Migration", "copying the global ring settings onto each alarm failed; trying again next start", t)
            false
        }
    }
}
