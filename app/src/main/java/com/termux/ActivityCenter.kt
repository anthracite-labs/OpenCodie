package com.termux

/**
 * Activity Center slice (part 1): live status for the notification shade.
 *
 * Pure logic, no Android imports, so it stays unit-testable on the JVM.
 * The task inbox (running/done/interrupted/pending) is deferred: it needs
 * server session/task API discovery (today only GET /api/info and
 * POST /api/pair are known in OpenCodeHost).
 */
object ActivityCenter {
    const val MAX_TEXT = 64

    /**
     * One atomic status line. Error code first so a truncated shade still
     * shows the failure class. Status/error carry no secrets by
     * construction (server output lives only in the capped host log).
     */
    fun notificationText(status: String, error: String?): String {
        val base = if (error != null) "[${RuntimeHealth.failureCode(error)}] $error"
        else status.ifBlank { "Starting" }
        val oneLine = base.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= MAX_TEXT) oneLine else oneLine.take(MAX_TEXT - 1) + "…"
    }
}
