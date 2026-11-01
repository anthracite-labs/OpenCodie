package com.termux

/** A failed probe is not necessarily an expired browser session. */
internal enum class SessionValidation { VALID, EXPIRED, TRANSIENT }

/** Side-effect-free WebView recovery rules, exercised in JVM tests. */
internal object WebRecoveryPolicy {
    private const val MAX_RETRIES = 5
    private const val MAX_HISTORY = 20

    fun classifySessionHttp(code: Int?): SessionValidation = when (code) {
        200 -> SessionValidation.VALID
        301, 302, 303, 307, 308, 401, 403 -> SessionValidation.EXPIRED
        else -> SessionValidation.TRANSIENT
    }

    fun isRecoverableHttp(code: Int): Boolean =
        code == 408 || code == 429 || code >= 500

    fun canRetry(attempt: Int): Boolean = attempt in 1..MAX_RETRIES

    fun delayMs(attempt: Int): Long =
        (2_000L * (1L shl (attempt.coerceIn(1, 5) - 1))).coerceAtMost(30_000L)

    fun allowExternalScheme(scheme: String?): Boolean =
        scheme?.lowercase() in setOf("https", "http", "mailto")

    /** Never serialize one-time pairing codes or unrelated origins into Activity state. */
    fun safeHistory(urls: List<String>, host: String, port: Int): Boolean =
        urls.isNotEmpty() && urls.size <= MAX_HISTORY &&
            urls.all { OnboardingState.isWorkspaceUrl(it, host, port) }
}
