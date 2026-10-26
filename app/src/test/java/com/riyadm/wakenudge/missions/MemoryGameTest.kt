package com.riyadm.wakenudge.missions

import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.missions.memory.MemoryGame
import com.riyadm.wakenudge.missions.memory.MemoryGame.Phase
import com.riyadm.wakenudge.missions.memory.MemoryGame.TapResult
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGameTest {
    private fun game(difficulty: Difficulty = Difficulty.EASY, rounds: Int = 3, seed: Int = 1) =
        MemoryGame.forDifficulty(difficulty, rounds, Random(seed))

    @Test fun `grid sizes and starting lengths follow the difficulty`() {
        val easy = game(Difficulty.EASY)
        assertEquals(9, easy.tileCount); assertEquals(2, easy.sequence.size)
        val medium = game(Difficulty.MEDIUM)
        assertEquals(9, medium.tileCount); assertEquals(3, medium.sequence.size)
        val hard = game(Difficulty.HARD)
        assertEquals(16, hard.tileCount); assertEquals(4, hard.sequence.size)
    }

    @Test fun `sequences use valid tiles and never repeat a tile back to back`() {
        for (seed in 0 until 200) for (difficulty in Difficulty.entries) {
            val g = game(difficulty, rounds = 6, seed = seed)
            assertTrue(g.sequence.all { it in 0 until g.tileCount })
            assertTrue(g.sequence.zipWithNext().none { (a, b) -> a == b })
        }
    }

    @Test fun `taps are ignored while the sequence is being shown`() {
        val g = game()
        assertEquals(Phase.SHOWING, g.phase)
        assertEquals(TapResult.IGNORED, g.tap(g.sequence[0]))
        assertEquals(0, g.inputIndex)
    }

    @Test fun `repeating the sequence advances the round and grows it by one`() {
        val g = game(rounds = 3)
        g.beginInput()
        val first = g.sequence
        first.dropLast(1).forEach { assertEquals(TapResult.CORRECT, g.tap(it)) }
        assertEquals(TapResult.ROUND_COMPLETE, g.tap(first.last()))
        assertEquals(1, g.round)
        assertEquals(first.size + 1, g.sequence.size)
        assertEquals(Phase.SHOWING, g.phase)
        assertEquals(1, g.completedRounds)
    }

    @Test fun `finishing the last round completes the game`() {
        val g = game(rounds = 2)
        var result = TapResult.IGNORED
        repeat(2) {
            g.beginInput()
            g.sequence.forEach { result = g.tap(it) }
        }
        assertEquals(TapResult.GAME_COMPLETE, result)
        assertEquals(Phase.FINISHED, g.phase)
        assertEquals(2, g.completedRounds)
        assertEquals(TapResult.IGNORED, g.tap(0))
    }

    @Test fun `a wrong tap restarts the same round with a new sequence of the same length`() {
        val g = game(rounds = 3)
        g.beginInput()
        val before = g.sequence
        val idBefore = g.sequenceId
        val wrong = (0 until g.tileCount).first { it != before[0] }
        assertEquals(TapResult.WRONG, g.tap(wrong))
        assertEquals(0, g.round)
        assertEquals(before.size, g.sequence.size)
        assertEquals(Phase.SHOWING, g.phase)
        assertEquals(0, g.inputIndex)
        assertNotEquals(idBefore, g.sequenceId)
    }

    @Test fun `a wrong tap midway does not lose completed rounds`() {
        val g = game(rounds = 4)
        g.beginInput(); g.sequence.forEach { g.tap(it) }
        g.beginInput()
        g.tap(g.sequence[0])
        g.tap((0 until g.tileCount).first { it != g.sequence[1] })
        assertEquals(1, g.round)
        assertEquals(1, g.completedRounds)
    }

    @Test fun `saved state restores the round and the sequence`() {
        val g = game(rounds = 5)
        g.beginInput(); g.sequence.forEach { g.tap(it) }
        val restored = MemoryGame.restore(g.toSaved(), Random(3))
        assertEquals(g.round, restored.round)
        assertEquals(g.sequence, restored.sequence)
        assertEquals(g.gridSide, restored.gridSide)
        assertEquals(Phase.SHOWING, restored.phase)
        assertNotEquals(g.sequenceId, restored.sequenceId)
    }
}
