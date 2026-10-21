package com.riyadm.wakenudge.data.repo

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.data.db.AlarmDao
import com.riyadm.wakenudge.data.db.EventDao
import com.riyadm.wakenudge.data.db.ExceptionDao
import com.riyadm.wakenudge.data.db.GroupDao
import com.riyadm.wakenudge.data.db.TimerDao
import com.riyadm.wakenudge.data.db.WakeCheckDao
import com.riyadm.wakenudge.data.db.WorldClockDao
import com.riyadm.wakenudge.data.db.toDomain
import com.riyadm.wakenudge.data.db.toEntity
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.ExceptionDate
import com.riyadm.wakenudge.domain.model.WakeCheck
import com.riyadm.wakenudge.domain.model.WorldClockCity
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.repository.TimerRepository
import com.riyadm.wakenudge.domain.repository.WakeCheckRepository
import com.riyadm.wakenudge.domain.repository.WorldClockRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class AlarmRepositoryImpl @Inject constructor(
    private val dao: AlarmDao,
    private val time: TimeSource,
) : AlarmRepository {
    override fun observeAlarms(): Flow<List<Alarm>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override fun observeAlarm(id: Long): Flow<Alarm?> = dao.observe(id).map { it?.toDomain() }
    override suspend fun getAlarms(): List<Alarm> = dao.getAll().map { it.toDomain() }
    override suspend fun getAlarm(id: Long): Alarm? = dao.get(id)?.toDomain()

    override suspend fun save(alarm: Alarm): Long {
        val now = time.nowMillis()
        val stamped = alarm.copy(
            createdAt = if (alarm.createdAt == 0L) now else alarm.createdAt,
            updatedAt = now,
        )
        return dao.upsert(stamped.toEntity())
    }

    override suspend fun delete(id: Long) = dao.delete(id)
    override suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled, time.nowMillis())
    override suspend fun setSkipDay(id: Long, epochDay: Long?) = dao.setSkipDay(id, epochDay, time.nowMillis())
    override suspend fun replaceAll(alarms: List<Alarm>) = dao.replaceAll(alarms.map { it.toEntity() })
}

@Singleton
class GroupRepositoryImpl @Inject constructor(
    private val dao: GroupDao,
    private val alarmDao: AlarmDao,
) : GroupRepository {
    override fun observeGroups(): Flow<List<AlarmGroup>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun getGroups(): List<AlarmGroup> = dao.getAll().map { it.toDomain() }
    override suspend fun save(group: AlarmGroup): Long = dao.upsert(group.toEntity())

    override suspend fun delete(id: Long) {
        alarmDao.detachGroup(id)
        dao.delete(id)
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)
    override suspend fun replaceAll(groups: List<AlarmGroup>) = dao.replaceAll(groups.map { it.toEntity() })
}

@Singleton
class HistoryRepositoryImpl @Inject constructor(private val dao: EventDao) : HistoryRepository {
    override suspend fun start(event: AlarmEvent): Long = dao.insert(event.toEntity())
    override suspend fun update(event: AlarmEvent) = dao.update(event.toEntity())
    override suspend fun get(id: Long): AlarmEvent? = dao.get(id)?.toDomain()
    override suspend fun findOccurrence(alarmId: Long, scheduledAt: Long): AlarmEvent? =
        dao.findOccurrence(alarmId, scheduledAt)?.toDomain()

    override suspend fun openEvents(): List<AlarmEvent> = dao.openEvents().map { it.toDomain() }
    override suspend fun recent(limit: Int): List<AlarmEvent> = dao.recent(limit).map { it.toDomain() }
    override fun observeRecent(limit: Int): Flow<List<AlarmEvent>> = dao.observeRecent(limit).map { l -> l.map { it.toDomain() } }
    override fun observeSince(sinceMillis: Long): Flow<List<AlarmEvent>> = dao.observeSince(sinceMillis).map { l -> l.map { it.toDomain() } }
    override suspend fun deleteOlderThan(beforeMillis: Long) = dao.deleteOlderThan(beforeMillis)
    override suspend fun clear() = dao.deleteAll()
    override suspend fun replaceAll(events: List<AlarmEvent>) {
        dao.deleteAll()
        dao.insertAll(events.map { it.toEntity() })
    }
}

@Singleton
class TimerRepositoryImpl @Inject constructor(
    private val dao: TimerDao,
    private val time: TimeSource,
) : TimerRepository {
    override fun observeTimers(): Flow<List<CountdownTimer>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override suspend fun getTimers(): List<CountdownTimer> = dao.getAll().map { it.toDomain() }
    override suspend fun getTimer(id: Long): CountdownTimer? = dao.get(id)?.toDomain()
    override suspend fun save(timer: CountdownTimer): Long =
        dao.upsert(timer.copy(createdAt = if (timer.createdAt == 0L) time.nowMillis() else timer.createdAt).toEntity())

    override suspend fun delete(id: Long) = dao.delete(id)
}

@Singleton
class WorldClockRepositoryImpl @Inject constructor(private val dao: WorldClockDao) : WorldClockRepository {
    override fun observeCities(): Flow<List<WorldClockCity>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override suspend fun getCities(): List<WorldClockCity> = dao.getAll().map { it.toDomain() }
    override suspend fun add(city: WorldClockCity) {
        val next = (dao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.upsert(city.copy(sortOrder = next).toEntity())
    }

    override suspend fun remove(zoneId: String) {
        dao.getAll().firstOrNull { it.zoneId == zoneId }?.let { dao.delete(it) }
    }

    override suspend fun replaceAll(cities: List<WorldClockCity>) = dao.replaceAll(cities.map { it.toEntity() })
}

@Singleton
class ExceptionRepositoryImpl @Inject constructor(private val dao: ExceptionDao) : ExceptionRepository {
    override fun observeDates(): Flow<List<ExceptionDate>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override suspend fun getDates(): List<ExceptionDate> = dao.getAll().map { it.toDomain() }
    override suspend fun add(date: ExceptionDate) = dao.upsert(date.toEntity())
    override suspend fun remove(epochDay: Long) = dao.delete(epochDay)
    override suspend fun pruneBefore(epochDay: Long) = dao.deleteBefore(epochDay)
    override suspend fun replaceAll(dates: List<ExceptionDate>) = dao.replaceAll(dates.map { it.toEntity() })
}

@Singleton
class WakeCheckRepositoryImpl @Inject constructor(private val dao: WakeCheckDao) : WakeCheckRepository {
    override suspend fun getAll(): List<WakeCheck> = dao.getAll().map { it.toDomain() }
    override fun observeAll(): Flow<List<WakeCheck>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override suspend fun get(alarmId: Long): WakeCheck? = dao.get(alarmId)?.toDomain()
    override suspend fun save(check: WakeCheck) = dao.upsert(check.toEntity())
    override suspend fun delete(alarmId: Long) = dao.delete(alarmId)
    override suspend fun clear() = dao.deleteAll()
}
