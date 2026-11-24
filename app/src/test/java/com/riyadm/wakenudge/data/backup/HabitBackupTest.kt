package com.riyadm.wakenudge.data.backup

import com.riyadm.wakenudge.domain.habits.Habit
import com.riyadm.wakenudge.domain.habits.HabitBackup
import com.riyadm.wakenudge.domain.habits.HabitCheckIn
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitBackupTest {
    private val created = LocalDate.of(2026, 11, 16).toEpochDay()
    private val valid = HabitBackup(
        habits = listOf(Habit(id = 4, name = "Read 📚", icon = "📚", colorIndex = 2, reminderMinutes = 480, createdEpochDay = created)),
        checkIns = listOf(HabitCheckIn(4, created), HabitCheckIn(4, created + 1)),
    )

    @Test fun `habits and checkins round trip through the additive backup field`() = runTest {
        val source = BackupRig(Sample.file.copy(habits = valid))
        val json = source.manager.export(includeHistory = false)
        assertTrue(json.contains("\"habits\""))
        assertTrue(json.contains("\"checkIns\""))

        val target = BackupRig()
        val result = target.manager.import(json) as ImportResult.Success
        assertEquals(valid, target.store.contents.habits)
        assertEquals(1, result.counts.habits)
    }

    @Test fun `an old file without habits leaves the stored habits alone`() = runTest {
        val target = BackupRig(Sample.file.copy(habits = valid))
        val result = target.manager.import("""{"app":"WakeNudge","formatVersion":1,"alarms":[]}""")
        assertTrue(result is ImportResult.Success)
        assertEquals(valid, target.store.contents.habits)
        assertNull((result as ImportResult.Success).counts.habits)
    }

    @Test fun `an explicitly empty habits field replaces the stored habits`() = runTest {
        val target = BackupRig(Sample.file.copy(habits = valid))
        target.manager.import(BackupCodec.encode(BackupFile(habits = HabitBackup())))
        assertEquals(HabitBackup(), target.store.contents.habits)
    }

    @Test fun `hostile habit values are bounded and checkins are relinked`() {
        val source = HabitBackup(
            habits = listOf(
                Habit(
                    id = 9,
                    name = "x".repeat(80),
                    icon = "",
                    colorIndex = 99,
                    daysMask = 0,
                    reminderMinutes = 9_999,
                    createdEpochDay = created,
                    sortOrder = 99_999,
                ),
                Habit(id = 9, name = "Second", createdEpochDay = created),
                Habit(id = 10, name = "", createdEpochDay = -100),
            ),
            checkIns = listOf(
                HabitCheckIn(9, created),
                HabitCheckIn(9, created),
                HabitCheckIn(9, created - 1),
                HabitCheckIn(404, created),
            ),
        )
        val cleaned = BackupSanitizer.sanitize(BackupFile(habits = source)).file.habits!!
        assertEquals(3, cleaned.habits.size)
        assertEquals(40, cleaned.habits[0].name.length)
        assertEquals(Habit.DEFAULT_ICON, cleaned.habits[0].icon)
        assertEquals(Habit.PALETTE_SIZE - 1, cleaned.habits[0].colorIndex)
        assertEquals(Habit.EVERY_DAY_MASK, cleaned.habits[0].daysMask)
        assertEquals(1_439, cleaned.habits[0].reminderMinutes)
        assertEquals(10_000, cleaned.habits[0].sortOrder)
        assertEquals(3, cleaned.habits.map { it.id }.distinct().size)
        assertEquals(0L, cleaned.habits[2].createdEpochDay)
        assertEquals("Habit", cleaned.habits[2].name)
        assertEquals(listOf(HabitCheckIn(cleaned.habits[0].id, created)), cleaned.checkIns)
    }

    @Test fun `habit count is capped and malformed entries are skipped without sinking the file`() {
        val many = HabitBackup(habits = (1L..40L).map { Habit(id = it, name = "Habit $it", createdEpochDay = created) })
        val cleaned = BackupSanitizer.sanitize(BackupFile(habits = many)).file.habits!!
        assertEquals(Habit.MAX_HABITS, cleaned.habits.size)

        val raw = """{"app":"WakeNudge","formatVersion":1,"habits":{"habits":[{"id":1,"name":"Valid","createdEpochDay":$created},42],"checkIns":[{"habitId":1,"epochDay":$created},"bad"]}}"""
        val parsed = BackupCodec.parse(raw) as ParseOutcome.Parsed
        assertEquals(1, parsed.file.habits?.habits?.size)
        assertEquals(1, parsed.file.habits?.checkIns?.size)
        assertEquals(2, parsed.warnings.size)
    }

    @Test fun `unknown additive keys remain forwards compatible`() {
        val parsed = BackupCodec.parse("""{"app":"WakeNudge","formatVersion":1,"habits":{"habits":[],"checkIns":[],"futureField":true},"otherFutureField":1}""")
        assertTrue(parsed is ParseOutcome.Parsed)
        assertEquals(HabitBackup(), (parsed as ParseOutcome.Parsed).file.habits)
    }
}
