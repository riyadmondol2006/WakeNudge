package com.riyadm.wakenudge.missions

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Continue, the button that let anyone through a mission by refusing a permission, is gone and must not come back. */
class NoContinueTest {
    private val valuesDir = File("src/main/res/values")

    @Test fun `there is no Continue string for missions any more`() {
        val files = valuesDir.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty()
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val text = file.readText()
            assertFalse("${file.name} defines mission_continue", text.contains("name=\"mission_continue\""))
            assertFalse("${file.name} offers a plain Continue", Regex(">\\s*Continue\\s*<").containsMatchIn(text))
        }
    }

    @Test fun `no mission source refers to a way to skip it`() {
        val sources = File("src/main/java/com/riyadm/wakenudge/missions").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        for (file in sources) {
            val text = file.readText()
            assertFalse("${file.name} uses mission_continue", text.contains("mission_continue"))
            assertFalse("${file.name} uses MissionWayOut", text.contains("MissionWayOut"))
        }
    }
}
