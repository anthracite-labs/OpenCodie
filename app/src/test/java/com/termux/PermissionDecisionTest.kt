package com.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionDecisionTest {
    @Test fun requestIsPendingUntilAndroidReturns() {
        val state = PermissionDecision()
        assertTrue(state.begin(PermissionKind.NOTIFICATIONS))
        assertFalse(state.begin(PermissionKind.NOTIFICATIONS))
        assertEquals(PermissionStatus.REQUESTING, state.status(PermissionKind.NOTIFICATIONS, granted = false, everAsked = false))
        state.lostFocus()
        assertEquals(PermissionKind.NOTIFICATIONS, state.focusReturned())
        assertEquals(PermissionStatus.NOT_ENABLED, state.status(PermissionKind.NOTIFICATIONS, granted = false, everAsked = true))
    }

    @Test fun dismissalIsNeutralNotAnExplicitDenial() {
        val state = PermissionDecision()
        state.begin(PermissionKind.NOTIFICATIONS)
        assertEquals(PermissionStatus.REQUESTING, state.status(PermissionKind.NOTIFICATIONS, false, false))
        state.lostFocus()
        state.focusReturned()
        assertEquals(PermissionStatus.NOT_ENABLED, state.status(PermissionKind.NOTIFICATIONS, false, true))
    }

    @Test fun returnedGrantWinsAndClearsBusyState() {
        val state = PermissionDecision()
        state.begin(PermissionKind.STORAGE)
        state.finish(PermissionKind.STORAGE)
        assertEquals(PermissionStatus.ALLOWED, state.status(PermissionKind.STORAGE, granted = true, everAsked = true))
        assertTrue(state.begin(PermissionKind.STORAGE))
    }

    @Test fun permissionRequestStateRestoresAfterRotation() {
        val original = PermissionDecision()
        original.begin(PermissionKind.NOTIFICATIONS)
        val recreated = PermissionDecision(original.pending)
        assertEquals(PermissionStatus.REQUESTING, recreated.status(PermissionKind.NOTIFICATIONS, false, false))
        recreated.finish(PermissionKind.NOTIFICATIONS)
        assertEquals(PermissionStatus.NOT_ENABLED, recreated.status(PermissionKind.NOTIFICATIONS, false, true))
    }

    @Test fun initialGrantIsNotMisreportedDenied() {
        assertEquals(PermissionStatus.GRANT, PermissionDecision().status(PermissionKind.NOTIFICATIONS, false, false))
        assertEquals(PermissionStatus.ALLOWED, PermissionDecision().status(PermissionKind.NOTIFICATIONS, true, false))
    }
}
