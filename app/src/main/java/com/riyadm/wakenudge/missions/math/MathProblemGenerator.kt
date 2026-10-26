package com.riyadm.wakenudge.missions.math

import com.riyadm.wakenudge.domain.model.Difficulty
import kotlin.random.Random

/** One arithmetic question. [expression] is display-ready (uses × and −), [answer] is always a positive integer. */
data class MathProblem(val expression: String, val answer: Int)

/**
 * Generates wake-up arithmetic for someone who has just been woken: enough to make the brain start, never a test.
 *
 * - [Difficulty.EASY]: one-digit sums and small subtractions, like 7 + 5 or 9 − 4.
 * - [Difficulty.MEDIUM]: a two-digit number and a one-digit number, or the 2 to 5 times tables, like 27 + 6 or 7 × 4.
 * - [Difficulty.HARD]: two two-digit numbers, the full times tables, or a times table plus a number, like 38 + 47 or
 *   7 × 8 + 23.
 *
 * Every operand is at least 2 (no "× 1" or "+ 0" freebies) and every answer is a whole number of at least 2. Takes the
 * [Random] so tests (and saved state) can reproduce a sequence from a seed.
 */
class MathProblemGenerator(private val random: Random = Random.Default) {
    private var lastExpression: String? = null

    fun next(difficulty: Difficulty): MathProblem {
        var problem = build(difficulty)
        // A repeated question would let a sleepy user re-submit the previous answer.
        var attempts = 0
        while (problem.expression == lastExpression && attempts++ < 10) problem = build(difficulty)
        lastExpression = problem.expression
        return problem
    }

    private fun build(difficulty: Difficulty): MathProblem = when (difficulty) {
        Difficulty.EASY -> easy()
        Difficulty.MEDIUM -> medium()
        Difficulty.HARD -> hard()
    }

    private fun easy(): MathProblem {
        if (random.nextInt(3) < 2) {
            val a = random.nextInt(2, 10)
            val b = random.nextInt(2, 10)
            return MathProblem("$a $PLUS $b", a + b)
        }
        // Subtraction stays within 10, so there is nothing to borrow: 10 − 3, 8 − 5.
        val answer = random.nextInt(2, 9)
        val b = random.nextInt(2, 11 - answer)
        return MathProblem("${answer + b} $MINUS $b", answer)
    }

    private fun medium(): MathProblem = when (random.nextInt(3)) {
        0 -> {
            val a = random.nextInt(11, 50)
            val b = random.nextInt(2, 10)
            MathProblem("$a $PLUS $b", a + b)
        }
        1 -> {
            val a = random.nextInt(12, 50)
            val b = random.nextInt(2, 10)
            MathProblem("$a $MINUS $b", a - b)
        }
        else -> {
            val a = random.nextInt(2, 10)
            val b = random.nextInt(2, 6)
            if (random.nextBoolean()) MathProblem("$a $TIMES $b", a * b) else MathProblem("$b $TIMES $a", a * b)
        }
    }

    private fun hard(): MathProblem = when (random.nextInt(4)) {
        0 -> {
            // Two two-digit numbers whose sum stays below 100.
            val a = random.nextInt(11, 80)
            val b = random.nextInt(11, 100 - a)
            MathProblem("$a $PLUS $b", a + b)
        }
        1 -> {
            val a = random.nextInt(30, 100)
            val b = random.nextInt(11, a - 1)
            MathProblem("$a $MINUS $b", a - b)
        }
        2 -> {
            val a = random.nextInt(3, 10)
            val b = random.nextInt(3, 10)
            MathProblem("$a $TIMES $b", a * b)
        }
        else -> {
            val a = random.nextInt(2, 10)
            val b = random.nextInt(2, 10)
            val c = random.nextInt(10, 50)
            MathProblem("$a $TIMES $b $PLUS $c", a * b + c)
        }
    }

    companion object {
        const val TIMES = '×'
        const val MINUS = '−'
        const val PLUS = '+'
    }
}
