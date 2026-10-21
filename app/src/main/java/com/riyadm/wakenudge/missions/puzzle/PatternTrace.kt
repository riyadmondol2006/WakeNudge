package com.riyadm.wakenudge.missions.puzzle

import com.riyadm.wakenudge.domain.model.Difficulty
import kotlin.math.abs
import kotlin.random.Random

/** Geometry of the 3x3 dot grid, nodes numbered 0..8 row by row. */
object PatternGrid {
    const val SIDE = 3
    const val NODES = SIDE * SIDE

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Nodes lying exactly on the straight segment between [from] and [to] (e.g. 1 between 0 and 2), in travel order. */
    fun between(from: Int, to: Int): List<Int> {
        val dRow = to / SIDE - from / SIDE
        val dCol = to % SIDE - from % SIDE
        val steps = gcd(abs(dRow), abs(dCol))
        return (1 until steps).map { k -> (from / SIDE + dRow / steps * k) * SIDE + from % SIDE + dCol / steps * k }
    }
}

/**
 * Builds the pattern the user has to trace: a path of distinct dots where no segment runs over another dot, so every
 * pattern has exactly one way to be drawn (unlike lock-screen patterns, where passing over a dot silently adds it).
 */
object PatternGenerator {
    fun generate(length: Int, random: Random): List<Int> {
        require(length in 2..PatternGrid.NODES)
        repeat(50) {
            val path = extend(mutableListOf(random.nextInt(PatternGrid.NODES)), length, random)
            if (path != null) return path
        }
        // Practically unreachable (a perimeter walk always exists), but keep a valid answer rather than failing.
        return listOf(0, 1, 2, 5, 8, 7, 6, 3, 4).take(length)
    }

    private fun extend(path: MutableList<Int>, length: Int, random: Random): List<Int>? {
        if (path.size == length) return path.toList()
        val options = (0 until PatternGrid.NODES).filter { it !in path && PatternGrid.between(path.last(), it).isEmpty() }.shuffled(random)
        for (node in options) {
            path += node
            extend(path, length, random)?.let { return it }
            path.removeAt(path.lastIndex)
        }
        return null
    }

    fun lengthFor(difficulty: Difficulty): Int = when (difficulty) {
        Difficulty.EASY -> 4
        Difficulty.MEDIUM -> 6
        Difficulty.HARD -> 8
    }
}

/**
 * Records the dots a finger passes over while drawing. A fast swipe can jump straight over a dot between two touch
 * events, so any untouched dot lying on the segment is added too, as the user's stroke really crossed it. The result
 * only matches the target when it is the same dots in the same direction.
 */
class PatternTracer(private val target: List<Int>) {
    private val path = mutableListOf<Int>()

    val visited: List<Int> get() = path.toList()
    val isEmpty: Boolean get() = path.isEmpty()

    /** Registers that the finger is now over [node]; returns true if the path changed. */
    fun enter(node: Int): Boolean {
        if (node in path) return false
        if (path.isNotEmpty()) PatternGrid.between(path.last(), node).forEach { if (it !in path) path += it }
        path += node
        return true
    }

    fun matches(): Boolean = path == target

    /** True while everything drawn so far is a correct beginning of the target; used to colour the stroke. */
    val isOnTrack: Boolean get() = path.size <= target.size && path == target.take(path.size)

    fun clear() = path.clear()
}
