package com.termux

import java.util.concurrent.TimeUnit

internal object ProcessGroup {
    /** No new installation may start until the isolated bootstrap group acknowledges shutdown. */
    fun terminate(process: Process, signal: (Int) -> Unit, alive: () -> Boolean) {
        var interrupted = Thread.interrupted()
        try {
            for (sig in listOf(15, 9)) {
                signal(sig)
                val deadline = System.nanoTime() + 3_000_000_000L
                do {
                    try { process.waitFor(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { interrupted = true }
                    if (!alive()) {
                        ChildProcess.terminate(process)
                        return
                    }
                    try { Thread.sleep(25) } catch (_: InterruptedException) { interrupted = true }
                } while (System.nanoTime() < deadline)
            }
            throw ChildProcess.Unterminated()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
