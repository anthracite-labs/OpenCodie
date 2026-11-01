package com.termux

/** Only the Android result or return to a focused Activity resolves a request. */
internal enum class PermissionKind { NOTIFICATIONS, STORAGE }
internal enum class PermissionStatus { GRANT, REQUESTING, ALLOWED, NOT_ENABLED }

internal class PermissionDecision(initialPending: PermissionKind? = null) {
    var pending: PermissionKind? = initialPending
        private set

    private var focusWasLost = false

    fun begin(kind: PermissionKind): Boolean {
        if (pending != null) return false
        pending = kind
        focusWasLost = false
        return true
    }

    fun lostFocus() {
        if (pending != null) focusWasLost = true
    }

    fun focusReturned(): PermissionKind? =
        if (focusWasLost) pending?.also { finish(it) } else null

    fun finish(kind: PermissionKind): Boolean {
        if (pending != kind) return false
        pending = null
        focusWasLost = false
        return true
    }

    fun status(kind: PermissionKind, granted: Boolean, everAsked: Boolean): PermissionStatus = when {
        pending == kind -> PermissionStatus.REQUESTING
        granted -> PermissionStatus.ALLOWED
        everAsked -> PermissionStatus.NOT_ENABLED
        else -> PermissionStatus.GRANT
    }
}
