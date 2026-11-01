package com.termux

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingStateTest {
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
}
