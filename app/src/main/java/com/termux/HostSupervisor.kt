package com.termux

/** One owner retires completely before a requested replacement may start. */
internal class HostSupervisor(
    private val onCrash: () -> Unit = {},
    private val run: (Cancellation) -> Unit,
) {
    internal class Cancellation {
        @Volatile var cancelled = false
            private set
        @Volatile var restartAllowed = true
        private var child: Process? = null

        @Synchronized fun attach(process: Process) {
            child = process
            if (cancelled) process.destroy()
        }

        @Synchronized fun cancel() {
            cancelled = true
            child?.destroy()
        }
    }
    private var worker: Thread? = null
    private var cancellation: Cancellation? = null
    private var requested = false
    private var retirementFailed = false

    @Synchronized fun start() {
        if (retirementFailed) return
        requested = true
        if (worker != null) return
        launch()
    }

    private fun launch() {
        val token = Cancellation()
        cancellation = token
        worker = Thread {
            try {
                run(token)
            } catch (_: Exception) {
                // An unexpected owner failure cannot silently strand the host or start another
                // owner before the old child is known to be gone.
                if (!token.cancelled) {
                    token.restartAllowed = false
                    try { onCrash() } catch (_: Exception) { /* Keep the original failure isolated. */ }
                }
            } finally {
                synchronized(this) {
                    if (!token.restartAllowed) retirementFailed = true
                    worker = null
                    if (requested && token.cancelled && token.restartAllowed) launch()
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() {
        requested = false
        cancellation?.cancel()
        worker?.interrupt()
    }
}
