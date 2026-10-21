package com.riyadm.wakenudge.update

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Play build has no internet permission and says so; the GitHub APK has it (for the update check) and must not.
 * This keeps the two sets of user-facing wording honest: whenever shared text talks about the internet, the GitHub
 * flavour has to supply its own accurate version.
 */
class OfflineClaimsTest {

    private fun strings(dir: String): Map<String, String> {
        val folder = File(dir)
        if (!folder.isDirectory) return emptyMap()
        val found = linkedMapOf<String, String>()
        folder.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }.forEach { file ->
            val items = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            for (i in 0 until items.length) {
                val node = items.item(i)
                found[node.attributes.getNamedItem("name").nodeValue] = node.textContent
            }
        }
        return found
    }

    // Unit tests run with the module directory (app/) as the working directory.
    private val shared = strings("src/main/res/values")
    private val github = strings("src/github/res/values")

    @Test fun `the resource sets were found`() {
        assertTrue("shared strings missing", shared.size > 100)
        assertTrue("github strings missing", github.size > 10)
    }

    @Test fun `every shared string that talks about the internet has an accurate github version`() {
        val mentions = shared.filter { (_, text) -> text.contains("internet", ignoreCase = true) }.keys
        assertTrue("expected the shared wording to mention the internet somewhere", mentions.isNotEmpty())
        val missing = mentions.filter { it !in github }
        assertEquals("no github override for: $missing", emptyList<String>(), missing)
    }

    @Test fun `the privacy claims in the about screen and onboarding are reworded for github`() {
        val reworded = listOf("about_offline", "about_offline_body", "about_no_tracking_body", "about_permissions_body", "onboarding_privacy_body")
        reworded.forEach { name ->
            assertTrue("$name needs a github override", name in github)
            assertTrue("$name must override an existing shared string", name in shared)
        }
    }

    @Test fun `no github text claims the app cannot go online`() {
        val banned = listOf("no internet permission", "does not have the internet permission", "cannot send anything", "completely offline")
        github.forEach { (name, text) ->
            banned.forEach { phrase -> assertFalse("$name says \"$phrase\"", text.contains(phrase, ignoreCase = true)) }
        }
    }

    @Test fun `github text that mentions going online also says it is optional or switchable`() {
        listOf("about_offline_body", "onboarding_privacy_body", "about_permissions_body").forEach { name ->
            val text = github.getValue(name)
            assertTrue("$name should mention the update check", text.contains("update", ignoreCase = true))
            assertTrue("$name should say it is optional", text.contains("optional", ignoreCase = true) || text.contains("switch it off", ignoreCase = true))
        }
    }

    @Test fun `github only overrides strings that exist or are its own`() {
        val own = github.keys.filter { it.startsWith("update_") }.toSet()
        val strays = github.keys.filter { it !in shared && it !in own }
        assertEquals("github strings that override nothing: $strays", emptyList<String>(), strays)
    }

    @Test fun `the play wording is the shared wording and still says there is no internet permission`() {
        assertTrue(shared.getValue("about_offline_body").contains("internet permission"))
        assertTrue(shared.getValue("onboarding_privacy_body").contains("No internet permission"))
    }
}
