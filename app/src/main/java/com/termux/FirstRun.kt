package com.termux

/**
 * Guided First Run slice: pure state machine, no Android imports.
 *
 * Automatic startup is kept; this only adds step-by-step guidance that is
 * dismissible and shown at most once per app version (never loops).
 * Storage stays optional: the step can always be skipped.
 */
enum class FirstRunStep { RUNTIME, STORAGE, PROJECT, DONE }

data class FirstRunState(
    val runtimeReady: Boolean,
    val storageGranted: Boolean,
    val projectNames: List<String>,
    val seenVersion: String?,
    val currentVersion: String,
)

object FirstRun {
    const val PREFS = "opencodie_firstrun"
    const val SEEN_KEY = "seen_version"

    fun nextStep(state: FirstRunState): FirstRunStep = when {
        !state.runtimeReady -> FirstRunStep.RUNTIME
        !state.storageGranted -> FirstRunStep.STORAGE
        state.projectNames.isEmpty() -> FirstRunStep.PROJECT
        else -> FirstRunStep.DONE
    }

    /** Show at most once per version, only after the automatic bootstrap prompt ran. */
    fun shouldShow(
        runtimeReady: Boolean,
        storagePromptedBefore: Boolean,
        seenVersion: String?,
        currentVersion: String,
    ): Boolean = runtimeReady && storagePromptedBefore && seenVersion != currentVersion

    fun stepLines(state: FirstRunState): List<String> = listOf(
        "1. Runtime: " + if (state.runtimeReady) "ready" else "installing…",
        "2. Storage (optional): " + if (state.storageGranted) "granted" else "skipped for now",
        "3. First project: " + if (state.projectNames.isEmpty()) {
            "create one under ~/projects in the WebUI"
        } else {
            "${state.projectNames.size} found"
        },
    )
}
