package com.riyadm.wakenudge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {

    private fun plain(markdown: String?) = ReleaseNotes.excerpt(markdown)

    @Test fun `nothing in gives nothing out`() {
        assertEquals("", plain(null))
        assertEquals("", plain(""))
        assertEquals("", plain("   \n\t\n"))
        assertEquals("", plain("<!-- only a comment -->"))
    }

    @Test fun `plain text passes through untouched`() {
        assertEquals("Fixes the snooze button.", plain("Fixes the snooze button."))
        assertEquals("One line.\nSecond line.", plain("One line.\nSecond line."))
    }

    @Test fun `headings lose their hashes`() {
        assertEquals("What's Changed\nBetter alarms", plain("## What's Changed\n# Better alarms ##"))
        assertEquals("not a heading #1", plain("not a heading #1"))
    }

    @Test fun `bold italic strike and code lose their markers`() {
        assertEquals("a bold word", plain("a **bold** word"))
        assertEquals("a bold word", plain("a __bold__ word"))
        assertEquals("an italic word", plain("an *italic* word"))
        assertEquals("an italic word", plain("an _italic_ word"))
        assertEquals("a struck word", plain("a ~~struck~~ word"))
        assertEquals("call start() now", plain("call `start()` now"))
        assertEquals("snake_case_name and 2 * 3 * 4", plain("snake_case_name and 2 * 3 * 4"))
    }

    @Test fun `links keep their text and drop their address`() {
        assertEquals("See the guide for details", plain("See [the guide](https://example.com/guide) for details"))
        assertEquals("A screenshot", plain("![A screenshot](https://example.com/a.png)"))
        assertEquals("https://github.com/riyadmondol2006/WakeNudge/pull/12", plain("<https://github.com/riyadmondol2006/WakeNudge/pull/12>"))
    }

    @Test fun `lists become bullets and numbers stay`() {
        assertEquals("• one\n• two\n• three", plain("- one\n* two\n+ three"))
        assertEquals("• done\n• todo", plain("- [x] done\n- [ ] todo"))
        assertEquals("1. first\n2. second", plain("1. first\n2. second"))
        assertEquals("nested items are flattened", "• top\n• nested", plain("- top\n  - nested"))
    }

    @Test fun `quotes rules fences and html are removed`() {
        assertEquals("quoted line", plain("> quoted line"))
        assertEquals("above\n\nbelow", plain("above\n\n---\n\nbelow"))
        assertEquals("val x = 1", plain("```kotlin\nval x = 1\n```"))
        assertEquals("Hello world", plain("<p>Hello <b>world</b></p>"))
        assertEquals("before after", plain("before <!-- hidden --> after"))
        assertEquals("1 < 2 and 3 > 2", plain("1 < 2 and 3 > 2"))
    }

    @Test fun `escaped markdown characters show as themselves`() {
        assertEquals("1.2 * 3 [x] _y_", plain("1\\.2 \\* 3 \\[x\\] \\_y\\_"))
    }

    @Test fun `windows and old mac line endings are normalised and blank runs collapse`() {
        assertEquals("a\nb\nc", plain("a\r\nb\rc"))
        assertEquals("a\n\nb", plain("a\n\n\n\n\nb"))
        assertEquals("a b", plain("a    \t   b"))
    }

    @Test fun `invisible and direction-changing characters are dropped`() {
        assertEquals("safe text", plain("safe\u202E text\u202C\u2066\u2069\u200B\uFEFF\u0007"))
    }

    @Test fun `a generated changelog reads cleanly`() {
        val body = """
            ## What's Changed
            * Fix alarm drifting after DST by @someone in https://github.com/riyadmondol2006/WakeNudge/pull/41
            * **New:** shake mission by @other in https://github.com/riyadmondol2006/WakeNudge/pull/42

            **Full Changelog**: https://github.com/riyadmondol2006/WakeNudge/compare/v1.0.41...v1.0.42
        """.trimIndent()
        assertEquals(
            "What's Changed\n" +
                "• Fix alarm drifting after DST by @someone in https://github.com/riyadmondol2006/WakeNudge/pull/41\n" +
                "• New: shake mission by @other in https://github.com/riyadmondol2006/WakeNudge/pull/42\n\n" +
                "Full Changelog: https://github.com/riyadmondol2006/WakeNudge/compare/v1.0.41...v1.0.42",
            plain(body),
        )
    }

    @Test fun `text up to the limit is kept whole`() {
        val exact = "a".repeat(ReleaseNotes.MAX_CHARS)
        assertEquals(exact, plain(exact))
    }

    @Test fun `longer text is cut within the limit and marked with an ellipsis`() {
        val cut = plain("word ".repeat(400))
        assertTrue(cut.length <= ReleaseNotes.MAX_CHARS)
        assertTrue(cut.endsWith("…"))
        assertFalse("ends on a whole word", cut.removeSuffix("…").endsWith("wor"))
    }

    @Test fun `an unbroken run is cut hard at the limit`() {
        val cut = plain("x".repeat(5_000))
        assertEquals(ReleaseNotes.MAX_CHARS, cut.length)
        assertTrue(cut.endsWith("…"))
    }

    @Test fun `a cut never splits an emoji`() {
        val text = "a".repeat(ReleaseNotes.MAX_CHARS - 2) + "😀😀😀"
        val cut = plain(text)
        assertTrue(cut.length <= ReleaseNotes.MAX_CHARS)
        assertFalse("no lone surrogate before the ellipsis", cut.removeSuffix("…").last().isHighSurrogate())
    }

    @Test fun `enormous or hostile input is handled quickly`() {
        val tokens = listOf("*", "_", "[", "](", "<", "<!--", "`", "~", "-", "#", ">", "\\", "! ", "** ", "- [", "<a ", "    ", "\n")
        for (token in tokens) {
            for (shape in listOf(token.repeat(200_000), token.repeat(7_000) + "x", ("a" + token).repeat(3_000))) {
                val started = System.nanoTime()
                val out = plain(shape)
                val millis = (System.nanoTime() - started) / 1_000_000
                assertTrue("'$token' took $millis ms", millis < 2_000)
                assertTrue(out.length <= ReleaseNotes.MAX_CHARS)
            }
        }
    }

    @Test fun `nested and unbalanced markers do not throw`() {
        listOf("**bold *nested* bold**", "**unclosed", "[unclosed](", "![](", "`unclosed", "~~", "***", "___", "[a](b](c)", "<", "<a", "\\")
            .forEach { plain(it) }
    }
}
