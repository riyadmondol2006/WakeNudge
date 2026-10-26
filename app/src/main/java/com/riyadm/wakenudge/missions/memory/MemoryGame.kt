package com.riyadm.wakenudge.missions.memory

import com.riyadm.wakenudge.domain.model.Difficulty
import kotlin.random.Random

/**
 * Rules of the Simon-style pattern-recall mission, free of any UI so they can be tested and saved across
 * configuration changes. A round shows [sequence] (phase [Phase.SHOWING]); once the UI has played it back it calls
 * [beginInput] and the user repeats it tile by tile. A wrong tap restarts the same round with a brand-new sequence
 * of the same length, so a lucky guess cannot be brute-forced by repeating the first tile.
 */
class MemoryGame(
    val gridSide: Int,
    val startLength: Int,
    val rounds: Int,
    private val random: Random = Random.Default,
) {
    enum class Phase { SHOWING, INPUT, FINISHED }
    enum class TapResult { IGNORED, CORRECT, ROUND_COMPLETE, GAME_COMPLETE, WRONG }

    val tileCount: Int = gridSide * gridSide

    /** Zero-based index of the round being played. */
    var round: Int = 0
        private set
    var phase: Phase = Phase.SHOWING
        private set
    var sequence: List<Int> = emptyList()
        private set

    /** How many tiles of [sequence] the user has already repeated correctly. */
    var inputIndex: Int = 0
        private set

    /** Increments whenever a new sequence is generated, so the UI can restart playback for it. */
    var sequenceId: Int = 0
        private set

    init {
        require(gridSide >= 2 && startLength >= 1 && rounds >= 1)
        newSequence()
    }

    val currentLength: Int get() = startLength + round

    /** Rounds fully completed so far. */
    val completedRounds: Int get() = if (phase == Phase.FINISHED) rounds else round

    fun beginInput() {
        if (phase == Phase.SHOWING) {
            phase = Phase.INPUT
            inputIndex = 0
        }
    }

    fun tap(tile: Int): TapResult {
        if (phase != Phase.INPUT) return TapResult.IGNORED
        if (tile != sequence[inputIndex]) {
            newSequence()
            return TapResult.WRONG
        }
        inputIndex++
        if (inputIndex < sequence.size) return TapResult.CORRECT
        return if (round == rounds - 1) {
            phase = Phase.FINISHED
            TapResult.GAME_COMPLETE
        } else {
            round++
            newSequence()
            TapResult.ROUND_COMPLETE
        }
    }

    private fun newSequence() {
        val result = ArrayList<Int>(currentLength)
        repeat(currentLength) {
            // Never the same tile twice in a row: two identical flashes in a row are easy to miscount.
            var tile = random.nextInt(tileCount)
            while (result.isNotEmpty() && tile == result.last()) tile = random.nextInt(tileCount)
            result += tile
        }
        sequence = result
        inputIndex = 0
        phase = Phase.SHOWING
        sequenceId++
    }

    /** Flat form for `rememberSaveable`: everything except the random source. */
    fun toSaved(): List<Int> = listOf(gridSide, startLength, rounds, round, phase.ordinal, inputIndex, sequenceId) + sequence

    companion object {
        fun forDifficulty(difficulty: Difficulty, rounds: Int, random: Random = Random.Default): MemoryGame = when (difficulty) {
            // The sequence grows by one tile per round, so three rounds of medium are 3, 4 and 5 tiles.
            Difficulty.EASY -> MemoryGame(gridSide = 3, startLength = 2, rounds = rounds, random = random)
            Difficulty.MEDIUM -> MemoryGame(gridSide = 3, startLength = 3, rounds = rounds, random = random)
            Difficulty.HARD -> MemoryGame(gridSide = 4, startLength = 4, rounds = rounds, random = random)
        }

        fun restore(saved: List<Int>, random: Random = Random.Default): MemoryGame {
            val game = MemoryGame(saved[0], saved[1], saved[2], random)
            game.round = saved[3]
            // Replaying the sequence is cheap, so a restored mid-input round starts from its playback again.
            game.sequence = saved.drop(7)
            game.phase = if (Phase.entries[saved[4]] == Phase.FINISHED) Phase.FINISHED else Phase.SHOWING
            game.inputIndex = 0
            game.sequenceId = saved[6] + 1
            return game
        }
    }
}
