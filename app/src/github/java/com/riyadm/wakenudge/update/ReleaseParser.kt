package com.riyadm.wakenudge.update

import com.riyadm.wakenudge.core.WakeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** The few fields of GitHub's "latest release" document this app reads; everything else is ignored. */
@Serializable
internal data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

internal object ReleaseParser {

    /**
     * [json] is the body of GET /repos/{owner}/{repo}/releases/latest. A document that is not JSON of the expected
     * shape is a failure; a well-formed one this app cannot use (a draft, a pre-release, a tag that is not a
     * MAJOR.MINOR.PATCH number) simply has no update to offer.
     */
    fun parse(json: String, repoUrl: String): LookupResult {
        val release = try {
            WakeJson.instance.decodeFromString(GithubRelease.serializer(), json)
        } catch (e: SerializationException) {
            return LookupResult.Failed(FailureKind.MALFORMED, "not a release document")
        } catch (e: IllegalArgumentException) {
            return LookupResult.Failed(FailureKind.MALFORMED, "not a release document")
        }
        if (release.draft || release.prerelease) return LookupResult.NoRelease("latest is a draft or pre-release")
        val version = AppVersion.parse(release.tagName)
            ?: return LookupResult.NoRelease("tag \"${release.tagName.take(40)}\" is not MAJOR.MINOR.PATCH")
        return LookupResult.Found(
            ReleaseInfo(
                version = version,
                tag = release.tagName.take(40),
                downloadUrl = ReleaseUrls.downloadUrl(release.htmlUrl, repoUrl),
                notes = ReleaseNotes.excerpt(release.body),
            ),
        )
    }
}
