package com.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionFlowTest {
    @Test fun grantButtonsReflectActualPermissionAndPriorAttempt() {
        assertEquals(PermissionButton.GRANT, PermissionFlow.button(granted = false, attempted = false))
        assertEquals(PermissionButton.NOT_ALLOWED, PermissionFlow.button(granted = false, attempted = true))
        assertEquals(PermissionButton.ALLOWED, PermissionFlow.button(granted = true, attempted = false))
        assertEquals(PermissionButton.ALLOWED, PermissionFlow.button(granted = true, attempted = true))
    }

    @Test fun notificationFirstRequestCreatesChannelOnlyWhenNecessary() {
        assertEquals(
            NotificationAction.CREATE_CHANNEL,
            PermissionFlow.notificationAction(androidApi = 35, granted = false, channelExists = false),
        )
        assertEquals(
            NotificationAction.OPEN_SETTINGS,
            PermissionFlow.notificationAction(androidApi = 35, granted = false, channelExists = true),
        )
        assertEquals(
            NotificationAction.NONE,
            PermissionFlow.notificationAction(androidApi = 35, granted = true, channelExists = true),
        )
        assertEquals(
            NotificationAction.OPEN_SETTINGS,
            PermissionFlow.notificationAction(androidApi = 32, granted = false, channelExists = true),
        )
    }

    @Test fun revokedPermissionCanBeReviewedOnNextLaunchWithoutNaggingOverPriorDenials() {
        assertTrue(PermissionFlow.shouldReviewRevocation(
            completed = true, previousStorage = true, storageNow = false,
            previousNotifications = true, notificationsNow = true,
        ))
        assertTrue(PermissionFlow.shouldReviewRevocation(
            completed = true, previousStorage = false, storageNow = false,
            previousNotifications = true, notificationsNow = false,
        ))
        assertFalse(PermissionFlow.shouldReviewRevocation(
            completed = true, previousStorage = false, storageNow = false,
            previousNotifications = false, notificationsNow = false,
        ))
        assertFalse(PermissionFlow.shouldReviewRevocation(
            completed = false, previousStorage = true, storageNow = false,
            previousNotifications = true, notificationsNow = false,
        ))
    }

    @Test fun onboardingDoesNotStartHostUntilUserContinues() {
        assertFalse(PermissionFlow.shouldStartHost(completed = false))
        assertTrue(PermissionFlow.shouldStartHost(completed = true))
    }
}
