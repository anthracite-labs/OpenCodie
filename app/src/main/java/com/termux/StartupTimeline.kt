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
    val percent: Int? = null,
)

/** Lives in the host process; the UI reconstructs from a snapshot after rotation. */
internal class StartupTimeline {
    @Volatile private var event: StartupEvent = StartupEvent.PREPARING_RUNTIME
    @Volatile private var failure: Boolean = false

    @Synchronized fun advance(next: StartupEvent) {
        if (next.ordinal >= event.ordinal) {
            event = next
            failure = false
        }
    }

    @Synchronized fun fail() { failure = true }

    @Synchronized fun reset() {
        event = StartupEvent.PREPARING_RUNTIME
        failure = false
    }

    @Synchronized fun snapshot(): StartupSnapshot = describe(event).copy(failed = failure)

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
