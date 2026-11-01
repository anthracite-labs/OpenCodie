package com.termux

import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    private fun state(error: String? = null, storage: Boolean = true) = SettingsState(
        appVersion = "2.2.2",
        serverStatus = "Ready",
        serverError = error,
        workspacePath = "/data/data/com.termux/files/home/projects/workspace",
        storageGranted = storage,
    )

    @Test
    fun healthySummaryUsesWordsNotColor() {
        val lines = Settings.summaryLines(state())
        assertTrue(lines.any { it.contains("Server: Ready") })
        assertTrue(lines.any { it.contains("Storage: granted") })
    }

    @Test
    fun errorCarriesCodeAndStorageHonesty() {
        val lines = Settings.summaryLines(state(error = "OpenCode stopped: boom", storage = false))
        assertTrue(lines.any { it.contains("[E_STARTUP]") })
        assertTrue(lines.any { it.contains("not granted (optional)") })
    }
}
