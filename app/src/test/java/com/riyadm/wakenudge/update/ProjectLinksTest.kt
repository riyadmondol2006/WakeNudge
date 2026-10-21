package com.riyadm.wakenudge.update

import com.riyadm.wakenudge.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectLinksTest {

    @Test fun `the links are the project's public addresses`() {
        assertEquals("https://github.com/riyadmondol2006/WakeNudge", ProjectLinks.REPO)
        assertEquals(BuildConfig.REPO_URL, ProjectLinks.REPO)
        assertEquals("https://github.com/riyadmondol2006/WakeNudge/issues", ProjectLinks.ISSUES)
        assertEquals("https://github.com/riyadmondol2006/WakeNudge/blob/main/PRIVACY.md", ProjectLinks.PRIVACY_POLICY)
    }

    @Test fun `every link is https`() {
        listOf(ProjectLinks.REPO, ProjectLinks.ISSUES, ProjectLinks.PRIVACY_POLICY).forEach { assertTrue(it, it.startsWith("https://")) }
    }

    @Test fun `addresses are shown without the scheme`() {
        assertEquals("github.com/riyadmondol2006/WakeNudge", ProjectLinks.display(ProjectLinks.REPO))
        assertEquals("github.com/riyadmondol2006/WakeNudge/issues", ProjectLinks.display(ProjectLinks.ISSUES))
        assertEquals("github.com/riyadmondol2006/WakeNudge/blob/main/PRIVACY.md", ProjectLinks.display(ProjectLinks.PRIVACY_POLICY))
        assertEquals("example.com", ProjectLinks.display("https://example.com/"))
    }
}
