package com.riyadm.wakenudge.update

/**
 * The only update-related type the shared code knows about. The GitHub APK binds a real implementation (in
 * `src/github`), the Play build binds a do-nothing one (in `src/play`), so Play carries no network or update logic.
 */
interface UpdateChecker {
    /**
     * Called whenever the main screen comes to the foreground. Returns quickly and does nothing unless a check is
     * allowed and due; never throws, and never runs from an alarm or engine path.
     */
    suspend fun checkIfDue()
}
