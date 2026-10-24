package cn.edu.ahtcm.schedule.ui

import kotlin.test.*
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun `navigation adapts to platform and available width and height`() {
        assertEquals(NavigationMode.Bottom, appLayout(390f, 844f, false).navigation)
        assertEquals(NavigationMode.Rail, appLayout(844f, 390f, false).navigation)
        assertFalse(appLayout(844f, 390f, false).details)
        assertTrue(appLayout(844f, 390f, false).shortWindow)
        assertEquals(NavigationMode.Rail, appLayout(800f, 1280f, false).navigation)
        assertTrue(appLayout(1280f, 800f, false).details)
        assertEquals(NavigationMode.Sidebar, appLayout(1180f, 820f, true).navigation)
        assertTrue(appLayout(1180f, 820f, true).details)
        assertEquals(NavigationMode.Rail, appLayout(760f, 650f, true).navigation)
        assertFalse(appLayout(760f, 650f, true).details)
        assertFalse(appLayout(1180f, 820f, true, 1.6f).details)
    }

    @Test fun `synchronization gate serializes operations across UI recreation`() {
        val gate = SyncGate()
        assertTrue(gate.acquire())
        assertFalse(gate.acquire())
        assertTrue(gate.running.value)
        gate.release()
        assertFalse(gate.running.value)
        assertTrue(gate.acquire())
        gate.release()
    }
}
