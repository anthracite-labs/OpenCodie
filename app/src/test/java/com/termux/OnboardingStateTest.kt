package com.termux

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingStateTest {
    @Test fun errorsAreShownBeforeAnyConnectionProgress() {
        assertEquals(
            SetupPhase.FAILED,
            OnboardingState.phase("Waiting for OpenCode", "Failed to start", true),
        )
    }

    @Test fun readinessTransitionsToConnecting() {
        assertEquals(SetupPhase.CONNECTING, OnboardingState.phase("Ready", null, true))
        assertEquals(SetupPhase.CONNECTING, OnboardingState.phase("Waiting for OpenCode", null, false))
    }

    @Test fun installerStatusesProduceHumanFriendlyCopy() {
        assertEquals(
            "Unpacking your workspace",
            OnboardingState.preparationTitle("Extracting Termux runtime"),
        )
        assertEquals(
            "Installing developer tools",
            OnboardingState.preparationTitle("Running Termux package setup"),
        )
        assertEquals(
            "Preparing your workspace",
            OnboardingState.preparationTitle("Starting"),
        )
    }

    @Test fun connectionNeverClaimsReadyBeforeWebViewLoads() {
        assertEquals(SetupPhase.CONNECTING, OnboardingState.phase("Ready", null, true))
        assertEquals(SetupPhase.PREPARING, OnboardingState.phase("Preparing Termux runtime", null, false))
    }
}
