package com.riyadm.wakenudge.update

/**
 * The newest published release, reduced to what the app shows and opens. [downloadUrl] is always an address inside
 * this project's releases (see [ReleaseUrls]); [notes] is plain text of at most [ReleaseNotes.MAX_CHARS] characters.
 */
data class ReleaseInfo(
    val version: AppVersion,
    val tag: String,
    val downloadUrl: String,
    val notes: String,
)

/** Why a lookup did not produce a release. Only ever logged: the user is never shown a reason. */
enum class FailureKind { OFFLINE, TIMEOUT, RATE_LIMITED, HTTP_ERROR, TOO_LARGE, MALFORMED, UNSAFE_REDIRECT, ALARM_RINGING, UNEXPECTED }

/** What asking GitHub for the latest release came to. */
sealed interface LookupResult {
    data class Found(val release: ReleaseInfo) : LookupResult

    /** GitHub answered, but there is nothing to offer: no release published yet, or a tag this app cannot compare. */
    data class NoRelease(val detail: String) : LookupResult

    data class Failed(val kind: FailureKind, val detail: String) : LookupResult
}

/** What the About screen and the log say about the most recent check of this session. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val release: ReleaseInfo) : UpdateStatus
    data class Failed(val kind: FailureKind) : UpdateStatus
}

/** What the update code needs to know about the running build; a value so tests can pick any build they like. */
data class UpdateEnvironment(
    /** BuildConfig.VERSION_NAME of the running app, possibly with a "-debug" suffix. */
    val currentVersion: String,
    /** Debug builds do not check on start (so developers are not nagged), but "Check now" still works. */
    val autoCheck: Boolean,
    val repoUrl: String,
)
