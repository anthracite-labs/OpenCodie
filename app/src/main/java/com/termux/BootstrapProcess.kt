package com.termux

import java.io.IOException
import java.io.File
import java.util.concurrent.TimeUnit

internal object BootstrapProcess {
    fun command(setsid: String, bash: String, script: String, groupFile: String, ackFile: String): List<String> = listOf(
        setsid, "--wait", bash, "-c",
        """set -e; printf '%s\n' "${'$'}${'$'}" > "${'$'}1"; for attempt in {1..250}; do if [ -f "${'$'}4" ]; then exec "${'$'}2" "${'$'}3"; fi; sleep 0.02; done; exit 75""",
        "opencodie-bootstrap", groupFile, bash, script, ackFile,
    )

    fun authorize(process: Process, groupFile: File, journal: BootstrapJournal, id: String, parentGroup: Int): Int {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val group = groupId(groupFile, parentGroup)
            if (group != null) { journal.activate(id, group); return group }
            if (!process.isAlive) throw IOException("Bootstrap launcher stopped before authorization")
            Thread.sleep(10)
        }
        throw IOException("Bootstrap authorization timed out")
    }

    fun groupId(file: File, parentGroup: Int): Int? {
        // The launcher may not have published its group file yet.
        if (!file.exists()) return null
        if (java.nio.file.Files.isSymbolicLink(file.toPath()) || file.length() > 32) throw ChildProcess.Unterminated()
        val text = file.readText()
        if (text.isEmpty()) return null
        if (!text.endsWith("\n")) throw ChildProcess.Unterminated()
        val id = text.dropLast(1).toIntOrNull() ?: throw ChildProcess.Unterminated()
        if (id <= 1 || id == parentGroup) throw ChildProcess.Unterminated()
        return id
    }

    fun currentGroup(): Int = try {
        File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[2].toInt()
    } catch (_: Exception) { throw ChildProcess.Unterminated() }

    fun await(process: Process, timeoutMillis: Long = 180_000L, cleanup: () -> Unit = { ChildProcess.terminate(process) }) {
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
            cleanup()
            process.inputStream.close()
            reader.join(1_000)
        }
    }
}
