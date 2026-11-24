package com.riyadm.wakenudge.data.habits

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.wakenudge.domain.habits.Habit
import com.riyadm.wakenudge.domain.habits.HabitBackup
import com.riyadm.wakenudge.domain.habits.HabitCheckIn
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HabitRepositoryInstrumentedTest {
    private lateinit var db: HabitDatabase
    private lateinit var repository: HabitRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, HabitDatabase::class.java).build()
        repository = HabitRepositoryImpl(db, db.habitDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun checkInWindowAndCascadeAreEnforced() = runBlocking {
        val today = LocalDate.of(2026, 11, 24)
        val id = requireNotNull(repository.save(Habit(name = "Read", createdEpochDay = today.minusDays(10).toEpochDay())))

        assertTrue(repository.setDone(id, today.minusDays(6), true, today))
        assertFalse(repository.setDone(id, today.minusDays(7), true, today))
        assertFalse(repository.setDone(id, today.plusDays(1), true, today))
        assertTrue(repository.setDone(id, today.minusDays(6), false, today))
        assertTrue(repository.setDone(id, today, true, today))
        assertEquals(setOf(HabitCheckIn(id, today.toEpochDay())), repository.data().checkIns)

        repository.delete(id)
        assertTrue(repository.data().checkIns.isEmpty())
    }

    @Test
    fun reorderAndReplaceRoundTripThroughRoom() = runBlocking {
        val first = requireNotNull(repository.save(Habit(name = "First")))
        val second = requireNotNull(repository.save(Habit(name = "Second")))

        repository.move(second, upwards = true)
        assertEquals(listOf(second, first), repository.data().habits.map { it.id })

        val restored = Habit(id = 42, name = "Restored", createdEpochDay = 20_000)
        repository.replace(HabitBackup(
            habits = listOf(restored),
            checkIns = listOf(HabitCheckIn(restored.id, 20_000)),
        ))
        val data = repository.data()
        assertEquals(listOf(restored), data.habits)
        assertEquals(setOf(HabitCheckIn(restored.id, 20_000)), data.checkIns)
    }
}
