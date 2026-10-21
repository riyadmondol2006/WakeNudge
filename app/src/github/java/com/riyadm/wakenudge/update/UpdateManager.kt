package com.riyadm.wakenudge.update

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.di.AppScope
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.engine.RingSessionStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Decides when to look for a newer release and what to do with the answer. Every entry point is driven by the main
 * screen (app start, the switch and the "Check now" button on About); nothing here is reachable from an alarm or the
 * engine, and nothing runs while an alarm is ringing.
 */
@Singleton
class UpdateManager @Inject constructor(
    private val client: ReleaseClient,
    private val settings: SettingsRepository,
    private val time: TimeSource,
    private val diagnostics: DiagnosticsLog,
    private val ring: RingSessionStore,
    private val env: UpdateEnvironment,
    @AppScope private val scope: CoroutineScope,
) : UpdateChecker {

    private val gate = Mutex()
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    private val _prompt = MutableStateFlow<ReleaseInfo?>(null)

    /** Process-wide clock of the last failed attempt, so a flaky connection is not retried on every app start. */
    @Volatile private var lastFailureAt = 0L

    /** The popup is offered once per app session, however many checks find the same release. */
    @Volatile private var offered = false

    /** Outcome of the latest check in this session; [UpdateStatus.Idle] until there is one. */
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** The release the popup should announce, or null. Cleared by [dismissPrompt] and [skip]. */
    val prompt: StateFlow<ReleaseInfo?> = _prompt.asStateFlow()

    override suspend fun checkIfDue() {
        if (!env.autoCheck) return
        val current = settings.current()
        if (!current.onboardingDone || !current.updateChecksEnabled) return
        val now = time.nowMillis()
        if (!isDue(current.lastUpdateCheckAt, now) || recentlyFailed(now)) return
        run(manual = false)
    }

    /** The "Check now" button: ignores the switch, the 24 h wait and the debug rule, but runs in the background. */
    fun checkNow() {
        scope.launch { run(manual = true) }
    }

    fun dismissPrompt() {
        _prompt.value = null
    }

    /** "Skip this version": no popup for this release; a newer one prompts again. */
    suspend fun skip(release: ReleaseInfo) {
        _prompt.value = null
        settings.update { it.copy(skippedUpdateVersion = release.version.toString()) }
    }

    suspend fun setEnabled(enabled: Boolean) {
        settings.update { it.copy(updateChecksEnabled = enabled) }
    }

    private suspend fun run(manual: Boolean) {
        if (ring.session.value != null) {
            diagnostics.info(TAG, "check skipped: an alarm is ringing")
            if (manual) _status.value = UpdateStatus.Failed(FailureKind.ALARM_RINGING)
            return
        }
        // A check already under way answers for this one too.
        if (!gate.tryLock()) return
        try {
            _status.value = UpdateStatus.Checking
            val result = try {
                client.latest()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                LookupResult.Failed(FailureKind.UNEXPECTED, t.javaClass.simpleName)
            }
            apply(result, manual)
        } finally {
            // Cancelled mid-way (the screen went away): do not leave "Checking…" behind.
            if (_status.value == UpdateStatus.Checking) _status.value = UpdateStatus.Idle
            gate.unlock()
        }
    }

    private suspend fun apply(result: LookupResult, manual: Boolean) {
        val now = time.nowMillis()
        when (result) {
            is LookupResult.Found -> {
                recordAnswer(now)
                val release = result.release
                val installed = AppVersion.parse(env.currentVersion)
                if (installed != null && release.version > installed) {
                    diagnostics.info(TAG, "newer release ${release.version} found (installed ${env.currentVersion})")
                    _status.value = UpdateStatus.Available(release)
                    if (!manual) offerPrompt(release)
                } else {
                    diagnostics.info(TAG, "up to date (latest ${release.version}, installed ${env.currentVersion})")
                    _status.value = UpdateStatus.UpToDate
                }
            }
            is LookupResult.NoRelease -> {
                recordAnswer(now)
                diagnostics.info(TAG, "no release to offer: ${result.detail}")
                _status.value = UpdateStatus.UpToDate
            }
            is LookupResult.Failed -> {
                lastFailureAt = now
                diagnostics.warn(TAG, "check failed (${result.kind}): ${result.detail}")
                _status.value = UpdateStatus.Failed(result.kind)
            }
        }
    }

    private suspend fun recordAnswer(now: Long) {
        lastFailureAt = 0
        settings.update { it.copy(lastUpdateCheckAt = now) }
    }

    private suspend fun offerPrompt(release: ReleaseInfo) {
        if (offered) return
        val skipped = AppVersion.parse(settings.current().skippedUpdateVersion)
        if (skipped != null && release.version <= skipped) return
        offered = true
        _prompt.value = release
    }

    private fun recentlyFailed(now: Long): Boolean = lastFailureAt != 0L && now - lastFailureAt in 0 until RETRY_AFTER_FAILURE_MS

    companion object {
        private const val TAG = "Update"
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val RETRY_AFTER_FAILURE_MS = 60L * 60 * 1000

        /** A clock set back past the last check counts as due, otherwise checks could stall for years. */
        fun isDue(lastCheckAt: Long, now: Long): Boolean = lastCheckAt <= 0 || lastCheckAt > now || now - lastCheckAt >= CHECK_INTERVAL_MS
    }
}
