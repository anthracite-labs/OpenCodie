package com.termux

import java.util.concurrent.TimeUnit

internal object ChildProcess {
    class Unterminated : IllegalStateException("Child did not acknowledge shutdown")
    /** Cleanup must complete even when a stop interrupted the supervisor's wait. */
    fun terminate(process: Process) {
        val interrupted = Thread.interrupted()
        try {
            if (!process.isAlive) return
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                if (!process.waitFor(3, TimeUnit.SECONDS)) throw Unterminated()
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
