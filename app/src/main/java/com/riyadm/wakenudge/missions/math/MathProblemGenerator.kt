package com.riyadm.wakenudge.missions.math

import com.riyadm.wakenudge.domain.model.Difficulty
import kotlin.random.Random

/** One arithmetic question. [expression] is display-ready (uses × ÷ −), [answer] is always a positive integer. */
data class MathProblem(val expression: String, val answer: Int)

/**
 * Generates wake-up arithmetic that is awkward to do half asleep but never unfair: every operand is at least 2
 * (no "x1" or "+0" freebies), divisions are exact, and the answer is never negative. Takes the [Random] so tests
 * (and saved state) can reproduce a sequence from a seed.
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
        val a = random.nextInt(10, 100)
        val b = random.nextInt(10, 100)
        if (random.nextBoolean()) return MathProblem("$a $PLUS $b", a + b)
        val high = maxOf(a, b)
        val low = minOf(a, b)
        // Equal or adjacent operands would give 0 or 1, which is trivial: draw again.
        if (high - low < 2) return easy()
        return MathProblem("$high $MINUS $low", high - low)
    }

    private fun medium(): MathProblem {
        val a = random.nextInt(12, 100)
        val b = random.nextInt(2, 10)
        val product = a * b
        return if (random.nextBoolean()) {
            val c = random.nextInt(10, 100)
            MathProblem("$a $TIMES $b $PLUS $c", product + c)
        } else {
            // product >= 24, so the upper bound always leaves at least 10..22 to choose from.
            val c = random.nextInt(10, minOf(99, product - 2) + 1)
            MathProblem("$a $TIMES $b $MINUS $c", product - c)
        }
    }

    private fun hard(): MathProblem = when (random.nextInt(5)) {
        0 -> {
            val a = random.nextInt(12, 50)
            val b = random.nextInt(3, 10)
            val c = random.nextInt(11, minOf(99, a * b - 2) + 1)
            MathProblem("($a $TIMES $b) $MINUS $c", a * b - c)
        }
        1 -> {
            val a = random.nextInt(12, 50)
            val b = random.nextInt(3, 10)
            val c = random.nextInt(11, 100)
            MathProblem("($a $TIMES $b) $PLUS $c", a * b + c)
        }
        2 -> {
            val divisor = random.nextInt(2, 10)
            val quotient = random.nextInt(6, 31)
            val c = random.nextInt(3, 10)
            MathProblem("(${divisor * quotient} $DIVIDE $divisor) $TIMES $c", quotient * c)
        }
        3 -> {
            // (a x b) / c with c dividing a, and b != c so the answer is not simply "a".
            val c = random.nextInt(2, 10)
            val k = random.nextInt((12 + c - 1) / c, 99 / c + 1)
            var b = random.nextInt(3, 10)
            while (b == c) b = random.nextInt(3, 10)
            MathProblem("(${c * k} $TIMES $b) $DIVIDE $c", k * b)
        }
        else -> {
            val b = random.nextInt(3, 10)
            val c = random.nextInt(11, 30)
            val remainder = random.nextInt(11, 100)
            MathProblem("${b * c + remainder} $MINUS ($b $TIMES $c)", remainder)
        }
    }

    companion object {
        const val TIMES = '×'
        const val DIVIDE = '÷'
        const val MINUS = '−'
        const val PLUS = '+'
    }
}
