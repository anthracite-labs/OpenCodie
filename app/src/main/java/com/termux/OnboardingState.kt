package com.termux

import java.net.URI
import java.util.Locale

/**
 * Presentation-only mapping for the native welcome screen.
 *
 * Installation, authentication and supervision remain owned by the existing
 * RuntimeInstaller/OpenCodeHost. Never derive progress percentages from text.
 */
internal enum class SetupPhase { PREPARING, CONNECTING, FAILED }

internal object OnboardingState {
    fun canRevealWebView(
        onboardingCompleted: Boolean,
        webPageReady: Boolean,
        hostReady: Boolean = true,
        hostFailed: Boolean = false,
    ): Boolean = onboardingCompleted && webPageReady && hostReady && !hostFailed

    /** Retry after FAILED is deliberately user driven, never a poll-loop. */
    fun shouldAttemptStorageLinks(
        runtimeReady: Boolean,
        permissionGranted: Boolean,
        linksReady: Boolean,
        inFlight: Boolean,
        lastAttemptFailed: Boolean,
    ): Boolean = runtimeReady && permissionGranted && !linksReady && !inFlight && !lastAttemptFailed

    /** A load event is only a candidate: the session must ALSO validate. */
    fun isWorkspaceUrl(url: String, host: String, port: Int): Boolean = runCatching {
        val parsed = URI(url)
        val path = parsed.path ?: return@runCatching false
        parsed.scheme == "http" && parsed.host == host && parsed.port == port &&
            (path == "/" || path.startsWith("/project/") || path.startsWith("/workspace/"))
    }.getOrDefault(false)

    fun shouldPromptForStorage(runtimeReady: Boolean, optedIn: Boolean, alreadyPrompted: Boolean): Boolean =
        runtimeReady && optedIn && !alreadyPrompted

    fun phase(status: String, error: String?, pairingReady: Boolean): SetupPhase {
        if (error != null) return SetupPhase.FAILED
        if (pairingReady || status.contains("Waiting for OpenCode", ignoreCase = true) ||
            status.equals("Ready", ignoreCase = true) ||
            status.contains("Starting OpenCode", ignoreCase = true)
        ) return SetupPhase.CONNECTING
        return SetupPhase.PREPARING
    }

    fun preparationTitle(status: String): String {
        val normalized = status.lowercase(Locale.ROOT)
        return when {
            normalized.contains("extract") || normalized.contains("unpack") ->
                "Unpacking your workspace"
            normalized.contains("package") || normalized.contains("second stage") ||
                normalized.contains("configur") || normalized.contains("installing") ->
                "Installing developer tools"
            normalized.contains("upgrad") -> "Updating your tools"
            else -> "Preparing your workspace"
        }
    }
}
