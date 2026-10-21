package com.riyadm.wakenudge.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.riyadm.wakenudge.BuildConfig
import com.riyadm.wakenudge.R

/** The project's public addresses, and the one place the app hands a link to the user's browser. */
object ProjectLinks {
    const val REPO: String = BuildConfig.REPO_URL
    const val SITE: String = BuildConfig.SITE_URL
    const val ISSUES: String = "$REPO/issues"
    /** The policy lives in the repository until the project has a website of its own; change it here when that happens. */
    const val PRIVACY_POLICY: String = "$REPO/blob/main/PRIVACY.md"

    /** How an address reads on screen: without the scheme, which adds noise and no meaning. */
    fun display(url: String): String = url.removePrefix("https://").trimEnd('/')

    /**
     * Opens [url] in whatever browser the user has. Only https addresses are ever opened, and a phone with no
     * browser (or a locked-down profile) gets a short message instead of a crash. Returns whether anything opened.
     */
    fun open(context: Context, url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.link_no_browser, Toast.LENGTH_SHORT).show()
            false
        } catch (_: SecurityException) {
            Toast.makeText(context, R.string.link_no_browser, Toast.LENGTH_SHORT).show()
            false
        }
    }
}
