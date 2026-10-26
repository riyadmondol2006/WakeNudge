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

    @Test fun `easy is one-digit sums and subtractions within ten`() {
        val all = problems(Difficulty.EASY)
        for (problem in all) {
            val evaluator = ExpressionEvaluator(problem.expression).also { it.evaluate() }
            assertEquals(problem.expression, 2, evaluator.operands.size)
            assertEquals(1, evaluator.operatorCount)
            assertTrue(!evaluator.usedMultiplication && !evaluator.usedDivision)
            assertTrue(problem.expression, evaluator.operands.all { it in 2..10 })
            assertTrue(problem.expression, problem.answer <= 18)
        }
        assertTrue("some easy questions add", all.any { '+' in it.expression })
        assertTrue("some easy questions subtract", all.any { '−' in it.expression })
    }

    @Test fun `medium is a two-digit number plus or minus one digit, or the two to five times tables`() {
        val all = problems(Difficulty.MEDIUM)
        for (problem in all) {
            val evaluator = ExpressionEvaluator(problem.expression).also { it.evaluate() }
            assertEquals(problem.expression, 2, evaluator.operands.size)
            assertEquals(1, evaluator.operatorCount)
            assertTrue(!evaluator.usedDivision)
            val (a, b) = evaluator.operands
            if (evaluator.usedMultiplication) {
                assertTrue(problem.expression, a in 2..9 && b in 2..9 && minOf(a, b) <= 5)
            } else {
                assertTrue(problem.expression, a in 11..49 && b in 2..9)
            }
            assertTrue("never more than two digits to type: ${problem.expression}", problem.answer <= 58)
        }
        assertTrue("times tables appear", all.any { '×' in it.expression })
    }

    @Test fun `hard uses two-digit sums, the full times tables, or a times table plus a number, never division`() {
        val all = problems(Difficulty.HARD)
        for (problem in all) {
            val evaluator = ExpressionEvaluator(problem.expression).also { it.evaluate() }
            assertTrue(problem.expression, evaluator.operands.size in 2..3)
            assertTrue(!evaluator.usedDivision)
            assertTrue(problem.expression, '(' !in problem.expression)
            assertTrue(problem.expression, evaluator.operands.all { it in 2..99 })
            assertTrue(problem.expression, problem.answer <= 130)
            if (evaluator.operands.size == 3) assertTrue("a times table plus a number", evaluator.usedMultiplication)
        }
        assertTrue("some hard questions have two steps", all.any { ExpressionEvaluator(it.expression).also { e -> e.evaluate() }.operands.size == 3 })
    }

    @Test fun `each level is easier than the next on average`() {
        fun meanAnswer(d: Difficulty) = problems(d).map { it.answer }.average()
        fun meanOperators(d: Difficulty) = problems(d).map { ExpressionEvaluator(it.expression).also { e -> e.evaluate() }.operatorCount }.average()
        assertTrue(meanAnswer(Difficulty.EASY) < meanAnswer(Difficulty.MEDIUM))
        assertTrue(meanAnswer(Difficulty.MEDIUM) < meanAnswer(Difficulty.HARD))
        assertTrue(meanOperators(Difficulty.MEDIUM) <= meanOperators(Difficulty.HARD))
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
