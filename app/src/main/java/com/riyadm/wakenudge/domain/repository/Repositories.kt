package com.riyadm.wakenudge.domain.repository

import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.ExceptionDate
import com.riyadm.wakenudge.domain.model.WakeCheck
import com.riyadm.wakenudge.domain.model.WorldClockCity
import kotlinx.coroutines.flow.Flow

interface AlarmRepository {
    fun observeAlarms(): Flow<List<Alarm>>
    fun observeAlarm(id: Long): Flow<Alarm?>
    suspend fun getAlarms(): List<Alarm>
    suspend fun getAlarm(id: Long): Alarm?

    /** Inserts (id == 0) or updates; stamps created/updated times and returns the row id. */
    suspend fun save(alarm: Alarm): Long
    suspend fun delete(id: Long)
    suspend fun setEnabled(id: Long, enabled: Boolean)
    suspend fun setSkipDay(id: Long, epochDay: Long?)
    suspend fun replaceAll(alarms: List<Alarm>)
}

interface GroupRepository {
    fun observeGroups(): Flow<List<AlarmGroup>>
    suspend fun getGroups(): List<AlarmGroup>
    suspend fun save(group: AlarmGroup): Long
    suspend fun delete(id: Long)
    suspend fun setEnabled(id: Long, enabled: Boolean)
    suspend fun replaceAll(groups: List<AlarmGroup>)
}

interface HistoryRepository {
    suspend fun start(event: AlarmEvent): Long
    suspend fun update(event: AlarmEvent)
    suspend fun get(id: Long): AlarmEvent?
    suspend fun findOccurrence(alarmId: Long, scheduledAt: Long): AlarmEvent?
    suspend fun openEvents(): List<AlarmEvent>

    /** Unresolved events that still have a snooze or backup alarm waiting to fire. */
    suspend fun withFollowUps(): List<AlarmEvent>
    suspend fun recent(limit: Int): List<AlarmEvent>
    fun observeRecent(limit: Int): Flow<List<AlarmEvent>>
    fun observeSince(sinceMillis: Long): Flow<List<AlarmEvent>>
    suspend fun deleteOlderThan(beforeMillis: Long)
    suspend fun clear()
    suspend fun replaceAll(events: List<AlarmEvent>)
}

interface TimerRepository {
    fun observeTimers(): Flow<List<CountdownTimer>>
    suspend fun getTimers(): List<CountdownTimer>
    suspend fun getTimer(id: Long): CountdownTimer?
    suspend fun save(timer: CountdownTimer): Long
    suspend fun delete(id: Long)
}

interface WorldClockRepository {
    fun observeCities(): Flow<List<WorldClockCity>>
    suspend fun getCities(): List<WorldClockCity>
    suspend fun add(city: WorldClockCity)
    suspend fun remove(zoneId: String)
    suspend fun replaceAll(cities: List<WorldClockCity>)
}

interface ExceptionRepository {
    fun observeDates(): Flow<List<ExceptionDate>>
    suspend fun getDates(): List<ExceptionDate>
    suspend fun add(date: ExceptionDate)
    suspend fun remove(epochDay: Long)
    suspend fun pruneBefore(epochDay: Long)
    suspend fun replaceAll(dates: List<ExceptionDate>)
}

interface WakeCheckRepository {
    suspend fun getAll(): List<WakeCheck>
    fun observeAll(): Flow<List<WakeCheck>>
    suspend fun get(alarmId: Long): WakeCheck?
    suspend fun save(check: WakeCheck)
    suspend fun delete(alarmId: Long)
    suspend fun clear()
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
    suspend fun replace(settings: AppSettings)
}

data class DiagnosticRecord(val timestamp: Long, val level: String, val tag: String, val message: String)

/** Local-only ring buffer of engine events that powers the Alarm Health diagnostics. */
interface DiagnosticsLog {
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String)
    fun error(tag: String, message: String, error: Throwable? = null)
    fun observeRecent(limit: Int = 200): Flow<List<DiagnosticRecord>>
    suspend fun recent(limit: Int = 200): List<DiagnosticRecord>
    suspend fun clear()
}
