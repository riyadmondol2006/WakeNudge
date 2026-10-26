package com.riyadm.wakenudge.update

/**
 * What the main screen calls to look for a newer release. [UpdateManager] implements it; tests swap in their own, so the
 * screen's view model can be tested without the network.
 */
interface UpdateChecker {
    /**
     * Called whenever the main screen comes to the foreground, and when onboarding finishes. Returns quickly and does
     * nothing unless a check is allowed and due; never throws, and never runs from an alarm or engine path.
     */
    suspend fun checkIfDue()
}
