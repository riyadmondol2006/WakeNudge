package com.riyadm.wakenudge.data.habits

import androidx.room.withTransaction
import com.riyadm.wakenudge.domain.habits.Habit
import com.riyadm.wakenudge.domain.habits.HabitBackup
import com.riyadm.wakenudge.domain.habits.HabitData
import com.riyadm.wakenudge.domain.habits.HabitRepository
import com.riyadm.wakenudge.domain.habits.HabitRules
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Singleton
class HabitRepositoryImpl @Inject constructor(
    private val db: HabitDatabase,
    private val dao: HabitDao,
) : HabitRepository {
    override fun observe(): Flow<HabitData> = combine(dao.observeHabits(), dao.observeCheckIns()) { habits, checkIns ->
        HabitData(habits.map(HabitEntity::toModel), checkIns.mapTo(hashSetOf(), HabitCheckInEntity::toModel))
    }

    override suspend fun data(): HabitData = HabitData(
        dao.habits().map(HabitEntity::toModel),
        dao.checkIns().mapTo(hashSetOf(), HabitCheckInEntity::toModel),
    )

    override suspend fun habit(id: Long): Habit? = dao.habit(id)?.toModel()

    override suspend fun save(habit: Habit): Long? = db.withTransaction {
        val name = habit.name.trim()
        if (name.isEmpty() || (habit.id == 0L && dao.count() >= Habit.MAX_HABITS)) return@withTransaction null
        val ready = habit.copy(
            name = name,
            icon = habit.icon.ifBlank { Habit.DEFAULT_ICON },
            daysMask = habit.daysMask.takeIf { it and Habit.EVERY_DAY_MASK != 0 } ?: Habit.EVERY_DAY_MASK,
            sortOrder = if (habit.id == 0L) dao.nextSortOrder() else habit.sortOrder,
        )
        dao.upsert(HabitEntity.fromModel(ready))
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun move(id: Long, upwards: Boolean) = db.withTransaction {
        val habits = dao.habits()
        val index = habits.indexOfFirst { it.id == id }
        val other = if (upwards) index - 1 else index + 1
        if (index < 0 || other !in habits.indices) return@withTransaction
        val reordered = habits.toMutableList()
        val moved = reordered.removeAt(index)
        reordered.add(other, moved)
        reordered.forEachIndexed { order, habit -> dao.upsert(habit.copy(sortOrder = order)) }
    }

    override suspend fun setDone(id: Long, date: LocalDate, done: Boolean, today: LocalDate): Boolean {
        val habit = dao.habit(id) ?: return false
        if (!HabitRules.canToggle(habit.toModel(), date, today)) return false
        if (done) dao.upsertCheckIn(HabitCheckInEntity(id, date.toEpochDay()))
        else dao.deleteCheckIn(id, date.toEpochDay())
        return true
    }

    override suspend fun replace(backup: HabitBackup) = dao.replaceAll(
        backup.habits.map(HabitEntity::fromModel),
        backup.checkIns.map(HabitCheckInEntity::fromModel),
    )
}
