package com.riyadm.wakenudge.update

/** Turns a release's markdown description into a short piece of plain text fit for a dialog. */
object ReleaseNotes {
    const val MAX_CHARS = 600

    /**
     * Only the start of a long description matters. Bounding the input and every line keeps the patterns below cheap
     * and their backtracking shallow, whatever a release description contains.
     */
    private const val MAX_INPUT = 6_000
    private const val MAX_LINE = 1_000
    private const val MAX_SPAN = 300

    private val HTML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val AUTOLINK = Regex("<(https?://[^>\\s]+)>")
    private val HTML_TAG = Regex("</?[A-Za-z][^>\\n]{0,200}>")
    private val FENCE = Regex("^\\s*(```|~~~).*$")
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+")
    private val HEADING_TAIL = Regex("\\s+#+\\s*$")
    private val QUOTE = Regex("^\\s*>+\\s?")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(\\[[ xX]]\\s+)?")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val CODE = Regex("`([^`]*)`")
    private val STRONG = Regex("(\\*\\*|__)(?=\\S)(.{1,$MAX_SPAN}?)(?<=\\S)\\1")
    private val STRIKE = Regex("~~(?=\\S)(.{1,$MAX_SPAN}?)(?<=\\S)~~")
    private val EMPHASIS = Regex("(?<![\\w*])([*_])(?=\\S)([^*_\\n]{1,$MAX_SPAN}?)(?<=\\S)\\1(?![\\w*])")
    private val ESCAPED = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|>~])")
    private val SPACES = Regex("[ \\t]+")
    private val BLANK_RUNS = Regex("\\n{3,}")

    private val ESCAPE_BASE = '\uE000'
    private val ESCAPE_END = '\uE07F'

    /** Bidirectional overrides and other invisible characters could dress a line up as something else. */
    private fun Char.isUnwantedControl(): Boolean =
        (isISOControl() && this != '\n' && this != '\t') || this in '\u202A'..'\u202E' || this in '\u2066'..'\u2069' ||
            this == '\u200B' || this == '\u200E' || this == '\u200F' || this == '\uFEFF'

    fun excerpt(markdown: String?): String {
        if (markdown.isNullOrBlank()) return ""
        // Private-use characters stand in for escaped markers while the patterns run; none may come from the input.
        var text = markdown.take(MAX_INPUT).replace("\r\n", "\n").replace('\r', '\n').filterNot { it in ESCAPE_BASE..ESCAPE_END }
        text = HTML_COMMENT.replace(text, "")
        text = AUTOLINK.replace(text, "$1")
        text = HTML_TAG.replace(text, "")
        val lines = text.lines().map { it.take(MAX_LINE) }.filterNot { FENCE.matches(it) }.map(::plainLine)
        text = lines.joinToString("\n")
        text = text.filterNot { it.isUnwantedControl() }
        text = text.map { if (it in ESCAPE_BASE..ESCAPE_END) (it.code - ESCAPE_BASE.code).toChar() else it }.joinToString("")
        text = SPACES.replace(text, " ").lines().joinToString("\n") { it.trim() }
        text = BLANK_RUNS.replace(text, "\n\n").trim()
        return cut(text)
    }

    /** Three or more of the same of `-`, `*` or `_`, spaces allowed between: a horizontal rule. */
    private fun isRule(line: String): Boolean {
        val marks = line.filterNot { it == ' ' || it == '\t' }
        return marks.length >= 3 && marks[0] in "-*_" && marks.all { it == marks[0] }
    }

    private fun plainLine(line: String): String {
        if (isRule(line)) return ""
        var s = HEADING_TAIL.replace(HEADING.replace(line, ""), "")
        s = QUOTE.replace(s, "")
        s = BULLET.replace(s) { "${it.groupValues[1]}• " }
        s = ESCAPED.replace(s) { (ESCAPE_BASE.code + it.groupValues[1][0].code).toChar().toString() }
        s = IMAGE.replace(s, "$1")
        s = LINK.replace(s, "$1")
        s = CODE.replace(s, "$1")
        s = STRONG.replace(s, "$2")
        s = STRIKE.replace(s, "$1")
        s = EMPHASIS.replace(s, "$2")
        return s
    }

    /** At most [MAX_CHARS] characters including the ellipsis, ending on a word where one is close by. */
    private fun cut(text: String): String {
        if (text.length <= MAX_CHARS) return text
        var end = MAX_CHARS - 1
        if (text[end - 1].isHighSurrogate()) end--
        val space = text.lastIndexOfAny(charArrayOf(' ', '\n'), end)
        if (space > end - 40) end = space
        return text.substring(0, end).trimEnd() + "…"
    }
}
