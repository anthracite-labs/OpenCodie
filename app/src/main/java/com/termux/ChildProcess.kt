package com.termux

import java.util.concurrent.TimeUnit

internal object ChildProcess {
    class Unterminated : IllegalStateException("Child did not acknowledge shutdown")
    /** Cleanup must complete even when a stop interrupted the supervisor's wait. */
    fun terminate(process: Process) {
        var interrupted = Thread.interrupted()
        fun awaitExit(): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (process.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                try {
                    if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) return true
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            return !process.isAlive
        }

        try {
            if (!process.isAlive) return
            process.destroy()
            if (!awaitExit()) {
                process.destroyForcibly()
                if (!awaitExit()) throw Unterminated()
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
