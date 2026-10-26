package com.riyadm.wakenudge.missions

import android.Manifest
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MissionInfoTest {
    @Test fun `every mission type has a title description icon and unsupported reason`() {
        for (type in MissionType.entries) {
            assertNotEquals("title $type", 0, MissionInfo.titleRes(type))
            assertNotEquals("description $type", 0, MissionInfo.descriptionRes(type))
            assertNotEquals("reason $type", 0, MissionInfo.unsupportedReasonRes(type))
            assertNotNull("icon $type", MissionInfo.icon(type))
        }
    }

    @Test fun `titles descriptions and icons are distinct per mission`() {
        val types = MissionType.entries
        assertEquals(types.size, types.map { MissionInfo.titleRes(it) }.toSet().size)
        assertEquals(types.size, types.map { MissionInfo.descriptionRes(it) }.toSet().size)
        assertEquals(types.size, types.map { MissionInfo.icon(it).name }.toSet().size)
    }

    @Test fun `camera missions require the camera permission and the others do not`() {
        assertEquals(Manifest.permission.CAMERA, MissionInfo.requiredPermission(MissionType.QR_BARCODE))
        assertEquals(Manifest.permission.CAMERA, MissionInfo.requiredPermission(MissionType.PHOTO))
        for (type in listOf(MissionType.MATH, MissionType.MEMORY, MissionType.TYPING, MissionType.NFC, MissionType.SHAKE, MissionType.SQUATS, MissionType.PUZZLE)) {
            assertNull("$type", MissionInfo.requiredPermission(type))
        }
        // Steps need activity recognition only on Android 10+; the unit-test SDK level is below that.
        MissionInfo.requiredPermission(MissionType.STEPS).let { assertTrue(it == null || it == Manifest.permission.ACTIVITY_RECOGNITION) }
    }

    @Test fun `missions that need setup are exactly the ones with a registered target`() {
        val needSetup = MissionType.entries.filter { it.needsSetup }.toSet()
        assertEquals(setOf(MissionType.QR_BARCODE, MissionType.NFC, MissionType.PHOTO), needSetup)
        for (type in needSetup) assertFalse(MissionConfig(type).isConfigured)
        assertTrue(MissionConfig(MissionType.QR_BARCODE, params = mapOf(MissionConfig.PARAM_CODE to "abc")).isConfigured)
        assertTrue(MissionConfig(MissionType.NFC, params = mapOf(MissionConfig.PARAM_TAG_ID to "04AA")).isConfigured)
        assertTrue(MissionConfig(MissionType.PHOTO, params = mapOf(MissionConfig.PARAM_SIGNATURE to "sig")).isConfigured)
    }
}

/** Checks the shipped resource file itself: naming, and that the typing phrases fit their difficulty. */
class MissionStringsTest {
    private val resources: Element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/values/strings_missions.xml")).documentElement

    private fun items(arrayName: String): List<String> {
        val array = (0 until resources.getElementsByTagName("string-array").length)
            .map { resources.getElementsByTagName("string-array").item(it) as Element }
            .first { it.getAttribute("name") == arrayName }
        return (0 until array.getElementsByTagName("item").length).map { array.getElementsByTagName("item").item(it).textContent.replace("\\'", "'") }
    }

    @Test fun `every resource name is prefixed with mission_`() {
        val names = listOf("string", "string-array", "plurals").flatMap { tag ->
            (0 until resources.getElementsByTagName(tag).length).map { (resources.getElementsByTagName(tag).item(it) as Element).getAttribute("name") }
        }
        assertTrue(names.size > 100)
        assertTrue(names.filterNot { it.startsWith("mission_") }.toString(), names.all { it.startsWith("mission_") })
        assertEquals("duplicate names", names.size, names.toSet().size)
    }

    @Test fun `there are about forty phrases in total, all typeable on a plain keyboard`() {
        val all = items("mission_phrases_easy") + items("mission_phrases_medium") + items("mission_phrases_hard")
        assertTrue("phrases: ${all.size}", all.size in 38..50)
        assertEquals("phrases are unique", all.size, all.toSet().size)
        for (phrase in all) {
            assertTrue("'$phrase' has characters outside basic ASCII", phrase.all { it.code in 32..126 })
            assertEquals("'$phrase' has stray spaces", phrase.trim().replace(Regex(" +"), " "), phrase)
        }
    }

    @Test fun `easy phrases are short lowercase and unpunctuated`() {
        for (phrase in items("mission_phrases_easy")) {
            assertTrue("'$phrase' length ${phrase.length}", phrase.length in 15..32)
            assertEquals(phrase.lowercase(), phrase)
            assertTrue("'$phrase'", phrase.all { it.isLowerCase() || it == ' ' })
        }
    }

    @Test fun `medium phrases are about thirty-five characters, lowercase and unpunctuated`() {
        for (phrase in items("mission_phrases_medium")) {
            assertTrue("'$phrase' length ${phrase.length}", phrase.length in 28..42)
            assertTrue("'$phrase'", phrase.all { it.isLowerCase() || it == ' ' })
        }
    }

    @Test fun `hard phrases are about sixty characters with capitals and punctuation`() {
        for (phrase in items("mission_phrases_hard")) {
            assertTrue("'$phrase' length ${phrase.length}", phrase.length in 50..68)
            assertTrue("'$phrase' has no capital", phrase.any { it.isUpperCase() })
            assertTrue("'$phrase' has no punctuation", phrase.any { it in ",.;:!-'" })
        }
    }

    @Test fun `the pattern mission describes all nine dots`() {
        assertEquals(9, items("mission_dot_names").size)
    }
}
