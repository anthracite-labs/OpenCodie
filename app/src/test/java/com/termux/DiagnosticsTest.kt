package com.termux

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @Test fun upstreamSecretsNeverBecomeLogOrUiMessages() {
        val secrets = listOf("local-password-value", "session-cookie-value", "pairing-code-value", "api-key-value")
        val lines = listOf(
            "Authorization: Basic local-password-value Cookie: session-cookie-value",
            "http://127.0.0.1:4096/auth/connect/pairing-code-value?token=api-key-value",
            "EADDRINUSE; Authorization: local-password-value; Cookie: session-cookie-value",
            "x".repeat(100_000) + "api-key-value",
        )
        for (line in lines) {
            val rendered = Diagnostics.fromOutput(line).orEmpty() + Diagnostics.failure(IllegalStateException(line))
            for (secret in secrets) assertFalse(rendered.contains(secret))
            assertTrue(rendered.length < 400)
        }
    }

    @Test fun fixedCategoriesRemainActionable() {
        assertTrue(Diagnostics.fromOutput("listen failed: EADDRINUSE")!!.contains("port"))
        assertTrue(Diagnostics.failure(java.io.IOException("ENOSPC /private/path")).contains("storage"))
        assertTrue(Diagnostics.failure(IllegalStateException("Unverified existing Termux prefix preserved")).contains("preserved"))
    }

    @Test fun bootstrapLauncherAndUnknownIoAreNotMisdiagnosedAsFullStorage() {
        val launcher = Diagnostics.failure(java.io.IOException("Bootstrap launcher stopped before authorization"))
        assertTrue(launcher.contains("launcher"))
        assertFalse(launcher.contains("free storage"))
        val other = Diagnostics.failure(java.io.IOException("Unknown device I/O failure"))
        assertFalse(other.contains("free storage"))
        assertTrue(other.contains("setup"))
    }

    @Test fun nestedIoCausesAreCategorizedWithoutLeakingRawMessages() {
        val error = IllegalStateException("Unsafe or unreadable bundled runtime archive",
            java.io.IOException("ENOSPC; credential=local-secret"))
        val safe = Diagnostics.failure(error)
        assertTrue(safe.contains("storage is full"))
        assertFalse(safe.contains("local-secret"))
    }
}
