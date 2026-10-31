package com.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Relaunch authentication decisions; no live server, cookies or Termux files. */
class WebUiSessionSelectorTest {
    private val root = "http://127.0.0.1:4096/"

    @Test
    fun relaunchWithValidPersistedCookie_goesDirectlyToWebUiWithoutRePairing() {
        var pairs = 0
        val selector = WebUiSessionSelector(
            rootUrl = root,
            readCookies = { "opencode_session=existing" },
            verifySession = { cookie -> cookie == "opencode_session=existing" },
            newPairingLink = { pairs++; "http://127.0.0.1:4096/auth/connect/new" },
        )

        assertEquals(root, selector.destinationUrl())
        assertEquals(0, pairs)
    }

    @Test
    fun relaunchWithMissingCookie_mintsNewLinkInsteadOfReusingConsumedStartupLink() {
        val previouslyRedeemed = "http://127.0.0.1:4096/auth/connect/consumed"
        var pairs = 0
        val selector = WebUiSessionSelector(
            rootUrl = root,
            readCookies = { null },
            verifySession = { error("must not check absent cookie") },
            newPairingLink = {
                pairs++
                "http://127.0.0.1:4096/auth/connect/fresh"
            },
        )

        val destination = selector.destinationUrl()
        assertEquals("http://127.0.0.1:4096/auth/connect/fresh", destination)
        assertFalse(previouslyRedeemed == destination)
        assertEquals(1, pairs)
    }

    @Test
    fun invalidOrExpiredSessionCookie_isReplacedAutomatically() {
        var checked = false
        val selector = WebUiSessionSelector(
            rootUrl = root,
            readCookies = { "opencode_session=expired" },
            verifySession = { checked = true; false },
            newPairingLink = { "http://127.0.0.1:4096/auth/connect/replacement" },
        )

        assertEquals("http://127.0.0.1:4096/auth/connect/replacement", selector.destinationUrl())
        assertTrue(checked)
    }

    @Test
    fun failedCookieValidation_fallsBackToNewPairingLink() {
        val selector = WebUiSessionSelector(
            rootUrl = root,
            readCookies = { "opencode_session=existing" },
            verifySession = { throw java.io.IOException("temporarily unavailable") },
            newPairingLink = { "http://127.0.0.1:4096/auth/connect/new-after-failure" },
        )

        assertEquals(
            "http://127.0.0.1:4096/auth/connect/new-after-failure",
            selector.destinationUrl(),
        )
    }

    @Test
    fun failedPairingCreation_neverReturnsReplayedLinkOrSilentSuccess() {
        var attempts = 0
        val selector = WebUiSessionSelector(
            rootUrl = root,
            readCookies = { "" },
            verifySession = { error("should not validate blank cookie") },
            newPairingLink = {
                attempts++
                throw java.io.IOException("server is restarting")
            },
        )

        try {
            selector.destinationUrl()
            throw AssertionError("Expected the caller to retry on the next poll")
        } catch (expected: java.io.IOException) {
            assertEquals("server is restarting", expected.message)
        }
        assertEquals(1, attempts)
    }
}
