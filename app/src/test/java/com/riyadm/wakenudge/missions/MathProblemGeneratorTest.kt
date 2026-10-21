package com.riyadm.wakenudge.missions

import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.missions.math.MathProblem
import com.riyadm.wakenudge.missions.math.MathProblemGenerator
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Evaluates a generated expression independently, insisting that every division is exact. */
private class ExpressionEvaluator(private val text: String) {
    private var pos = 0
    val operands = mutableListOf<Int>()
    var usedDivision = false
    var usedMultiplication = false
    var operatorCount = 0

    fun evaluate(): Int {
        val value = expression()
        assertEquals("Trailing input in '$text'", text.length, pos)
        return value
    }

    private fun peek(): Char? = text.getOrNull(pos)
    private fun skipSpaces() { while (peek() == ' ') pos++ }

    private fun expression(): Int {
        var value = term()
        while (true) {
            skipSpaces()
            when (peek()) {
                '+' -> { pos++; operatorCount++; value += term() }
                '−' -> { pos++; operatorCount++; value -= term() }
                else -> return value
            }
        }
    }

    private fun term(): Int {
        var value = factor()
        while (true) {
            skipSpaces()
            when (peek()) {
                '×' -> { pos++; operatorCount++; usedMultiplication = true; value *= factor() }
                '÷' -> {
                    pos++; operatorCount++; usedDivision = true
                    val divisor = factor()
                    assertEquals("Inexact division in '$text'", 0, value % divisor)
                    value /= divisor
                }
                else -> return value
            }
        }
    }

    private fun factor(): Int {
        skipSpaces()
        if (peek() == '(') {
            pos++
            val inner = expression()
            skipSpaces()
            assertEquals(')', peek())
            pos++
            return inner
        }
        val start = pos
        while (peek()?.isDigit() == true) pos++
        val number = text.substring(start, pos).toInt()
        operands += number
        return number
    }
}

class MathProblemGeneratorTest {
    private fun problems(difficulty: Difficulty, seeds: Int = 400, perSeed: Int = 5): List<MathProblem> =
        (0 until seeds).flatMap { seed -> MathProblemGenerator(Random(seed)).let { g -> List(perSeed) { g.next(difficulty) } } }

    @Test fun `stated answer always equals the evaluated expression for every difficulty`() {
        for (difficulty in Difficulty.entries) for (problem in problems(difficulty)) {
            assertEquals(problem.expression, problem.answer, ExpressionEvaluator(problem.expression).evaluate())
        }
    }

    @Test fun `answers are never negative or trivial and no operand is 0 or 1`() {
        for (difficulty in Difficulty.entries) for (problem in problems(difficulty)) {
            assertTrue("${problem.expression} = ${problem.answer}", problem.answer >= 2)
            val evaluator = ExpressionEvaluator(problem.expression).also { it.evaluate() }
            assertTrue(problem.expression, evaluator.operands.all { it >= 2 })
        }
    }

    @Test fun `easy is two two-digit operands joined by plus or minus`() {
        for (problem in problems(Difficulty.EASY)) {
            val evaluator = ExpressionEvaluator(problem.expression).also { it.evaluate() }
            assertEquals(problem.expression, 2, evaluator.operands.size)
            assertTrue(problem.expression, evaluator.operands.all { it in 10..99 })
            assertEquals(1, evaluator.operatorCount)
            assertTrue(!evaluator.usedMultiplication && !evaluator.usedDivision)
        }
    }

    @Test fun `medium is a two-digit times one-digit then plus or minus a two-digit number`() {
        for (problem in problems(Difficulty.MEDIUM)) {
            val evaluator = ExpressionEvaluator(problem.expression).also { it.evaluate() }
            assertEquals(problem.expression, 3, evaluator.operands.size)
            val (a, b, c) = evaluator.operands
            assertTrue(problem.expression, a in 12..99 && b in 2..9 && c in 10..99)
            assertTrue(evaluator.usedMultiplication && !evaluator.usedDivision)
        }
    }

    @Test fun `hard has three terms mixing multiplication and exact division in some questions`() {
        val all = problems(Difficulty.HARD)
        var divisions = 0
        for (problem in all) {
            val evaluator = ExpressionEvaluator(problem.expression).also { it.evaluate() }
            assertEquals(problem.expression, 3, evaluator.operands.size)
            assertEquals(2, evaluator.operatorCount)
            assertTrue(evaluator.usedMultiplication)
            if (evaluator.usedDivision) divisions++
        }
        assertTrue("division appears in a healthy share of hard questions ($divisions of ${all.size})", divisions > all.size / 5)
    }

    @Test fun `consecutive questions differ`() {
        for (difficulty in Difficulty.entries) {
            val generator = MathProblemGenerator(Random(7))
            var previous = generator.next(difficulty)
            repeat(200) {
                val next = generator.next(difficulty)
                assertNotEquals(previous.expression, next.expression)
                previous = next
            }
        }
    }

    @Test fun `same seed gives the same sequence`() {
        val a = MathProblemGenerator(Random(99))
        val b = MathProblemGenerator(Random(99))
        repeat(20) { assertEquals(a.next(Difficulty.HARD), b.next(Difficulty.HARD)) }
    }
}
