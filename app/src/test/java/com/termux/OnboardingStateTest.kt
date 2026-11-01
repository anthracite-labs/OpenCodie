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

    @Test fun onboardingMustBeCompletedAndWebUiLoadedBeforeReveal() {
        assertEquals(false, OnboardingState.canRevealWebView(false, false))
        assertEquals(false, OnboardingState.canRevealWebView(true, false))
        assertEquals(false, OnboardingState.canRevealWebView(false, true))
        assertEquals(true, OnboardingState.canRevealWebView(true, true))
    }

    @Test fun sharedStoragePromptRequiresOptInAndCompletedBootstrap() {
        assertEquals(false, OnboardingState.shouldPromptForStorage(false, true, false))
        assertEquals(false, OnboardingState.shouldPromptForStorage(true, false, false))
        assertEquals(false, OnboardingState.shouldPromptForStorage(true, true, true))
        assertEquals(true, OnboardingState.shouldPromptForStorage(true, true, false))
    }

    @Test fun connectionNeverClaimsReadyBeforeWebViewLoads() {
        assertEquals(SetupPhase.CONNECTING, OnboardingState.phase("Ready", null, true))
        assertEquals(SetupPhase.PREPARING, OnboardingState.phase("Preparing Termux runtime", null, false))
    }
    @Test fun stalePageIsNeverShownWhenHostRestartsOrFails() {
        assertEquals(false, OnboardingState.canRevealWebView(true, true, false, false))
        assertEquals(false, OnboardingState.canRevealWebView(true, true, true, true))
        assertEquals(true, OnboardingState.canRevealWebView(true, true, true, false))
    }

    @Test fun storageSetupDoesNotSilentlyLoopAfterAnError() {
        assertEquals(true, OnboardingState.shouldAttemptStorageLinks(true, true, false, false, false))
        assertEquals(false, OnboardingState.shouldAttemptStorageLinks(true, true, false, false, true))
        assertEquals(false, OnboardingState.shouldAttemptStorageLinks(true, true, false, true, false))
        assertEquals(false, OnboardingState.shouldAttemptStorageLinks(true, true, true, false, false))
    }

    @Test fun onlyLocalApplicationPagesQualifyForAuthenticationCheck() {
        assertEquals(true, OnboardingState.isWorkspaceUrl("http://127.0.0.1:4096/", "127.0.0.1", 4096))
        assertEquals(true, OnboardingState.isWorkspaceUrl("http://127.0.0.1:4096/project/demo", "127.0.0.1", 4096))
        assertEquals(false, OnboardingState.isWorkspaceUrl("http://127.0.0.1:4096/auth/connect/token", "127.0.0.1", 4096))
        assertEquals(false, OnboardingState.isWorkspaceUrl("http://127.0.0.1:4096/api/info", "127.0.0.1", 4096))
        assertEquals(false, OnboardingState.isWorkspaceUrl("http://example.com:4096/", "127.0.0.1", 4096))
        assertEquals(false, OnboardingState.isWorkspaceUrl("https://127.0.0.1:4096/", "127.0.0.1", 4096))
    }

    @Test fun preparationTitlesDoNotDependOnCurrentLocale() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertEquals("Installing developer tools", OnboardingState.preparationTitle("INSTALLING packages"))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

}
