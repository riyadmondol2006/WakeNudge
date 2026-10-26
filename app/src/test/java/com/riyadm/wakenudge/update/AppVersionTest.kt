package com.riyadm.wakenudge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    private fun v(text: String) = AppVersion.parse(text)!!

    @Test fun `plain and tagged versions parse to the same number`() {
        val expected = AppVersion(1, 0, 42)
        assertEquals(expected, AppVersion.parse("1.0.42"))
        assertEquals(expected, AppVersion.parse("v1.0.42"))
        assertEquals(expected, AppVersion.parse("V1.0.42"))
        assertEquals(expected, AppVersion.parse("  v1.0.42 \n"))
    }

    @Test fun `suffixes such as debug and pre-release labels are ignored`() {
        val expected = AppVersion(1, 0, 42)
        assertEquals(expected, AppVersion.parse("1.0.42-debug"))
        assertEquals(expected, AppVersion.parse("v1.0.42-rc.1"))
        assertEquals(expected, AppVersion.parse("1.0.42+build.7"))
        assertEquals(expected, AppVersion.parse("1.0.42-beta+exp.sha.5114f85"))
        assertEquals(expected, AppVersion.parse("1.0.42-"))
    }

    @Test fun `leading zeros and zero versions are numbers`() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("01.002.0003"))
        assertEquals(AppVersion(0, 0, 0), AppVersion.parse("0.0.0"))
        assertEquals(AppVersion(999_999_999, 999_999_999, 999_999_999), AppVersion.parse("999999999.999999999.999999999"))
    }

    @Test fun `anything that is not three plain numbers is not a version`() {
        val notVersions = listOf(
            null, "", " ", "v", "V", "1", "1.0", "v1.0", "1.0.0.0", "1..0", ".1.0", "1.0.", "..", "a.b.c", "1.0.x", "1.x.0",
            "-1.0.0", "1.-1.0", "+1.0.0", "1.0.-1", "1.0.+1", "vv1.0.0", "v v1.0.0", "1. 0.0", "1 .0.0", "1.0.0 beta", "1,0,0",
            "latest", "nightly", "release-1.0.0", "1.0.4294967296", "1.0.1234567890", "٣.١.٢", "１.０.０", "1.0.0\u0000",
            "9".repeat(41),
        )
        notVersions.forEach { assertNull("'$it' must not parse", AppVersion.parse(it)) }
    }

    @Test fun `numbers are compared as numbers not as text`() {
        assertTrue(v("1.0.10") > v("1.0.9"))
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.0.2") < v("1.0.10"))
        assertTrue(v("0.9.9") < v("1.0.0"))
    }

    @Test fun `equal numbers are equal whatever the spelling`() {
        assertEquals(0, v("1.0.42").compareTo(v("v1.0.42-debug")))
        assertEquals(v("1.0.42"), v("v001.0.042"))
    }

    @Test fun `versions sort ascending`() {
        val shuffled = listOf("1.0.10", "1.0.2", "0.9.0", "1.1.0", "1.0.9").map(::v)
        assertEquals(listOf("0.9.0", "1.0.2", "1.0.9", "1.0.10", "1.1.0"), shuffled.sorted().map { it.toString() })
    }

    @Test fun `a version prints as three plain numbers`() {
        assertEquals("1.0.42", v("v1.0.42-debug").toString())
    }
}
