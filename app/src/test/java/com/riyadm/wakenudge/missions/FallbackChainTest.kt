package com.riyadm.wakenudge.missions

import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.missions.math.MathProblemGenerator
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backup mission and the way-out chain: the right length for every cause at every level, longer and never harder. */
class FallbackChainTest {
    /** The section 2 settings that size a chain, one per level. */
    private val levels = mapOf(
        "Easy" to AntiOversleepSettings(wayOutCount = 10, fallbackCount = 3),
        "Normal" to AntiOversleepSettings(wayOutCount = 10, fallbackCount = 3),
        "Hard" to AntiOversleepSettings(emergencyStopEnabled = false, wayOutDelayMinutes = 3, wayOutCount = 10, fallbackCount = 5),
        "No mercy" to AntiOversleepSettings(emergencyStopEnabled = false, wayOutDelayMinutes = 10, wayOutCount = 20, fallbackCount = 8),
    )

    @Test fun `every reason at every level gets a chain of the decided length`() {
        val expectedBadLuck = mapOf("Easy" to 3, "Normal" to 3, "Hard" to 5, "No mercy" to 8)
        val expectedChoice = mapOf("Easy" to 10, "Normal" to 10, "Hard" to 10, "No mercy" to 20)
        for ((level, ao) in levels) for (reason in FallbackReason.entries) {
            val steps = FallbackChain.backup(reason, ao)
            val expected = if (reason.userCaused) expectedChoice.getValue(level) else expectedBadLuck.getValue(level)
            assertEquals("$level, $reason", expected, steps.size)
            assertTrue("$level, $reason: every step is one task", steps.all { it.count == 1 })
        }
    }

    @Test fun `only a refused permission and NFC switched off count as something the user could fix`() {
        assertEquals(setOf(FallbackReason.PERMISSION_REFUSED, FallbackReason.NFC_OFF), FallbackReason.entries.filter { it.userCaused }.toSet())
    }

    @Test fun `whatever a restored file says, a chain asks for something and never for ever`() {
        for (count in listOf(Int.MIN_VALUE, -5, 0, 1, 7, 20, 21, 999, Int.MAX_VALUE)) {
            val ao = AntiOversleepSettings(wayOutCount = count, fallbackCount = count)
            for (reason in FallbackReason.entries) {
                val n = FallbackChain.backup(reason, ao).size
                assertTrue("$count, $reason gave $n", n in 1..30)
            }
            assertTrue(FallbackChain.wayOut(ao).size in 3..30)
        }
    }

    @Test fun `a mixed chain takes Focus Tap, typing and sums in turn`() {
        val steps = FallbackChain.steps(7).map { it.type }
        assertEquals(
            listOf(MissionType.FOCUS_TAP, MissionType.TYPING, MissionType.MATH, MissionType.FOCUS_TAP, MissionType.TYPING, MissionType.MATH, MissionType.FOCUS_TAP),
            steps,
        )
        assertEquals("an offset only moves where the turn starts", MissionType.TYPING, FallbackChain.steps(3, offset = 1).first().type)
        assertEquals(MissionType.FOCUS_TAP, FallbackChain.steps(3, offset = -3).first().type)
    }

    @Test fun `the typing-only and the untimed styles hold only what they say`() {
        assertTrue(FallbackChain.steps(10, ChainStyle.TYPING_ONLY).all { it.type == MissionType.TYPING })
        val untimed = FallbackChain.steps(10, ChainStyle.SUMS_AND_TYPING).map { it.type }.toSet()
        assertEquals(setOf(MissionType.MATH, MissionType.TYPING), untimed)
    }

    @Test fun `no step needs hardware, a permission or a setup, so the backup cannot fail in turn`() {
        for (style in ChainStyle.entries) for (step in FallbackChain.steps(30, style)) {
            assertFalse(step.type.needsSetup)
            assertTrue(step.isConfigured)
            assertTrue(step.type in setOf(MissionType.FOCUS_TAP, MissionType.TYPING, MissionType.MATH))
        }
    }

    @Test fun `no step is harder than today's hard sums`() {
        for (style in ChainStyle.entries) for (step in FallbackChain.steps(30, style)) {
            assertTrue(step.difficulty.ordinal < Difficulty.HARD.ordinal)
        }
    }

    /** The sums a chain asks for, over many seeds: never a multiplication with a multi-digit number. */
    @Test fun `a chain's sums never multiply a multi-digit number`() {
        val mathSteps = FallbackChain.steps(30).filter { it.type == MissionType.MATH }
        assertTrue(mathSteps.isNotEmpty())
        for (seed in 0 until 2_000) {
            val generator = MathProblemGenerator(Random(seed))
            for (step in mathSteps) {
                val problem = generator.next(step.difficulty)
                if (MathProblemGenerator.TIMES in problem.expression) {
                    val factors = problem.expression.split(MathProblemGenerator.TIMES).map { it.trim().takeWhile(Char::isDigit) }
                    assertTrue("${problem.expression} multiplies a multi-digit number", factors.all { it.length == 1 })
                }
            }
        }
    }
}
