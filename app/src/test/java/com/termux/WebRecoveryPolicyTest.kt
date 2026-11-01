package com.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRecoveryPolicyTest {
    @Test fun transientHttpErrorsRetryButMissingRoutesDoNotBlankWorkspace() {
        assertTrue(WebRecoveryPolicy.isRecoverableHttp(503))
        assertTrue(WebRecoveryPolicy.isRecoverableHttp(429))
        assertTrue(WebRecoveryPolicy.isRecoverableHttp(408))
        assertFalse(WebRecoveryPolicy.isRecoverableHttp(404))
    }

    @Test fun sessionValidationDistinguishesExpiredAndTemporaryFailure() {
        assertEquals(SessionValidation.VALID, WebRecoveryPolicy.classifySessionHttp(200))
        assertEquals(SessionValidation.EXPIRED, WebRecoveryPolicy.classifySessionHttp(401))
        assertEquals(SessionValidation.EXPIRED, WebRecoveryPolicy.classifySessionHttp(403))
        assertEquals(SessionValidation.TRANSIENT, WebRecoveryPolicy.classifySessionHttp(null))
        assertEquals(SessionValidation.TRANSIENT, WebRecoveryPolicy.classifySessionHttp(503))
    }

    @Test fun retriesAreBoundedWithBackoff() {
        assertEquals(2_000L, WebRecoveryPolicy.delayMs(1))
        assertEquals(4_000L, WebRecoveryPolicy.delayMs(2))
        assertEquals(8_000L, WebRecoveryPolicy.delayMs(3))
        assertTrue(WebRecoveryPolicy.canRetry(1))
        assertFalse(WebRecoveryPolicy.canRetry(6))
    }

    @Test fun onlySafeExternalSchemesCanBeDispatched() {
        assertTrue(WebRecoveryPolicy.allowExternalScheme("https"))
        assertTrue(WebRecoveryPolicy.allowExternalScheme("mailto"))
        assertFalse(WebRecoveryPolicy.allowExternalScheme("file"))
        assertFalse(WebRecoveryPolicy.allowExternalScheme("intent"))
        assertFalse(WebRecoveryPolicy.allowExternalScheme(null))
    }

    @Test fun historyRejectsOneTimePairingAndUnrelatedOrigins() {
        assertTrue(WebRecoveryPolicy.safeHistory(
            listOf("http://127.0.0.1:4096/", "http://127.0.0.1:4096/project/hello"),
            "127.0.0.1", 4096))
        assertFalse(WebRecoveryPolicy.safeHistory(
            listOf("http://127.0.0.1:4096/auth/connect/secret"),
            "127.0.0.1", 4096))
        assertFalse(WebRecoveryPolicy.safeHistory(
            listOf("https://example.com/"), "127.0.0.1", 4096))
        assertFalse(WebRecoveryPolicy.safeHistory(
            List(21) { "http://127.0.0.1:4096/project/test$it" },
            "127.0.0.1", 4096))
    }
}
