package com.riyadm.wakenudge.update

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One scripted answer from the "network". */
private class FakeConnection(
    url: URL,
    private val code: Int = 200,
    private val body: InputStream = ByteArrayInputStream(ByteArray(0)),
    private val headers: Map<String, String> = emptyMap(),
    private val declaredLength: Long = -1,
    private val failOnResponse: IOException? = null,
) : HttpURLConnection(url) {
    val sent = mutableMapOf<String, String>()
    var disconnected = false

    constructor(url: URL, code: Int, body: String, headers: Map<String, String> = emptyMap()) :
        this(url, code, ByteArrayInputStream(body.toByteArray()), headers)

    override fun connect() = Unit
    override fun disconnect() {
        disconnected = true
    }
    override fun usingProxy() = false
    override fun getResponseCode(): Int {
        failOnResponse?.let { throw it }
        return code
    }
    override fun getInputStream(): InputStream = body
    override fun getHeaderField(name: String?): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    override fun getContentLengthLong(): Long = declaredLength
    override fun setRequestProperty(key: String, value: String) {
        sent[key] = value
    }
}

class GithubReleaseClientTest {
    private val repo = "https://github.com/riyadmondol2006/WakeNudge"
    private val env = UpdateEnvironment(currentVersion = "1.0.1", autoCheck = true, repoUrl = repo)
    private val goodBody = """{"tag_name":"v1.0.42","html_url":"$repo/releases/tag/v1.0.42","body":"Notes"}"""

    private var clock = 0L
    private val requested = mutableListOf<URL>()
    private val opened = mutableListOf<FakeConnection>()

    /** A client whose every connection is made by [script] (called with the 0-based request number). */
    private fun client(dispatcher: kotlinx.coroutines.CoroutineDispatcher, script: (Int, URL) -> FakeConnection) =
        GithubReleaseClient(
            env,
            HttpConnections { url ->
                requested += url
                script(requested.size - 1, url).also { opened += it }
            },
            dispatcher,
        ) { clock }

    private fun runClient(script: (Int, URL) -> FakeConnection): LookupResult {
        var result: LookupResult? = null
        runTest {
            result = client(UnconfinedTestDispatcher(testScheduler), script).latest()
        }
        return result!!
    }

    private fun failure(result: LookupResult): FailureKind = (result as LookupResult.Failed).kind

    @Test fun `a good answer becomes a release and the request is exactly what we promise`() {
        val result = runClient { _, url -> FakeConnection(url, 200, goodBody) }
        val release = (result as LookupResult.Found).release
        assertEquals(AppVersion(1, 0, 42), release.version)
        assertEquals("Notes", release.notes)

        assertEquals(listOf(URL("https://api.github.com/repos/riyadmondol2006/WakeNudge/releases/latest")), requested)
        val connection = opened.single()
        assertEquals("GET", connection.requestMethod)
        assertEquals("application/vnd.github+json", connection.sent["Accept"])
        assertEquals(GithubReleaseClient.USER_AGENT, connection.sent["User-Agent"])
        assertTrue(connection.sent["User-Agent"]!!.contains("WakeNudge"))
        assertFalse("follows no redirect by itself", connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.connectTimeout in 1..8_000)
        assertTrue(connection.readTimeout in 1..8_000)
        assertTrue(connection.disconnected)
        assertEquals("nothing but those headers is sent", setOf("Accept", "X-GitHub-Api-Version", "User-Agent"), connection.sent.keys)
    }

    @Test fun `404 before the first release is not a failure`() {
        val result = runClient { _, url -> FakeConnection(url, 404, """{"message":"Not Found"}""") }
        assertTrue(result is LookupResult.NoRelease)
        assertTrue(opened.single().disconnected)
    }

    @Test fun `403 and 429 are rate limits`() {
        assertEquals(FailureKind.RATE_LIMITED, failure(runClient { _, url -> FakeConnection(url, 403, """{"message":"API rate limit exceeded"}""") }))
        assertEquals(FailureKind.RATE_LIMITED, failure(runClient { _, url -> FakeConnection(url, 429, "") }))
    }

    @Test fun `other statuses are plain http errors`() {
        listOf(401, 410, 500, 502, 503, 204, 304).forEach { code ->
            assertEquals("HTTP $code", FailureKind.HTTP_ERROR, failure(runClient { _, url -> FakeConnection(url, code, "") }))
        }
    }

    @Test fun `network trouble is reported not thrown`() {
        assertEquals(FailureKind.TIMEOUT, failure(runClient { _, url -> FakeConnection(url, failOnResponse = SocketTimeoutException("connect timed out")) }))
        assertEquals(FailureKind.OFFLINE, failure(runClient { _, url -> FakeConnection(url, failOnResponse = UnknownHostException("api.github.com")) }))
        assertEquals(FailureKind.OFFLINE, failure(runClient { _, url -> FakeConnection(url, failOnResponse = ConnectException("refused")) }))
        assertEquals(FailureKind.OFFLINE, failure(runClient { _, url -> FakeConnection(url, failOnResponse = IOException("reset")) }))
    }

    @Test fun `a connection that dies mid-body is reported`() {
        val dying = object : InputStream() {
            override fun read(): Int = throw SocketTimeoutException("read timed out")
        }
        assertEquals(FailureKind.TIMEOUT, failure(runClient { _, url -> FakeConnection(url, 200, dying) }))
        assertTrue(opened.single().disconnected)
    }

    @Test fun `unexpected runtime errors are contained`() {
        val bomb = object : InputStream() {
            override fun read(): Int = throw SecurityException("INTERNET permission is missing")
        }
        assertEquals(FailureKind.UNEXPECTED, failure(runClient { _, url -> FakeConnection(url, 200, bomb) }))
    }

    @Test fun `malformed json is a failure`() {
        assertEquals(FailureKind.MALFORMED, failure(runClient { _, url -> FakeConnection(url, 200, "<html>captive portal</html>") }))
        assertEquals(FailureKind.MALFORMED, failure(runClient { _, url -> FakeConnection(url, 200, "{") }))
    }

    @Test fun `an empty body is a failure`() {
        assertEquals(FailureKind.MALFORMED, failure(runClient { _, url -> FakeConnection(url, 200, "") }))
    }

    @Test fun `a response that declares itself too big is refused unread`() {
        var read = false
        val stream = object : InputStream() {
            override fun read(): Int {
                read = true
                return -1
            }
        }
        val result = runClient { _, url -> FakeConnection(url, 200, stream, declaredLength = 10L * 1024 * 1024) }
        assertEquals(FailureKind.TOO_LARGE, failure(result))
        assertFalse("not a single byte was read", read)
    }

    @Test fun `a response that outgrows the cap while streaming is cut off`() {
        val endless = object : InputStream() {
            var served = 0L
            override fun read(): Int = 'a'.code.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte())
                return len
            }
        }
        val result = runClient { _, url -> FakeConnection(url, 200, endless) }
        assertEquals(FailureKind.TOO_LARGE, failure(result))
        assertTrue("stopped soon after the cap, read ${endless.served}", endless.served <= GithubReleaseClient.MAX_BODY_BYTES + 16 * 1024)
        assertTrue(opened.single().disconnected)
    }

    @Test fun `a body just under the cap still works`() {
        val padding = "x".repeat(GithubReleaseClient.MAX_BODY_BYTES - 200)
        val json = """{"tag_name":"v2.0.0","pad":"$padding"}"""
        assertTrue(json.length < GithubReleaseClient.MAX_BODY_BYTES)
        val release = (runClient { _, url -> FakeConnection(url, 200, json) } as LookupResult.Found).release
        assertEquals(AppVersion(2, 0, 0), release.version)
    }

    @Test fun `a server that drips bytes forever is stopped by the overall deadline`() {
        val slow = object : InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                clock += 7_000_000_000L // seven seconds per read: each one is inside the read timeout
                b[off] = 'a'.code.toByte()
                return 1
            }
        }
        assertEquals(FailureKind.TIMEOUT, failure(runClient { _, url -> FakeConnection(url, 200, slow) }))
    }

    @Test fun `a redirect within api github com is followed`() {
        val result = runClient { n, url ->
            if (n == 0) FakeConnection(url, 301, "", mapOf("Location" to "https://api.github.com/repositories/123/releases/latest"))
            else FakeConnection(url, 200, goodBody)
        }
        assertTrue(result is LookupResult.Found)
        assertEquals(URL("https://api.github.com/repositories/123/releases/latest"), requested[1])
        assertTrue("every connection was closed", opened.all { it.disconnected })
    }

    @Test fun `a relative redirect stays on the same host`() {
        val result = runClient { n, url ->
            if (n == 0) FakeConnection(url, 307, "", mapOf("location" to "/repositories/123/releases/latest"))
            else FakeConnection(url, 200, goodBody)
        }
        assertTrue(result is LookupResult.Found)
        assertEquals("api.github.com", requested[1].host)
    }

    @Test fun `redirects to other hosts or schemes are refused without a second request`() {
        listOf(
            "https://evil.example/repos/riyadmondol2006/WakeNudge/releases/latest",
            "https://github.com/riyadmondol2006/WakeNudge/releases/latest",
            "https://api.github.com.evil.example/x",
            "https://api.github.com@evil.example/x",
            "https://user:pw@api.github.com/x",
            "https://api.github.com:8443/x",
            "http://api.github.com/repos/riyadmondol2006/WakeNudge/releases/latest",
            "ftp://api.github.com/x",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "//evil.example/x",
            "",
            "   ",
            "https://api.github.com/" + "a".repeat(600),
        ).forEach { target ->
            requested.clear(); opened.clear()
            val result = runClient { _, url -> FakeConnection(url, 302, "", mapOf("Location" to target)) }
            assertEquals("'$target'", FailureKind.UNSAFE_REDIRECT, failure(result))
            assertEquals("'$target' must not be requested", 1, requested.size)
            assertTrue(opened.single().disconnected)
        }
    }

    @Test fun `a redirect without a location is refused`() {
        assertEquals(FailureKind.UNSAFE_REDIRECT, failure(runClient { _, url -> FakeConnection(url, 302, "") }))
    }

    @Test fun `a redirect loop ends`() {
        val result = runClient { _, url -> FakeConnection(url, 302, "", mapOf("Location" to "https://api.github.com/loop")) }
        assertEquals(FailureKind.UNSAFE_REDIRECT, failure(result))
        assertTrue("gave up after a few hops, made ${requested.size}", requested.size <= 4)
    }

    @Test fun `cancelling stops the check instead of reporting a failure`() = runTest {
        lateinit var job: Job
        val cancelOnRead = object : InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                job.cancel()
                b[off] = 'a'.code.toByte()
                return 1
            }
        }
        var result: LookupResult? = null
        job = launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.LAZY) {
            result = client(UnconfinedTestDispatcher(testScheduler)) { _, url -> FakeConnection(url, 200, cancelOnRead) }.latest()
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("no result is produced for a cancelled check", null, result)
        assertTrue(opened.single().disconnected)
    }

    @Test fun `the endpoint is the fixed https address of this project`() {
        assertEquals("https://api.github.com/repos/riyadmondol2006/WakeNudge/releases/latest", GithubReleaseClient.LATEST_RELEASE_URL)
        assertEquals("https", URL(GithubReleaseClient.LATEST_RELEASE_URL).protocol)
        assertTrue(GithubReleaseClient.MAX_BODY_BYTES <= 256 * 1024)
        assertTrue(GithubReleaseClient.TIMEOUT_MS <= 8_000)
    }
}
