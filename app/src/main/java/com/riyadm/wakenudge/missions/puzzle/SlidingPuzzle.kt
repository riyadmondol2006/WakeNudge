package com.riyadm.wakenudge.missions.puzzle

import com.riyadm.wakenudge.domain.model.Difficulty
import kotlin.math.abs
import kotlin.random.Random

/**
 * The classic sliding-tile puzzle on a [size] x [size] board; tile `0` is the blank. Boards are made by walking
 * random legal moves away from the solved position, so every generated board is solvable by construction (a random
 * shuffle would be unsolvable half the time).
 */
class SlidingPuzzle private constructor(val size: Int, private val cells: IntArray) {
    val tiles: List<Int> get() = cells.toList()
    val blankIndex: Int get() = cells.indexOf(0)

    val isSolved: Boolean get() = cells.indices.all { cells[it] == goalAt(it) }

    /** Tiles that sit on their final square, excluding the blank; drives the progress meter. */
    val correctCount: Int get() = cells.indices.count { cells[it] != 0 && cells[it] == goalAt(it) }
    val tileTotal: Int get() = size * size - 1

    private fun goalAt(index: Int): Int = if (index == size * size - 1) 0 else index + 1

    /** True when [index] holds a tile that shares a row or column with the blank, so tapping it slides something. */
    fun canSlide(index: Int): Boolean {
        val blank = blankIndex
        return index != blank && (index / size == blank / size || index % size == blank % size)
    }

    /** Slides the tile at [index] (and any tiles between it and the blank) one square toward the blank. */
    fun tap(index: Int): Boolean {
        if (!canSlide(index)) return false
        val blank = blankIndex
        val step = when {
            index / size == blank / size -> if (index < blank) 1 else -1
            else -> if (index < blank) size else -size
        }
        // Walk from the blank back towards the tapped tile, pulling each tile into the gap.
        var gap = blank
        while (gap != index) {
            cells[gap] = cells[gap - step]
            gap -= step
        }
        cells[index] = 0
        return true
    }

    /**
     * Swipe on the tile at [index] in direction ([dRow], [dCol]) (each -1, 0 or 1, one of them non-zero). Only slides
     * if the blank lies that way, so a swipe towards a wall does nothing.
     */
    fun swipe(index: Int, dRow: Int, dCol: Int): Boolean {
        if (!canSlide(index)) return false
        val blank = blankIndex
        val rowDelta = blank / size - index / size
        val colDelta = blank % size - index % size
        val towardBlank = (dRow != 0 && colDelta == 0 && rowDelta.sign() == dRow) || (dCol != 0 && rowDelta == 0 && colDelta.sign() == dCol)
        return towardBlank && tap(index)
    }

    private fun Int.sign(): Int = if (this > 0) 1 else if (this < 0) -1 else 0

    companion object {
        fun solved(size: Int): SlidingPuzzle = SlidingPuzzle(size, IntArray(size * size) { if (it == size * size - 1) 0 else it + 1 })

        /** A solvable, unsolved board reached by [moves] random legal moves from the solved one. */
        fun scrambled(size: Int, moves: Int, random: Random): SlidingPuzzle {
            val puzzle = solved(size)
            var previousBlank = -1
            var done = 0
            while (done < moves || puzzle.isSolved) {
                val blank = puzzle.blankIndex
                val options = neighbours(blank, size).filter { it != previousBlank }
                val next = options[random.nextInt(options.size)]
                puzzle.cells[blank] = puzzle.cells[next]
                puzzle.cells[next] = 0
                previousBlank = blank
                done++
            }
            return puzzle
        }

        fun forDifficulty(difficulty: Difficulty, random: Random): SlidingPuzzle = when (difficulty) {
            Difficulty.EASY -> scrambled(3, moves = 24, random = random)
            Difficulty.MEDIUM -> scrambled(3, moves = 150, random = random)
            Difficulty.HARD -> scrambled(4, moves = 220, random = random)
        }

        fun restore(size: Int, tiles: List<Int>): SlidingPuzzle {
            require(tiles.size == size * size && tiles.sorted() == List(size * size) { it }) { "Not a permutation of 0..${size * size - 1}" }
            return SlidingPuzzle(size, tiles.toIntArray())
        }

        private fun neighbours(index: Int, size: Int): List<Int> {
            val row = index / size
            val col = index % size
            return buildList {
                if (row > 0) add(index - size)
                if (row < size - 1) add(index + size)
                if (col > 0) add(index - 1)
                if (col < size - 1) add(index + 1)
            }
        }

        /**
         * Independent solvability test by the inversion-parity rule, used to verify the generator (and to reject a
         * corrupted saved board): odd widths need an even inversion count; even widths combine inversions with the
         * blank's row counted from the bottom.
         */
        fun isSolvable(tiles: List<Int>, size: Int): Boolean {
            val numbers = tiles.filter { it != 0 }
            var inversions = 0
            for (i in numbers.indices) for (j in i + 1 until numbers.size) if (numbers[i] > numbers[j]) inversions++
            if (size % 2 == 1) return inversions % 2 == 0
            val blankRowFromBottom = size - tiles.indexOf(0) / size
            return (inversions + blankRowFromBottom) % 2 == 1
        }

        /** Manhattan distance of every tile from its home; a lower bound on the moves left. */
        fun manhattanDistance(tiles: List<Int>, size: Int): Int = tiles.withIndex().sumOf { (index, tile) ->
            if (tile == 0) 0 else abs((tile - 1) / size - index / size) + abs((tile - 1) % size - index % size)
        }
    }
}
