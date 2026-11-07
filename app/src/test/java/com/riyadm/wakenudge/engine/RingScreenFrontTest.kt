package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.engine.policy.RingScreenFront
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "the ring screen is in front" means for the pull-back, in the order the Pixel 6 (Android 17, gesture navigation)
 * reported its callbacks: the Overview over a resumed ring screen only takes its input away, and the system announces it.
 */
class RingScreenFrontTest {
    private var now = 5_000_000L
    private val front = RingScreenFront()

    private fun shown() {
        front.onResumed(true, inMultiWindow = false)
        front.onTopResumed(true)
        front.onFocus(true, now)
    }

    @Test fun `resumed, on top and with input is in front`() {
        shown()
        assertTrue(front.inFront)
    }

    @Test fun `the Overview over the ring screen leaves it resumed and on top, but it is not in front`() {
        shown()
        // As on the phone: the announcement first, then input moves to the launcher; no pause, no top-resumed change.
        front.onRecentsOpened(now)
        now += 120
        front.onFocus(false, now)
        assertFalse("behind the Overview", front.inFront)
        assertTrue(front.behindOverview)
    }

    @Test fun `the announcement may also come after input is gone`() {
        shown()
        front.onFocus(false, now)
        now += 300
        front.onRecentsOpened(now)
        assertFalse(front.inFront)
    }

    @Test fun `coming back with input is in front again`() {
        shown()
        front.onRecentsOpened(now)
        front.onFocus(false, now + 100)
        now += 1_500
        front.onFocus(true, now)
        assertTrue(front.inFront)
    }

    @Test fun `input lost to the shade, the power menu or the ring screen's own dialog is not leaving`() {
        shown()
        now += 60_000
        front.onFocus(false, now)
        assertTrue("never pulled back over the power menu's Emergency button or an Emergency dialog", front.inFront)
    }

    @Test fun `an old Overview announcement does not make a later shade look like the Overview`() {
        shown()
        front.onRecentsOpened(now)
        front.onFocus(false, now + 100)
        front.onFocus(true, now + 1_200)
        now += 30_000
        front.onFocus(false, now)
        assertTrue(front.inFront)
    }

    @Test fun `resumed but not on top, as when started behind the Overview or beside another app, is not in front`() {
        front.onResumed(true, inMultiWindow = false)
        front.onFocus(true, now)
        assertFalse(front.inFront)
        front.onTopResumed(true)
        assertTrue(front.inFront)
    }

    @Test fun `split screen is not in front even on top with input`() {
        shown()
        front.onResumed(true, inMultiWindow = true)
        assertFalse(front.inFront)
    }

    @Test fun `paused is not in front and loses top`() {
        shown()
        front.onResumed(false, inMultiWindow = false)
        assertFalse(front.inFront)
        front.onResumed(true, inMultiWindow = false)
        assertFalse("not on top until the system says so", front.inFront)
    }

    @Test fun `a new ring screen starts with nothing known`() {
        shown()
        front.onRecentsOpened(now)
        front.onNewScreen()
        assertFalse(front.inFront)
        shown()
        assertTrue(front.inFront)
    }
}

/** The store keeps the state of the ring screen that resumed last, so an older one being taken down cannot clear it. */
class RingScreenOwnerTest {
    private val store = RingSessionStore()
    private val old = Any()
    private val new = Any()

    private fun show(screen: Any) {
        store.setUiResumed(screen, true, false)
        store.setUiTopResumed(screen, true)
        store.setUiFocused(screen, true, 1_000)
    }

    @Test fun `an older ring screen going away does not mark the new one as gone`() {
        show(old)
        show(new)
        assertTrue(store.isUiInFront)
        // The old task removed after the new screen resumed (RingTasks): its pause, focus and destroy arrive late.
        store.setUiTopResumed(old, false)
        store.setUiFocused(old, false, 2_000)
        store.setUiResumed(old, false, false)
        assertTrue(store.isUiInFront)
    }

    @Test fun `the screen in charge leaving is seen`() {
        show(old)
        store.setUiResumed(old, false, false)
        assertFalse(store.isUiInFront)
    }
}
