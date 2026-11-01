package com.termux

/**
 * Permission presentation and routing. Android remains the source of truth:
 * never use remembered button states as proof of a grant.
 *
 * OpenCodie targets API 28 for Termux executables. On Android 13+ the OS
 * controls the first notification dialog around creation of the first channel;
 * a further request for an existing, denied channel must open Settings.
 */
internal enum class PermissionButton { GRANT, ALLOWED, NOT_ALLOWED }
internal enum class NotificationAction { NONE, CREATE_CHANNEL, OPEN_SETTINGS }

internal object PermissionFlow {
    fun button(granted: Boolean, attempted: Boolean): PermissionButton = when {
        granted -> PermissionButton.ALLOWED
        attempted -> PermissionButton.NOT_ALLOWED
        else -> PermissionButton.GRANT
    }

    fun notificationAction(androidApi: Int, granted: Boolean, channelExists: Boolean): NotificationAction =
        when {
            granted -> NotificationAction.NONE
            androidApi >= 33 && !channelExists -> NotificationAction.CREATE_CHANNEL
            else -> NotificationAction.OPEN_SETTINGS
        }

    /** Offer the permission step once after a previously granted permission is revoked. */
    fun shouldReviewRevocation(
        completed: Boolean,
        previousStorage: Boolean,
        storageNow: Boolean,
        previousNotifications: Boolean,
        notificationsNow: Boolean,
    ): Boolean = completed && (
        previousStorage && !storageNow ||
        previousNotifications && !notificationsNow
    )

    /** Defer the foreground service (and notification channel) on fresh installs. */
    fun shouldStartHost(completed: Boolean): Boolean = completed
}
