package com.termux

import java.net.URI

/**
 * Pure state helpers for the native welcome and runtime screens.
 *
 * Installation, authentication and supervision remain owned by the existing
 * RuntimeInstaller/OpenCodeHost. Never derive progress percentages from text.
 */
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
}
