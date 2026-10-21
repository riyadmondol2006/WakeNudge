package com.riyadm.wakenudge.missions.puzzle

import com.riyadm.wakenudge.domain.model.Difficulty
import kotlin.random.Random

/**
 * A Schulte table: the numbers 1..side² scattered over a grid, tapped in ascending order. Needs visual search and
 * focus rather than arithmetic, which makes it a good "wake up your eyes" task.
 */
class NumberGrid(val side: Int, layout: List<Int>, next: Int = 1) {
    enum class TapResult { CORRECT, WRONG, COMPLETE }

    val numbers: List<Int> = layout.toList()
    val total: Int = side * side

    /** The number the user has to find next (`total + 1` once finished). */
    var next: Int = next
        private set

    val isComplete: Boolean get() = next > total

    init {
        require(numbers.size == total && numbers.sorted() == List(total) { it + 1 }) { "Layout must contain 1..$total exactly once" }
    }

    fun tap(number: Int): TapResult {
        if (isComplete) return TapResult.COMPLETE
        if (number != next) return TapResult.WRONG
        next++
        return if (isComplete) TapResult.COMPLETE else TapResult.CORRECT
    }

    companion object {
        fun scrambled(side: Int, random: Random): NumberGrid {
            val cells = (1..side * side).toMutableList()
            do cells.shuffle(random) while (cells == cells.sorted())
            return NumberGrid(side, cells)
        }

        fun forDifficulty(difficulty: Difficulty, random: Random): NumberGrid = scrambled(
            when (difficulty) {
                Difficulty.EASY -> 3
                Difficulty.MEDIUM -> 4
                Difficulty.HARD -> 5
            },
            random,
        )
    }
}
