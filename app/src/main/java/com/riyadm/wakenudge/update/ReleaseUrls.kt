package com.riyadm.wakenudge.update

import java.net.URI

/** Decides which address the "Download" button may open. Only the project's own release pages qualify. */
object ReleaseUrls {
    private const val MAX_LENGTH = 512

    /** The page for a release, or the project's "latest release" page when [htmlUrl] is not trustworthy. */
    fun downloadUrl(htmlUrl: String?, repoUrl: String): String =
        htmlUrl?.takeIf { isTrustedReleasePage(it, repoUrl) } ?: latestReleasePage(repoUrl)

    fun latestReleasePage(repoUrl: String): String = "$repoUrl/releases/latest"

    /**
     * True only for https://github.com/<owner>/<repo>/releases/... exactly as the project spells it. The prefix test
     * is not enough on its own: the address must also parse, carry no user info or odd characters, and stay on github.com.
     */
    fun isTrustedReleasePage(url: String, repoUrl: String): Boolean {
        val prefix = "$repoUrl/releases/"
        if (url.length > MAX_LENGTH || !url.startsWith(prefix)) return false
        if (url.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) return false
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        val expectedHost = try {
            URI(repoUrl).host
        } catch (_: Exception) {
            null
        } ?: return false
        // A browser resolves ".." (also written %2e%2e) before asking the server, which could leave /releases/.
        val climbs = uri.rawPath.replace("%2e", ".", ignoreCase = true).split('/').any { it == ".." }
        return !climbs && uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && uri.host.equals(expectedHost, ignoreCase = true)
    }
}
