package com.riyadm.wakenudge.missions

import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.missions.puzzle.NumberGrid
import com.riyadm.wakenudge.missions.puzzle.PatternGenerator
import com.riyadm.wakenudge.missions.puzzle.PatternGrid
import com.riyadm.wakenudge.missions.puzzle.PatternTracer
import com.riyadm.wakenudge.missions.puzzle.SlidingPuzzle
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlidingPuzzleTest {
    @Test fun `solvability checker agrees with known boards`() {
        assertTrue(SlidingPuzzle.isSolvable(listOf(1, 2, 3, 4, 5, 6, 7, 8, 0), 3))
        // The unsolvable "14-15" style swap on a 3x3.
        assertFalse(SlidingPuzzle.isSolvable(listOf(2, 1, 3, 4, 5, 6, 7, 8, 0), 3))
        assertTrue(SlidingPuzzle.isSolvable(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 0), 4))
        assertFalse(SlidingPuzzle.isSolvable(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15, 14, 0), 4))
    }

    @Test fun `every generated board is a permutation, solvable and not already solved`() {
        for (seed in 0 until 300) for (difficulty in Difficulty.entries) {
            val puzzle = SlidingPuzzle.forDifficulty(difficulty, Random(seed))
            val n = puzzle.size * puzzle.size
            assertEquals(List(n) { it }, puzzle.tiles.sorted())
            assertTrue("seed $seed $difficulty", SlidingPuzzle.isSolvable(puzzle.tiles, puzzle.size))
            assertFalse(puzzle.isSolved)
        }
    }

    @Test fun `sizes follow the difficulty`() {
        assertEquals(3, SlidingPuzzle.forDifficulty(Difficulty.EASY, Random(1)).size)
        assertEquals(3, SlidingPuzzle.forDifficulty(Difficulty.MEDIUM, Random(1)).size)
        assertEquals(4, SlidingPuzzle.forDifficulty(Difficulty.HARD, Random(1)).size)
    }

    @Test fun `easy boards are closer to solved than hard ones on average`() {
        fun average(difficulty: Difficulty) = (0 until 100).map {
            val puzzle = SlidingPuzzle.forDifficulty(difficulty, Random(it))
            SlidingPuzzle.manhattanDistance(puzzle.tiles, puzzle.size)
        }.average()
        assertTrue(average(Difficulty.EASY) < average(Difficulty.MEDIUM))
    }

    @Test fun `tapping a tile next to the blank slides it`() {
        val puzzle = SlidingPuzzle.restore(3, listOf(1, 2, 3, 4, 5, 6, 7, 0, 8))
        assertTrue(puzzle.tap(8))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 0), puzzle.tiles)
        assertTrue(puzzle.isSolved)
    }

    @Test fun `tapping a tile in line with the blank slides the whole row or column`() {
        val row = SlidingPuzzle.restore(3, listOf(0, 1, 2, 3, 4, 5, 6, 7, 8))
        assertTrue(row.tap(2))
        assertEquals(listOf(1, 2, 0, 3, 4, 5, 6, 7, 8), row.tiles)
        val column = SlidingPuzzle.restore(3, listOf(1, 2, 3, 4, 5, 6, 0, 7, 8))
        assertTrue(column.tap(0))
        assertEquals(listOf(0, 2, 3, 1, 5, 6, 4, 7, 8), column.tiles)
    }

    @Test fun `tiles not in line with the blank do not move`() {
        val puzzle = SlidingPuzzle.restore(3, listOf(1, 2, 3, 4, 0, 5, 6, 7, 8))
        val before = puzzle.tiles
        assertFalse(puzzle.canSlide(0))
        assertFalse(puzzle.tap(0))
        assertFalse(puzzle.tap(4))
        assertEquals(before, puzzle.tiles)
    }

    @Test fun `a swipe only moves a tile towards the blank`() {
        val puzzle = SlidingPuzzle.restore(3, listOf(1, 2, 3, 4, 5, 6, 7, 0, 8))
        assertFalse(puzzle.swipe(8, dRow = 0, dCol = 1))
        assertFalse(puzzle.swipe(8, dRow = 1, dCol = 0))
        assertTrue(puzzle.swipe(8, dRow = 0, dCol = -1))
        assertEquals(0, puzzle.tiles.last())
    }

    @Test fun `legal moves never break the permutation or solvability`() {
        val random = Random(8)
        val puzzle = SlidingPuzzle.forDifficulty(Difficulty.HARD, random)
        repeat(500) { puzzle.tap(random.nextInt(16)) }
        assertEquals(List(16) { it }, puzzle.tiles.sorted())
        assertTrue(SlidingPuzzle.isSolvable(puzzle.tiles, 4))
    }

    @Test fun `progress counts tiles on their home square`() {
        val puzzle = SlidingPuzzle.restore(3, listOf(1, 2, 3, 4, 5, 6, 8, 7, 0))
        assertEquals(6, puzzle.correctCount)
        assertEquals(8, puzzle.tileTotal)
        assertEquals(8, SlidingPuzzle.solved(3).correctCount)
    }

    @Test fun `a corrupted saved board is rejected`() {
        val result = runCatching { SlidingPuzzle.restore(3, listOf(1, 1, 3, 4, 5, 6, 7, 8, 0)) }
        assertTrue(result.isFailure)
    }
}

class NumberGridTest {
    @Test fun `grid sizes follow the difficulty and contain every number once`() {
        for ((difficulty, side) in listOf(Difficulty.EASY to 3, Difficulty.MEDIUM to 4, Difficulty.HARD to 5)) {
            for (seed in 0 until 50) {
                val grid = NumberGrid.forDifficulty(difficulty, Random(seed))
                assertEquals(side, grid.side)
                assertEquals((1..side * side).toList(), grid.numbers.sorted())
                assertNotEquals("scrambled", (1..side * side).toList(), grid.numbers)
            }
        }
    }

    @Test fun `numbers must be tapped in ascending order`() {
        val grid = NumberGrid.scrambled(3, Random(3))
        assertEquals(NumberGrid.TapResult.WRONG, grid.tap(2))
        assertEquals(1, grid.next)
        assertEquals(NumberGrid.TapResult.CORRECT, grid.tap(1))
        assertEquals(2, grid.next)
        assertEquals(NumberGrid.TapResult.WRONG, grid.tap(1))
        assertEquals(NumberGrid.TapResult.WRONG, grid.tap(5))
        assertEquals(2, grid.next)
    }

    @Test fun `tapping 1 to N completes it and a wrong tap never loses progress`() {
        val grid = NumberGrid.scrambled(4, Random(4))
        for (n in 1..16) {
            if (n < 16) assertEquals(NumberGrid.TapResult.WRONG, grid.tap(n + 1))
            assertEquals(if (n == 16) NumberGrid.TapResult.COMPLETE else NumberGrid.TapResult.CORRECT, grid.tap(n))
        }
        assertTrue(grid.isComplete)
    }

    @Test fun `layout validation rejects duplicates`() {
        assertTrue(runCatching { NumberGrid(3, listOf(1, 1, 3, 4, 5, 6, 7, 8, 9)) }.isFailure)
    }
}

class PatternTraceTest {
    @Test fun `dots between two nodes are the ones on the straight line`() {
        assertEquals(listOf(1), PatternGrid.between(0, 2))
        assertEquals(listOf(4), PatternGrid.between(0, 8))
        assertEquals(listOf(4), PatternGrid.between(6, 2))
        assertEquals(listOf(3), PatternGrid.between(0, 6))
        assertEquals(emptyList<Int>(), PatternGrid.between(0, 1))
        assertEquals(emptyList<Int>(), PatternGrid.between(0, 5))
        assertEquals(emptyList<Int>(), PatternGrid.between(4, 4))
    }

    @Test fun `generated patterns have the requested length distinct dots and no segment over another dot`() {
        for (difficulty in Difficulty.entries) {
            val length = PatternGenerator.lengthFor(difficulty)
            for (seed in 0 until 200) {
                val pattern = PatternGenerator.generate(length, Random(seed))
                assertEquals(length, pattern.size)
                assertEquals(length, pattern.toSet().size)
                assertTrue(pattern.all { it in 0 until PatternGrid.NODES })
                assertTrue(pattern.zipWithNext().all { (a, b) -> PatternGrid.between(a, b).isEmpty() })
            }
        }
    }

    @Test fun `lengths are 4 6 and 8`() {
        assertEquals(listOf(4, 6, 8), Difficulty.entries.map { PatternGenerator.lengthFor(it) })
    }

    @Test fun `tracing the same dots in the same order matches`() {
        val pattern = listOf(0, 1, 5, 4)
        val tracer = PatternTracer(pattern)
        pattern.forEach { tracer.enter(it) }
        assertTrue(tracer.matches())
    }

    @Test fun `the reverse direction does not match`() {
        val pattern = listOf(0, 1, 5, 4)
        val tracer = PatternTracer(pattern)
        pattern.reversed().forEach { tracer.enter(it) }
        assertEquals(pattern.reversed(), tracer.visited)
        assertFalse(tracer.matches())
    }

    @Test fun `a partial or extra trace does not match and revisiting a dot changes nothing`() {
        val tracer = PatternTracer(listOf(3, 4, 5))
        tracer.enter(3); tracer.enter(4)
        assertFalse(tracer.matches())
        assertTrue(tracer.isOnTrack)
        assertFalse(tracer.enter(3))
        assertEquals(listOf(3, 4), tracer.visited)
        tracer.enter(5); tracer.enter(8)
        assertFalse(tracer.matches())
        assertFalse(tracer.isOnTrack)
    }

    @Test fun `jumping straight over a dot includes that dot`() {
        val tracer = PatternTracer(listOf(0, 1, 2))
        tracer.enter(0); tracer.enter(2)
        assertEquals(listOf(0, 1, 2), tracer.visited)
        assertTrue(tracer.matches())
        // A dot that was already visited is not added twice.
        val other = PatternTracer(listOf(1, 0, 6, 3))
        other.enter(1); other.enter(0); other.enter(6)
        assertEquals(listOf(1, 0, 3, 6), other.visited)
    }

    @Test fun `clearing starts a fresh attempt`() {
        val tracer = PatternTracer(listOf(0, 1))
        tracer.enter(4)
        tracer.clear()
        assertTrue(tracer.isEmpty)
        tracer.enter(0); tracer.enter(1)
        assertTrue(tracer.matches())
    }
}
