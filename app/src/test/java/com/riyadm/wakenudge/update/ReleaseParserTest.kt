package com.riyadm.wakenudge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {
    private val repo = "https://github.com/riyadmondol2006/WakeNudge"

    private fun parse(json: String) = ReleaseParser.parse(json, repo)

    private fun found(json: String) = (parse(json) as LookupResult.Found).release

    /** Shaped like the real response, with plenty of fields the app does not care about. */
    private val real = """
        {
          "url": "https://api.github.com/repos/riyadmondol2006/WakeNudge/releases/1",
          "html_url": "https://github.com/riyadmondol2006/WakeNudge/releases/tag/v1.0.42",
          "id": 1,
          "author": { "login": "riyadmondol2006", "id": 7, "site_admin": false },
          "tag_name": "v1.0.42",
          "target_commitish": "main",
          "name": "WakeNudge 1.0.42",
          "draft": false,
          "prerelease": false,
          "created_at": "2026-10-21T05:30:00Z",
          "assets": [ { "name": "WakeNudge-1.0.42.apk", "size": 12345, "browser_download_url": "https://github.com/x/y/z.apk" } ],
          "body": "## What's new\n- Faster **alarms**"
        }
    """.trimIndent()

    @Test fun `a real release document is understood`() {
        val release = found(real)
        assertEquals(AppVersion(1, 0, 42), release.version)
        assertEquals("v1.0.42", release.tag)
        assertEquals("$repo/releases/tag/v1.0.42", release.downloadUrl)
        assertEquals("What's new\n• Faster alarms", release.notes)
    }

    @Test fun `a missing or null body means no notes`() {
        assertEquals("", found("""{"tag_name":"v1.0.1","html_url":"$repo/releases/tag/v1.0.1"}""").notes)
        assertEquals("", found("""{"tag_name":"v1.0.1","html_url":"$repo/releases/tag/v1.0.1","body":null}""").notes)
    }

    @Test fun `notes are cut to the dialog limit`() {
        val body = "line of notes ".repeat(2_000)
        val release = found("""{"tag_name":"v1.0.1","body":"$body"}""")
        assertTrue(release.notes.length <= ReleaseNotes.MAX_CHARS)
    }

    @Test fun `an address outside the project's releases is replaced by the latest release page`() {
        listOf("https://evil.example/download.apk", "http://github.com/riyadmondol2006/WakeNudge/releases/tag/v1", "", "javascript:alert(1)")
            .forEach { url ->
                val release = found("""{"tag_name":"v1.0.1","html_url":"$url"}""")
                assertEquals(url, "$repo/releases/latest", release.downloadUrl)
            }
        assertEquals("$repo/releases/latest", found("""{"tag_name":"v1.0.1"}""").downloadUrl)
    }

    @Test fun `a draft or a pre-release is not offered`() {
        assertTrue(parse("""{"tag_name":"v1.0.9","draft":true}""") is LookupResult.NoRelease)
        assertTrue(parse("""{"tag_name":"v1.0.9","prerelease":true}""") is LookupResult.NoRelease)
    }

    @Test fun `a tag that is not a version means no update rather than an error`() {
        listOf("nightly", "release", "", "v1", "v1.0", "v1.0.0.0", "1.x.0").forEach { tag ->
            assertTrue("'$tag'", parse("""{"tag_name":"$tag"}""") is LookupResult.NoRelease)
        }
        assertTrue(parse("{}") is LookupResult.NoRelease)
    }

    @Test fun `suffixes on the tag are ignored`() {
        assertEquals(AppVersion(1, 0, 42), found("""{"tag_name":"v1.0.42-debug"}""").version)
    }

    @Test fun `text that is not a release document is a failure`() {
        listOf("", "   ", "not json", "{", "[]", "[1,2]", "null", "\"v1.0.1\"", "42", "<html>rate limited</html>", """{"tag_name": 5}""", """{"tag_name":["v1"]}""")
            .forEach { text ->
                val result = parse(text)
                assertTrue("'$text' gave $result", result is LookupResult.Failed && result.kind == FailureKind.MALFORMED)
            }
    }

    @Test fun `unknown fields of every shape are ignored`() {
        val json = """{"tag_name":"v1.2.3","brand_new":{"a":[1,2,{"b":null}]},"another":"x","n":1.5}"""
        assertEquals(AppVersion(1, 2, 3), found(json).version)
    }
}
