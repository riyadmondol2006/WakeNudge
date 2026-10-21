package com.riyadm.wakenudge.engine

import android.content.Context
import android.content.SharedPreferences
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.repository.TimerRepository
import io.mockk.every
import io.mockk.mockk
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** A clock the test moves by hand. */
class TestClock(var now: Long = 1_800_000_000_000L) : TimeSource {
    override fun nowMillis(): Long = now
    override fun zone(): ZoneId = ZoneId.of("UTC")
}

/** SharedPreferences held in a map, so [RuntimeState] can be the real class in JVM tests. */
class MapPrefs : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removed.forEach(values::remove)
            values.putAll(pending)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

fun realRuntime(prefs: SharedPreferences = MapPrefs()): RuntimeState {
    val context = mockk<Context>()
    every { context.getSharedPreferences(any(), any()) } returns prefs
    return RuntimeState(context)
}

class MemoryAlarms : AlarmRepository {
    val rows = LinkedHashMap<Long, Alarm>()
    var failReads: Throwable? = null
    private var nextId = 1L

    fun add(alarm: Alarm): Alarm = alarm.copy(id = if (alarm.id == 0L) nextId++ else alarm.id).also { rows[it.id] = it }

    override fun observeAlarms(): Flow<List<Alarm>> = flowOf(rows.values.toList())
    override fun observeAlarm(id: Long): Flow<Alarm?> = flowOf(rows[id])
    override suspend fun getAlarms(): List<Alarm> = failReads?.let { throw it } ?: rows.values.toList()
    override suspend fun getAlarm(id: Long): Alarm? = failReads?.let { throw it } ?: rows[id]
    override suspend fun save(alarm: Alarm): Long = add(alarm).id
    override suspend fun delete(id: Long) { rows.remove(id) }
    override suspend fun setEnabled(id: Long, enabled: Boolean) { rows[id]?.let { rows[id] = it.copy(enabled = enabled) } }
    override suspend fun setSkipDay(id: Long, epochDay: Long?) { rows[id]?.let { rows[id] = it.copy(skipEpochDay = epochDay) } }
    override suspend fun replaceAll(alarms: List<Alarm>) { rows.clear(); alarms.forEach { rows[it.id] = it } }
}

/** A history that can be made to fail on every call, like a full disk or a locked database. */
class MemoryHistory : HistoryRepository {
    val rows = LinkedHashMap<Long, AlarmEvent>()
    var failure: Throwable? = null
    private var nextId = 1L
    private fun check() { failure?.let { throw it } }

    override suspend fun start(event: AlarmEvent): Long { check(); val id = nextId++; rows[id] = event.copy(id = id); return id }
    override suspend fun update(event: AlarmEvent) { check(); rows[event.id] = event }
    override suspend fun get(id: Long): AlarmEvent? { check(); return rows[id] }
    override suspend fun findOccurrence(alarmId: Long, scheduledAt: Long): AlarmEvent? {
        check()
        return rows.values.firstOrNull { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
    }

    override suspend fun openEvents(): List<AlarmEvent> { check(); return rows.values.filter { it.outcome == AlarmOutcome.RINGING } }
    override suspend fun withFollowUps(): List<AlarmEvent> { check(); return rows.values.filter { it.snoozeUntil > 0 || it.pendingBackups.isNotEmpty() } }
    override suspend fun recent(limit: Int): List<AlarmEvent> { check(); return rows.values.sortedByDescending { it.id }.take(limit) }
    override fun observeRecent(limit: Int): Flow<List<AlarmEvent>> = flowOf(rows.values.toList())
    override fun observeSince(sinceMillis: Long): Flow<List<AlarmEvent>> = flowOf(rows.values.toList())
    override suspend fun deleteOlderThan(beforeMillis: Long) = Unit
    override suspend fun clear() { rows.clear() }
    override suspend fun replaceAll(events: List<AlarmEvent>) { rows.clear(); events.forEach { rows[it.id] = it } }
}

class MemoryTimers : TimerRepository {
    val rows = LinkedHashMap<Long, CountdownTimer>()
    var failure: Throwable? = null
    private fun check() { failure?.let { throw it } }

    override fun observeTimers(): Flow<List<CountdownTimer>> = flowOf(rows.values.toList())
    override suspend fun getTimers(): List<CountdownTimer> { check(); return rows.values.toList() }
    override suspend fun getTimer(id: Long): CountdownTimer? { check(); return rows[id] }
    override suspend fun save(timer: CountdownTimer): Long { check(); val id = if (timer.id == 0L) (rows.keys.maxOrNull() ?: 0) + 1 else timer.id; rows[id] = timer.copy(id = id); return id }
    override suspend fun delete(id: Long) { check(); rows.remove(id) }
}

class MemorySettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    var failure: Throwable? = null

    override val settings: Flow<AppSettings> get() = state
    override suspend fun current(): AppSettings = failure?.let { throw it } ?: state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) { failure?.let { throw it }; state.value = transform(state.value) }
    override suspend fun replace(settings: AppSettings) { failure?.let { throw it }; state.value = settings }
}
