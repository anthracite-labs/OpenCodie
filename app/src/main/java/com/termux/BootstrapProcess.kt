package com.termux

import java.io.IOException
import java.util.concurrent.TimeUnit

internal object BootstrapProcess {
    fun await(process: Process, timeoutMillis: Long = 180_000L) {
        val reader = Thread {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(2048)
                    while (input.read(buffer) >= 0) { /* Drain without retaining upstream secrets. */ }
                }
            } catch (_: IOException) { }
        }.apply { isDaemon = true; start() }
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) throw IOException("Termux package configuration timed out")
            if (process.exitValue() != 0) throw IOException("Termux package configuration failed (exit ${process.exitValue()})")
        } finally {
            ChildProcess.terminate(process)
            process.inputStream.close()
            reader.join(1_000)
        }
    }
}
