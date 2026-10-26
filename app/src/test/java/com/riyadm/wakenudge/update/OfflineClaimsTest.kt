package com.riyadm.wakenudge.update

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WakeNudge holds the internet permission for one thing, the optional update check. This keeps the user-facing wording
 * honest about it: nothing may claim the app cannot go online, and whatever says it does go online must also say what
 * for and that it can be switched off.
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
    private val strings = strings("src/main/res/values")

    @Test fun `the strings were found`() {
        assertTrue("strings missing", strings.size > 100)
    }

    @Test fun `no text claims the app cannot go online`() {
        val banned = listOf("no internet permission", "does not have the internet permission", "cannot send anything", "completely offline", "fully offline")
        strings.forEach { (name, text) ->
            banned.forEach { phrase -> assertFalse("$name says \"$phrase\"", text.contains(phrase, ignoreCase = true)) }
        }
    }

    @Test fun `text about going online names the update check and says it is optional`() {
        // The Updates section's own strings are the update check; everything else that mentions going online must say so.
        val online = strings.filter { (name, text) ->
            !name.startsWith("update_") && (text.contains("internet", ignoreCase = true) || text.contains("goes online", ignoreCase = true))
        }
        assertTrue("expected the privacy wording to mention going online", online.keys.containsAll(listOf("about_offline_body", "onboarding_privacy_body", "about_permissions_body")))
        online.forEach { (name, text) ->
            assertTrue("$name should mention the update check", text.contains("update", ignoreCase = true))
            assertTrue("$name should say it is optional", text.contains("optional", ignoreCase = true) || text.contains("switch it off", ignoreCase = true))
        }
    }

    @Test fun `the update switch says how to stay offline`() {
        assertTrue(strings.getValue("update_switch_body").contains("never goes online", ignoreCase = true))
    }
}
