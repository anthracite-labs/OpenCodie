package com.termux

/**
 * Native mapping for the Runtime Health & Recovery slice.
 *
 * Pure Kotlin (no Android imports) so it stays unit-testable on the JVM.
 * `MainActivity` renders [HealthState]; `OpenCodeHost` supplies status/error.
 */
sealed interface HealthState {
    data object Installing : HealthState
    data object Starting : HealthState
    data object Waiting : HealthState
    data object Ready : HealthState
    data object Stopped : HealthState
    data class Failed(val code: String, val message: String) : HealthState
}

object RuntimeHealth {
    /** Only allow controlled installation stages into status/notifications. */
    fun installStatus(message: String): String = when {
        message == "Runtime extracted" -> "Runtime extracted"
        message == "Configuring Termux packages" -> "Configuring Termux packages"
        message.startsWith("Retrying interrupted initial installation") -> "Retrying runtime installation"
        message.startsWith("Recovering interrupted OpenCode upgrade") -> "Recovering OpenCode upgrade"
        message.startsWith("Upgrading OpenCode ") -> "Upgrading OpenCode"
        message.startsWith("Runtime ") && message.endsWith(" ready") -> "Termux runtime ready"
        message.startsWith("Termux runtime ") && message.endsWith(" ready") -> "Termux runtime ready"
        else -> "Preparing Termux runtime"
    }

    fun current(status: String, error: String?): HealthState {
        if (error != null) return HealthState.Failed(failureCode(error), error)
        val s = status.lowercase()
        return when {
            "ready" in s -> HealthState.Ready
            "waiting" in s -> HealthState.Waiting
            "starting" in s || "install" in s || "prepar" in s -> HealthState.Starting
            "stop" in s -> HealthState.Stopped
            else -> HealthState.Starting
        }
    }

    /** Stable codes for the health panel; never include raw exception text here. */
    fun failureCode(error: String?): String {
        if (error.isNullOrBlank()) return "E_UNKNOWN"
        val e = error.lowercase()
        return when {
            "runtime" in e || "prepare" in e -> "E_RUNTIME"
            "pair" in e || "ready" in e -> "E_PAIRING"
            "exit" in e || "stop" in e -> "E_STARTUP"
            else -> "E_STARTUP"
        }
    }

    /**
     * Strip secrets before any copy/share surface. Conservative on purpose:
     * pairing codes, passwords and auth headers are never diagnostics.
     */
    fun sanitize(text: String): String {
        var out = text
        out = out.replace(Regex("(?i)opencode_password=\\S+"), "OPENCODE_PASSWORD=REDACTED")
        out = out.replace(Regex("(?i)authorization\\s*[:=]\\s*(?:basic|bearer)\\s+\\S+"),
            "Authorization: REDACTED")
        out = out.replace(Regex("(?i)auth/connect/[A-Za-z0-9_-]+"), "auth/connect/REDACTED")
        out = out.replace(Regex("(?i)\"code\"\\s*:\\s*\"[^\"]+\""), "\"code\":\"REDACTED\"")
        // Handles JSON, dotenv, URL query strings, and common provider configuration names.
        out = out.replace(Regex("""(?i)(["']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|client[_-]?secret|password|secret)["']?\s*[:=]\s*["']?)[^&\s"']+""")) {
            it.groupValues[1] + "REDACTED"
        }
        return out
    }
}
