package com.riyadm.wakenudge.missions.typing

import com.riyadm.wakenudge.domain.model.Difficulty

/**
 * What the user has typed so far, compared with the target phrase.
 *
 * @property typedCorrect one flag per character of the raw typed text; leading and trailing whitespace is never
 * marked wrong because mobile keyboards add stray spaces after suggestions.
 * @property coveredPrefix how many leading characters of the target have been typed correctly, up to the first mistake.
 */
data class TypingAnalysis(
    val typedCorrect: List<Boolean>,
    val coveredPrefix: Int,
    val hasMistake: Boolean,
    val isComplete: Boolean,
)

/**
 * Comparison rules for the typing mission: HARD is case-sensitive, EASY and MEDIUM ignore case. Surrounding whitespace
 * is always ignored, and so is the typographic form of a mark the user cannot choose: many keyboards type a curly
 * apostrophe, a long dash or a non-breaking space by themselves, and that must not make a phrase impossible to finish.
 */
class TypingValidator(val difficulty: Difficulty) {
    val caseSensitive: Boolean get() = difficulty == Difficulty.HARD

    private fun same(a: Char, b: Char): Boolean {
        val x = plain(a)
        val y = plain(b)
        return if (caseSensitive) x == y else x.lowercaseChar() == y.lowercaseChar()
    }

    private fun plain(c: Char): Char = when (c) {
        '\u2018', '\u2019', '\u201A', '\u201B', '\u02BC', '\u2032', '`', '\u00B4' -> '\''
        '\u201C', '\u201D', '\u201E', '\u201F', '\u2033' -> '"'
        '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2212' -> '-'
        '\u00A0', '\u2007', '\u202F' -> ' '
        else -> c
    }

    fun isCorrect(target: String, typed: String): Boolean = analyze(target, typed).isComplete

    fun analyze(target: String, typed: String): TypingAnalysis {
        val goal = target.trim()
        val lead = typed.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) typed.length else it }
        val end = typed.trimEnd().length.coerceAtLeast(lead)
        val flags = ArrayList<Boolean>(typed.length)
        var mistakes = 0
        var covered = 0
        var stillMatching = true
        for (i in typed.indices) {
            if (i < lead || i >= end) {
                flags += true
                continue
            }
            val j = i - lead
            val ok = j < goal.length && same(typed[i], goal[j])
            flags += ok
            if (!ok) mistakes++
            if (ok && stillMatching) covered = j + 1 else stillMatching = false
        }
        val body = end - lead
        return TypingAnalysis(flags, covered, mistakes > 0, mistakes == 0 && body == goal.length)
    }

    companion object {
        /** Picks [count] distinct phrases at random (repeating only if the pool is smaller than [count]). */
        fun pickPhrases(pool: List<String>, count: Int, random: kotlin.random.Random): List<String> {
            if (pool.isEmpty()) return emptyList()
            val shuffled = pool.shuffled(random)
            return List(count) { shuffled[it % shuffled.size] }
        }
    }
}
