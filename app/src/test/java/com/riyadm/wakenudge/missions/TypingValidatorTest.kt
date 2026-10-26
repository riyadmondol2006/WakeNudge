package com.riyadm.wakenudge.missions

import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.missions.typing.TypingValidator
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingValidatorTest {
    private val easy = TypingValidator(Difficulty.EASY)
    private val medium = TypingValidator(Difficulty.MEDIUM)
    private val hard = TypingValidator(Difficulty.HARD)

    @Test fun `easy and medium ignore case`() {
        assertTrue(easy.isCorrect("good morning sunshine", "Good Morning SUNSHINE"))
        assertTrue(medium.isCorrect("Rise and shine, the day is waiting", "rise and shine, the day is waiting"))
    }

    @Test fun `hard is case sensitive`() {
        assertFalse(hard.isCorrect("Hello, World.", "hello, world."))
        assertTrue(hard.isCorrect("Hello, World.", "Hello, World."))
    }

    @Test fun `surrounding whitespace is ignored but inner characters must match`() {
        assertTrue(hard.isCorrect("Wake up!", "  Wake up! \n"))
        assertFalse(hard.isCorrect("Wake up!", "Wake  up!"))
        assertFalse(easy.isCorrect("good morning", "goodmorning"))
    }

    @Test fun `a prefix or an overlong entry is not complete`() {
        assertFalse(easy.isCorrect("good morning", "good morni"))
        assertFalse(easy.isCorrect("good morning", "good morning!"))
        assertFalse(easy.isCorrect("good morning", ""))
    }

    @Test fun `analysis marks the first wrong character and counts the correct prefix`() {
        val a = easy.analyze("good morning", "goox mo")
        assertEquals(listOf(true, true, true, false, true, true, true), a.typedCorrect)
        assertEquals(3, a.coveredPrefix)
        assertTrue(a.hasMistake)
        assertFalse(a.isComplete)
    }

    @Test fun `correct typing so far has no mistakes and full prefix coverage`() {
        val a = hard.analyze("Hello there", "Hello t")
        assertFalse(a.hasMistake)
        assertEquals(7, a.coveredPrefix)
        assertTrue(a.typedCorrect.all { it })
        assertFalse(a.isComplete)
        assertTrue(hard.analyze("Hello there", "Hello there").isComplete)
    }

    @Test fun `wrong case is a mistake only on hard`() {
        assertTrue(hard.analyze("Hello", "hello").hasMistake)
        assertFalse(medium.analyze("Hello", "hello").hasMistake)
    }

    @Test fun `typing beyond the phrase is flagged`() {
        val a = easy.analyze("abc", "abcd")
        assertEquals(listOf(true, true, true, false), a.typedCorrect)
        assertTrue(a.hasMistake)
        assertFalse(a.isComplete)
    }

    @Test fun `leading and trailing spaces are never flagged`() {
        val a = easy.analyze("abc", " ab ")
        assertTrue(a.typedCorrect.all { it })
        assertEquals(2, a.coveredPrefix)
        assertFalse(a.hasMistake)
    }

    @Test fun `phrase picking gives distinct phrases while the pool allows`() {
        val pool = List(10) { "phrase $it" }
        val picked = TypingValidator.pickPhrases(pool, 4, Random(1))
        assertEquals(4, picked.size)
        assertEquals(4, picked.toSet().size)
        assertTrue(picked.all { it in pool })
        assertEquals(12, TypingValidator.pickPhrases(pool.take(3), 12, Random(1)).size)
        assertTrue(TypingValidator.pickPhrases(emptyList(), 3, Random(1)).isEmpty())
    }

    @Test fun `a keyboard that types curly quotes, long dashes or a non-breaking space can still finish`() {
        val hard = TypingValidator(Difficulty.HARD)
        assertTrue(hard.isCorrect("Today's list: wake up.", "Today\u2019s list: wake up."))
        assertTrue(hard.isCorrect("Say \"Hello\" now", "Say \u201CHello\u201D now"))
        assertTrue(hard.isCorrect("brand-new day", "brand\u2013new day"))
        assertTrue(TypingValidator(Difficulty.MEDIUM).isCorrect("open the window", "open\u00A0the window"))
        // Only the form of the mark is forgiven; a different letter or a missing mark is still a mistake.
        assertTrue(!hard.isCorrect("Today's list", "Todays list"))
        assertTrue(!hard.isCorrect("brand-new day", "brand new day"))
    }
}
