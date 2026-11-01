package com.termux

/**
 * Presentation-only mapping for the native welcome screen.
 *
 * Installation, authentication and supervision remain owned by the existing
 * RuntimeInstaller/OpenCodeHost. Never derive progress percentages from text.
 */
internal enum class SetupPhase { PREPARING, CONNECTING, FAILED }

internal object OnboardingState {
    fun phase(status: String, error: String?, pairingReady: Boolean): SetupPhase {
        if (error != null) return SetupPhase.FAILED
        if (pairingReady || status.contains("Waiting for OpenCode", ignoreCase = true) ||
            status.equals("Ready", ignoreCase = true) ||
            status.contains("Starting OpenCode", ignoreCase = true)
        ) return SetupPhase.CONNECTING
        return SetupPhase.PREPARING
    }

    fun preparationTitle(status: String): String {
        val normalized = status.lowercase()
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
