package com.riyadm.wakenudge.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Asks where the newest release is. An interface so the throttling and prompting logic never needs a network. */
interface ReleaseClient {
    /** Never throws (other than for cancellation) and never blocks the caller's thread. */
    suspend fun latest(): LookupResult
}

/** Opens a connection to [url]; the seam that lets tests stand in for the network. */
internal fun interface HttpConnections {
    fun open(url: URL): HttpURLConnection
}

private object RealConnections : HttpConnections {
    override fun open(url: URL): HttpURLConnection = url.openConnection() as HttpURLConnection
}

/**
 * Reads the latest release from the GitHub REST API with the platform's own HTTPS client. Deliberately small and
 * strict: one fixed https address on one host, short timeouts, a capped response, no redirects off that host, and a
 * result for every failure instead of an exception.
 */
class GithubReleaseClient internal constructor(
    private val env: UpdateEnvironment,
    private val connections: HttpConnections,
    private val dispatcher: CoroutineDispatcher,
    private val nanoTime: () -> Long,
) : ReleaseClient {

    @Inject constructor(env: UpdateEnvironment) : this(env, RealConnections, Dispatchers.IO, System::nanoTime)

    override suspend fun latest(): LookupResult = withContext(dispatcher) {
        try {
            fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            classify(t)
        }
    }

    private suspend fun fetch(): LookupResult {
        var url = URL(LATEST_RELEASE_URL)
        var redirects = 0
        while (true) {
            if (!isApiAddress(url)) return LookupResult.Failed(FailureKind.UNSAFE_REDIRECT, "refused ${url.protocol}://${url.host}")
            val connection = connections.open(url)
            try {
                configure(connection)
                val code = connection.responseCode
                when {
                    code == HttpURLConnection.HTTP_OK -> return readRelease(connection)
                    code in REDIRECTS -> {
                        if (++redirects > MAX_REDIRECTS) return LookupResult.Failed(FailureKind.UNSAFE_REDIRECT, "too many redirects")
                        url = followable(url, connection.getHeaderField("Location"))
                            ?: return LookupResult.Failed(FailureKind.UNSAFE_REDIRECT, "redirect to a different host or scheme")
                    }
                    // Not published yet, which is the normal state of a project before its first release.
                    code == HttpURLConnection.HTTP_NOT_FOUND -> return LookupResult.NoRelease("HTTP 404")
                    code == HttpURLConnection.HTTP_FORBIDDEN || code == TOO_MANY_REQUESTS ->
                        return LookupResult.Failed(FailureKind.RATE_LIMITED, "HTTP $code")
                    else -> return LookupResult.Failed(FailureKind.HTTP_ERROR, "HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun configure(connection: HttpURLConnection) {
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
        connection.setRequestProperty("User-Agent", USER_AGENT)
    }

    private suspend fun readRelease(connection: HttpURLConnection): LookupResult {
        if (connection.contentLengthLong > MAX_BODY_BYTES) return LookupResult.Failed(FailureKind.TOO_LARGE, "declared ${connection.contentLengthLong} bytes")
        val deadline = nanoTime() + TOTAL_TIMEOUT_NANOS
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        connection.inputStream.use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                // Counted after decompression, so a small gzip file that inflates enormously is stopped too.
                if (body.size() + read > MAX_BODY_BYTES) return LookupResult.Failed(FailureKind.TOO_LARGE, "more than $MAX_BODY_BYTES bytes")
                body.write(buffer, 0, read)
                // The read timeout only bounds each wait, so a server dripping bytes could hold the call open forever.
                if (nanoTime() > deadline) return LookupResult.Failed(FailureKind.TIMEOUT, "response too slow")
            }
        }
        return ReleaseParser.parse(body.toString(Charsets.UTF_8.name()), env.repoUrl)
    }

    /** [location] resolved against [from], or null unless it is still the API host over https. */
    private fun followable(from: URL, location: String?): URL? {
        if (location.isNullOrBlank() || location.length > MAX_LOCATION) return null
        val target = try {
            URL(from, location)
        } catch (_: java.net.MalformedURLException) {
            return null
        }
        return target.takeIf(::isApiAddress)
    }

    private fun isApiAddress(url: URL): Boolean =
        url.protocol == "https" && url.host.equals(API_HOST, ignoreCase = true) && url.userInfo == null &&
            (url.port == -1 || url.port == 443)

    private fun classify(t: Throwable): LookupResult.Failed {
        val kind = when (t) {
            is SocketTimeoutException -> FailureKind.TIMEOUT
            is UnknownHostException, is NoRouteToHostException, is SocketException -> FailureKind.OFFLINE
            is IOException -> FailureKind.OFFLINE
            else -> FailureKind.UNEXPECTED
        }
        return LookupResult.Failed(kind, t.javaClass.simpleName)
    }

    companion object {
        const val API_HOST = "api.github.com"
        const val LATEST_RELEASE_URL = "https://$API_HOST/repos/riyadmondol2006/WakeNudge/releases/latest"
        const val USER_AGENT = "WakeNudge-update-check (+https://github.com/riyadmondol2006/WakeNudge)"
        const val TIMEOUT_MS = 8_000
        const val MAX_BODY_BYTES = 256 * 1024
        private const val API_VERSION = "2022-11-28"
        private const val TOTAL_TIMEOUT_NANOS = 20_000_000_000L
        private const val TOO_MANY_REQUESTS = 429
        private const val MAX_REDIRECTS = 2
        private const val MAX_LOCATION = 512
        private val REDIRECTS = setOf(301, 302, 307, 308)
    }
}
