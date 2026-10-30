package com.termux

import java.util.concurrent.TimeUnit

internal object ChildProcess {
    /** Cleanup must complete even when a stop interrupted the supervisor's wait. */
    fun terminate(process: Process) {
        val interrupted = Thread.interrupted()
        try {
            if (!process.isAlive) return
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(3, TimeUnit.SECONDS)) { "Child did not acknowledge shutdown" }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
