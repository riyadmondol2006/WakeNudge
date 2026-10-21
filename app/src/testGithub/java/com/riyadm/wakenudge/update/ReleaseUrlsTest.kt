package com.riyadm.wakenudge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUrlsTest {
    private val repo = "https://github.com/riyadmondol2006/WakeNudge"
    private val fallback = "$repo/releases/latest"

    @Test fun `a release page of the project is opened as given`() {
        val page = "$repo/releases/tag/v1.0.42"
        assertEquals(page, ReleaseUrls.downloadUrl(page, repo))
        assertEquals("$repo/releases/latest", ReleaseUrls.downloadUrl("$repo/releases/latest", repo))
        assertEquals("$repo/releases/tag/v1.0.42?x=1#top", ReleaseUrls.downloadUrl("$repo/releases/tag/v1.0.42?x=1#top", repo))
    }

    @Test fun `anything else falls back to the latest release page`() {
        val untrusted = listOf(
            null,
            "",
            "   ",
            "http://github.com/riyadmondol2006/WakeNudge/releases/tag/v1.0.42",
            "https://github.com/riyadmondol2006/WakeNudge",
            "https://github.com/riyadmondol2006/WakeNudge/releases",
            "https://github.com/riyadmondol2006/WakeNudge/issues/1",
            "https://github.com/riyadmondol2006/OtherRepo/releases/tag/v1",
            "https://github.com/someone-else/WakeNudge/releases/tag/v1",
            "https://github.com.evil.example/riyadmondol2006/WakeNudge/releases/tag/v1",
            "https://evil.example/riyadmondol2006/WakeNudge/releases/tag/v1",
            "https://github.com@evil.example/riyadmondol2006/WakeNudge/releases/tag/v1",
            "https://github.com:8443/riyadmondol2006/WakeNudge/releases/tag/v1",
            "javascript:alert(1)",
            "intent://github.com/riyadmondol2006/WakeNudge/releases/#Intent;scheme=https;end",
            "market://details?id=com.riyadm.wakenudge",
            "content://com.android.contacts/contacts",
            "file:///sdcard/WakeNudge.apk",
            "https://GITHUB.com/riyadmondol2006/WakeNudge/releases/tag/v1",
            "$repo/releases/../../evil",
            "$repo/releases/%2e%2e/%2E%2e/evil",
            "$repo/releases/tag/v1 .apk",
            "$repo/releases/tag/v1\n",
            "$repo/releases/tag/v1\u0000",
            "$repo/releases/tag\\v1",
            "$repo/releases/" + "a".repeat(600),
        )
        untrusted.forEach { assertEquals("'$it'", fallback, ReleaseUrls.downloadUrl(it, repo)) }
    }

    @Test fun `trust is decided by the project address it is given`() {
        assertTrue(ReleaseUrls.isTrustedReleasePage("https://example.com/me/app/releases/tag/v1", "https://example.com/me/app"))
        assertFalse(ReleaseUrls.isTrustedReleasePage("$repo/releases/tag/v1", "https://example.com/me/app"))
        assertFalse(ReleaseUrls.isTrustedReleasePage("$repo/releases/tag/v1", "not a url"))
    }

    @Test fun `the fallback is the latest release page`() {
        assertEquals(fallback, ReleaseUrls.latestReleasePage(repo))
    }
}
