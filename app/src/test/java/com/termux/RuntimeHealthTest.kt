package com.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM checks for the health slice; no device, server or Termux files. */
class RuntimeHealthTest {
    @Test
    fun readyMapsToReadyWithoutError() {
        assertEquals(HealthState.Ready, RuntimeHealth.current("Ready", null))
    }

    @Test
    fun waitingAndStartingMapToStages() {
        assertEquals(HealthState.Waiting, RuntimeHealth.current("Waiting for OpenCode", null))
        assertEquals(HealthState.Starting, RuntimeHealth.current("Starting OpenCode", null))
    }

    @Test
    fun errorAlwaysBeatsStatusWithStableCode() {
        val state = RuntimeHealth.current("Ready", "OpenCode stopped: boom")
        assertTrue(state is HealthState.Failed)
        assertEquals("E_STARTUP", (state as HealthState.Failed).code)
    }

    @Test
    fun blankErrorMapsToUnknownCode() {
        assertEquals("E_UNKNOWN", RuntimeHealth.failureCode(null))
        assertEquals("E_UNKNOWN", RuntimeHealth.failureCode("   "))
    }

    @Test
    fun sanitizeRedactsSecretsButKeepsStages() {
        val raw = "OPENCODE_PASSWORD=hunter2\nGET /auth/connect/abc123\nAuthorization: Basic b3BlbmNvZGU6eHh4\nstatus=Waiting for OpenCode"
        val clean = RuntimeHealth.sanitize(raw)
        assertTrue("hunter2" !in clean)
        assertTrue("abc123" !in clean)
        assertTrue("b3BlbmNvZGU6eHh4" !in clean)
        assertTrue("Waiting for OpenCode" in clean)
        assertTrue("REDACTED" in clean)
    }

    @Test
    fun installerProgressNeverDisplaysUntrustedOutput() {
        assertEquals("Preparing Termux runtime", RuntimeHealth.installStatus("OPENCODE_PASSWORD=secret"))
        assertEquals("Preparing Termux runtime", RuntimeHealth.installStatus("Authorization: Basic secret"))
        assertEquals("Preparing Termux runtime", RuntimeHealth.installStatus("bootstrap emitted a user path"))
        assertEquals("Runtime extracted", RuntimeHealth.installStatus("Runtime extracted"))
        assertEquals("Configuring Termux packages", RuntimeHealth.installStatus("Configuring Termux packages"))
        assertEquals("Upgrading OpenCode", RuntimeHealth.installStatus("Upgrading OpenCode old -> new"))
    }

    @Test
    fun sanitizeRedactsProviderCredentialsAndBearerHeaders() {
        val raw = """
            Authorization: Bearer very-secret-token
            apiKey="sk_provider_private"
            "api_key": "provider-secret"
            token=another-provider-token
            https://provider.example/v1?api_key=url-secret&foo=bar
        """.trimIndent()
        val clean = RuntimeHealth.sanitize(raw)
        listOf("very-secret-token", "sk_provider_private", "provider-secret",
            "another-provider-token", "url-secret").forEach {
            assertTrue("Leaked credential: $it", it !in clean)
        }
    }
}
