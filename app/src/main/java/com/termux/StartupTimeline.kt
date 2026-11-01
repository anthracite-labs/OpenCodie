package com.termux

/** Typed, verified milestones, not parsed logs or estimated percentages. */
internal enum class StartupEvent {
    PREPARING_RUNTIME,
    VERIFYING_RUNTIME,
    UNPACKING_RUNTIME,
    CONFIGURING_PACKAGES,
    VERIFYING_INSTALL,
    RUNTIME_READY,
    STARTING_SERVER,
    WAITING_FOR_SERVER,
    SERVER_READY,
    WEBVIEW_READY,
}

internal enum class StartupStep { RUNTIME, PACKAGES, SERVER, WEBVIEW, READY }

internal data class StartupSnapshot(
    val event: StartupEvent,
    val active: StartupStep,
    val completedCount: Int,
    val failed: Boolean = false,
    val retrying: Boolean = false,
    /** Safe, preclassified reason without exception text, passwords or pairing links. */
    val lastFailure: String? = null,
    val percent: Int? = null,
)

/** Lives in the host process; the UI reconstructs from a snapshot after rotation. */
internal class StartupTimeline {
    @Volatile private var event: StartupEvent = StartupEvent.PREPARING_RUNTIME
    @Volatile private var failure: Boolean = false
    @Volatile private var retryingServer = false
    @Volatile private var lastFailureReason: String? = null

    @Synchronized fun advance(next: StartupEvent) {
        if (next.ordinal >= event.ordinal) {
            event = next
            failure = false
            // Only verified server readiness clears "retrying": starting
            // another process is not evidence the recovery succeeded.
            if (next == StartupEvent.SERVER_READY || next == StartupEvent.WEBVIEW_READY) {
                retryingServer = false
            }
        }
    }

    /** A crashed/restarted server legitimately returns to the server stage. */
    @Synchronized fun restartServer() {
        if (event.ordinal >= StartupEvent.RUNTIME_READY.ordinal) {
            event = StartupEvent.STARTING_SERVER
            failure = false
        }
    }

    @Synchronized fun noteServerFailure(reason: String) {
        // Known, controlled stage text only; never pass through exception details.
        lastFailureReason = reason
        retryingServer = true
        failure = false
        if (event.ordinal >= StartupEvent.RUNTIME_READY.ordinal) {
            event = StartupEvent.STARTING_SERVER
        }
    }

    @Synchronized fun fail() {
        failure = true
        retryingServer = false
    }

    @Synchronized fun reset() {
        event = StartupEvent.PREPARING_RUNTIME
        failure = false
        retryingServer = false
        lastFailureReason = null
    }

    @Synchronized fun snapshot(): StartupSnapshot = describe(event).copy(
        failed = failure, retrying = retryingServer, lastFailure = lastFailureReason,
    )

    companion object {
        fun describe(event: StartupEvent): StartupSnapshot {
            val active = when (event) {
                StartupEvent.PREPARING_RUNTIME, StartupEvent.VERIFYING_RUNTIME,
                StartupEvent.UNPACKING_RUNTIME -> StartupStep.RUNTIME
                StartupEvent.CONFIGURING_PACKAGES, StartupEvent.VERIFYING_INSTALL -> StartupStep.PACKAGES
                StartupEvent.RUNTIME_READY, StartupEvent.STARTING_SERVER,
                StartupEvent.WAITING_FOR_SERVER -> StartupStep.SERVER
                StartupEvent.SERVER_READY -> StartupStep.WEBVIEW
                StartupEvent.WEBVIEW_READY -> StartupStep.READY
            }
            val completed = when (active) {
                StartupStep.RUNTIME -> 0
                StartupStep.PACKAGES -> 1
                StartupStep.SERVER -> 2
                StartupStep.WEBVIEW -> 3
                StartupStep.READY -> 4
            }
            // None of these tasks reports measured units of total work.
            return StartupSnapshot(event, active, completed)
        }
    }
}
