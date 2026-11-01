package com.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityCenterTest {
    @Test
    fun errorGetsCodePrefix() {
        val text = ActivityCenter.notificationText("Ready", "OpenCode stopped: boom")
        assertTrue(text.startsWith("[E_STARTUP]"))
    }

    @Test
    fun cleanStatusPassesThrough() {
        assertEquals("Waiting for OpenCode", ActivityCenter.notificationText("Waiting for OpenCode", null))
    }

    @Test
    fun blankStatusDefaults() {
        assertEquals("Starting", ActivityCenter.notificationText("  ", null))
    }

    @Test
    fun longTextTruncatesToLimit() {
        val text = ActivityCenter.notificationText("Ready", "x".repeat(200))
        assertEquals(ActivityCenter.MAX_TEXT, text.length)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun newlinesCollapse() {
        val text = ActivityCenter.notificationText("a\nb", null)
        assertEquals("a b", text)
    }
}
