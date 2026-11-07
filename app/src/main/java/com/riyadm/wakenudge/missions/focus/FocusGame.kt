package com.riyadm.wakenudge.missions.focus

import com.riyadm.wakenudge.domain.model.Difficulty
import kotlin.random.Random

enum class FocusShape { CIRCLE, SQUARE, TRIANGLE, DIAMOND }

/** Two colours that stay apart under the common colour-vision deficiencies; the shape always tells them apart as well. */
enum class FocusColor { BLUE, ORANGE }

data class FocusCue(val shape: FocusShape, val color: FocusColor)

/** One shape on the board. [id] tells a tap on it from a late tap on the one before. */
data class FocusStimulus(val id: Int, val cue: FocusCue, val cell: Int)

/**
 * Go/no-go: shapes appear one at a time in a 3 x 3 grid, and only the one asked for may be tapped. Tapping anything else
 * starts the round again; letting a shape go by costs nothing. Every round asks for a different shape. Half the shapes
 * that are not it share its colour and the other half its shape, so neither colour nor shape alone is enough, and it
 * moves around the board, so it cannot be done on reflex. Pure, so it is tested and saved without any UI.
 */
class FocusGame(
    val rounds: Int,
    val hitsPerRound: Int,
    private val random: Random = Random.Default,
) {
    enum class Result { IGNORED, HIT, ROUND_COMPLETE, GAME_COMPLETE, WRONG, LET_GO, MISSED }

    var round: Int = 0
        private set
    var hits: Int = 0
        private set
    var target: FocusCue = randomCue(null)
        private set
    var finished: Boolean = false
        private set
    var current: FocusStimulus? = null
        private set

    private var nextId = 1
    private var sinceTarget = 0
    private var lastCell = -1

    init {
        require(rounds >= 1 && hitsPerRound >= 1)
    }

    /** Shows the next shape. Never in the cell of the last one, and the target never waits more than three shapes. */
    fun next(): FocusStimulus {
        val isTarget = sinceTarget >= MAX_WITHOUT_TARGET || random.nextInt(100) < TARGET_PERCENT
        val cue = if (isTarget) target else distractor()
        sinceTarget = if (isTarget) 0 else sinceTarget + 1
        var cell = random.nextInt(CELLS)
        while (cell == lastCell) cell = random.nextInt(CELLS)
        lastCell = cell
        return FocusStimulus(nextId++, cue, cell).also { current = it }
    }

    fun isTarget(stimulus: FocusStimulus): Boolean = stimulus.cue == target

    /** A tap on [id]. Only the shape on the board counts: a tap on one that has already gone does nothing. */
    fun tap(id: Int): Result {
        val shown = current?.takeIf { it.id == id && !finished } ?: return Result.IGNORED
        current = null
        if (shown.cue != target) {
            hits = 0
            return Result.WRONG
        }
        hits++
        if (hits < hitsPerRound) return Result.HIT
        if (round == rounds - 1) {
            finished = true
            return Result.GAME_COMPLETE
        }
        round++
        hits = 0
        target = randomCue(target)
        sinceTarget = 0
        return Result.ROUND_COMPLETE
    }

    /** The shape [id] went by untapped: its time ran out, or a screen-reader user said "not this one". */
    fun letGo(id: Int): Result {
        val shown = current?.takeIf { it.id == id && !finished } ?: return Result.IGNORED
        current = null
        return if (shown.cue == target) Result.MISSED else Result.LET_GO
    }

    val completedRounds: Int get() = if (finished) rounds else round

    private fun distractor(): FocusCue = if (random.nextBoolean()) {
        FocusCue(FocusShape.entries.filter { it != target.shape }.random(random), target.color)
    } else {
        FocusCue(target.shape, FocusColor.entries.first { it != target.color })
    }

    private fun randomCue(not: FocusCue?): FocusCue {
        while (true) {
            val cue = FocusCue(FocusShape.entries.random(random), FocusColor.entries.random(random))
            if (cue != not) return cue
        }
    }

    /** Flat form for `rememberSaveable`. The shape on the board is not kept: a rebuilt screen shows a new one. */
    fun toSaved(): List<Int> = listOf(rounds, hitsPerRound, round, hits, target.shape.ordinal, target.color.ordinal, if (finished) 1 else 0)

    companion object {
        const val CELLS = 9
        const val TARGET_PERCENT = 40
        const val MAX_WITHOUT_TARGET = 3

        fun forDifficulty(difficulty: Difficulty, rounds: Int, random: Random = Random.Default) =
            FocusGame(rounds.coerceAtLeast(1), hitsFor(difficulty), random)

        fun hitsFor(difficulty: Difficulty) = when (difficulty) {
            Difficulty.EASY -> 3
            Difficulty.MEDIUM -> 4
            Difficulty.HARD -> 5
        }

        /** How long a shape stays up and the pause before the next, in milliseconds. */
        fun timing(difficulty: Difficulty): Pair<Long, Long> = when (difficulty) {
            Difficulty.EASY -> 1_500L to 350L
            Difficulty.MEDIUM -> 1_200L to 300L
            Difficulty.HARD -> 1_000L to 250L
        }

        fun restore(saved: List<Int>, random: Random = Random.Default): FocusGame {
            val game = FocusGame(saved[0], saved[1], random)
            game.round = saved[2]
            game.hits = saved[3]
            game.target = FocusCue(FocusShape.entries[saved[4]], FocusColor.entries[saved[5]])
            game.finished = saved[6] == 1
            return game
        }
    }
}
