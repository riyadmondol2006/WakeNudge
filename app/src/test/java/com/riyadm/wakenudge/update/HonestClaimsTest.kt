package com.riyadm.wakenudge.update

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WakeNudge makes getting back to sleep hard, not impossible: power off isn't blocked, Safe mode and a flat battery beat
 * it, and every alarm keeps Emergency. This keeps the app's text, the README and the store listing from promising more.
 * Nothing may say the alarm can't be escaped, and the README must keep its "What it can't stop" section.
 */
class HonestClaimsTest {

    // Unit tests run with the module directory (app/) as the working directory.
    private val root = File("..")

    /** Every string, plural item and array item in the default resources, keyed by name (and index for items). */
    private val strings: Map<String, String> by lazy {
        val found = linkedMapOf<String, String>()
        File("src/main/res/values").listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }.forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val singles = doc.getElementsByTagName("string")
            for (i in 0 until singles.length) {
                val node = singles.item(i)
                found[node.attributes.getNamedItem("name").nodeValue] = node.textContent
            }
            for (tag in listOf("plurals", "string-array")) {
                val groups = doc.getElementsByTagName(tag)
                for (i in 0 until groups.length) {
                    val group = groups.item(i)
                    val name = group.attributes.getNamedItem("name").nodeValue
                    val items = group.childNodes
                    var n = 0
                    for (j in 0 until items.length) {
                        val item = items.item(j)
                        if (item.nodeName == "item") found["$name[${n++}]"] = item.textContent
                    }
                }
            }
        }
        found
    }

    private val readme by lazy { File(root, "README.md").readText(Charsets.UTF_8) }

    private val listing: Map<String, String> by lazy {
        val dir = File(root, "fastlane/metadata/android/en-US")
        val files = listOf("title.txt", "short_description.txt", "full_description.txt").map { File(dir, it) } +
            File(dir, "changelogs").listFiles { f -> f.extension == "txt" }.orEmpty().sortedBy { it.name }
        files.filter { it.isFile }.associate { it.relativeTo(dir).path to it.readText(Charsets.UTF_8) }
    }

    /** Every user-facing text, labelled by where it comes from. */
    private val everything: Map<String, String> by lazy {
        strings.mapKeys { "string ${it.key}" } + mapOf("README.md" to readme) + listing.mapKeys { "fastlane ${it.key}" }
    }

    /** Lower case, Android's \' escape undone and typographic apostrophes made plain, so every spelling is caught. */
    private fun plain(text: String) = text.replace("\\'", "'").replace('’', '\'').replace('‘', '\'').lowercase()

    @Test fun `the texts were found`() {
        assertTrue("strings missing", strings.size > 100)
        assertTrue("README missing", readme.length > 1000)
        assertTrue("store listing missing", listing.keys.containsAll(listOf("short_description.txt", "full_description.txt")))
    }

    @Test fun `nothing claims the alarm cannot be escaped`() {
        val banned = listOf(
            "can't be escaped", "cannot be escaped", "can not be escaped", "inescapable",
            "cannot be turned off", "can't be turned off", "can not be turned off",
            "no escape", "impossible to", "unstoppable", "guaranteed", "guarantees you", "100%",
        )
        everything.forEach { (where, text) ->
            val lower = plain(text)
            banned.forEach { phrase -> assertFalse("$where says \"$phrase\"", lower.contains(phrase)) }
        }
    }

    @Test fun `nothing offers power-off blocking, which was not built`() {
        val banned = listOf("power guard", "blocks power off", "blocks the power menu", "can't switch the phone off", "cannot switch the phone off")
        everything.forEach { (where, text) ->
            val lower = plain(text)
            banned.forEach { phrase -> assertFalse("$where says \"$phrase\"", lower.contains(phrase)) }
        }
    }

    @Test fun `the README says what it can't stop`() {
        val text = readme.replace('’', '\'')
        val heading = Regex("""^#{2,3} What it can't stop\s*$""", RegexOption.MULTILINE).find(text)
        assertTrue("README lacks the \"What it can't stop\" heading", heading != null)
        val section = text.substring(heading!!.range.last + 1).substringBefore("\n## ")
        val lower = plain(section)
        for (must in listOf("power", "restart", "safe mode", "force stop", "emergency")) {
            assertTrue("\"What it can't stop\" should mention $must", lower.contains(must))
        }
        assertTrue("it should say power off isn't blocked", lower.contains("isn't blocked") || lower.contains("is not blocked"))
    }

    @Test fun `the protection screen says power off is not blocked`() {
        val power = plain(strings.getValue("protection_limits_power"))
        assertTrue(power.contains("isn't blocked") && power.contains("rings again"))
    }
}
