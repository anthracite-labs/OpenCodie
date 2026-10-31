package com.termux

/**
 * Picks the WebUI destination for a new Android WebView instance.
 *
 * The host's startup pairing URL is a one-time code and must never be replayed
 * after another Activity might have consumed it. Prefer an authenticated
 * persisted WebView cookie; mint a fresh one-time link when needed.
 *
 * All suppliers may perform I/O and this selector must run off the UI thread.
 */
internal class WebUiSessionSelector(
    private val rootUrl: String,
    private val readCookies: () -> String?,
    private val verifySession: (String) -> Boolean,
    private val newPairingLink: () -> String,
) {
    fun destinationUrl(): String {
        val cookies = runCatching { readCookies() }.getOrNull()
        if (!cookies.isNullOrBlank() && runCatching { verifySession(cookies) }.getOrDefault(false)) {
            return rootUrl
        }
        // Never return a previously published or redeemed pairing URL.
        // Propagate pairing failures: the Activity will retry with backoff.
        return newPairingLink()
    }
}
