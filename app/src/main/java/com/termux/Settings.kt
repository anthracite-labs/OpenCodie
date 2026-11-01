package com.termux

/**
 * Settings & About slice: pure summary logic for the native settings dialog.
 *
 * Text indicators carry state in words ("granted", "not granted") — color is
 * never the only signal (ui-ux-pro-max ARIA/color-independence rule).
 */
data class SettingsState(
    val appVersion: String,
    val serverStatus: String,
    val serverError: String?,
    val workspacePath: String,
    val storageGranted: Boolean,
)

object Settings {
    const val SOURCE_URL = "https://github.com/anthracite-labs/OpenCodie"

    fun summaryLines(state: SettingsState): List<String> = listOf(
        "Version: ${state.appVersion}",
        "Server: " + if (state.serverError != null) {
            "[${RuntimeHealth.failureCode(state.serverError)}] ${state.serverError}"
        } else {
            state.serverStatus
        },
        "Workspace: ${state.workspacePath}",
        "Storage: " + if (state.storageGranted) "granted" else "not granted (optional)",
        "Licenses: bundled with the runtime (share/doc/opencodie/); source at github.",
    )
}
