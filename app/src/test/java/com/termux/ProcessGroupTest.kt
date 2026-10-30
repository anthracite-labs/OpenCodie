package com.termux

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ProcessGroupTest {
    @Test fun timeoutStopsTheBootstrapShellAndItsPersistentChildBeforeRetry() {
        val root = Files.createTempDirectory("process-group").toFile()
        val groupFile = root.resolve("group")
        val childFile = root.resolve("child")
        val journal = BootstrapJournal(root) { true }
        val id = journal.begin()
        val script = root.resolve("bootstrap.sh")
        script.writeText("sleep 60 & child=\$!; echo \$child > '${childFile.path}'; trap 'wait; exit 0' TERM; wait \$child")
        val process = ProcessBuilder(BootstrapProcess.command("/usr/bin/setsid", "/bin/bash", script.path, groupFile.path, journal.ack(id).path)).start()
        try {
            BootstrapProcess.authorize(process, groupFile, journal, id, BootstrapProcess.currentGroup())
            val deadline = System.nanoTime() + 2_000_000_000L
            while ((!childFile.isFile || childFile.length() == 0L) && System.nanoTime() < deadline) Thread.sleep(5)
            val group = BootstrapProcess.groupId(groupFile, -1)!!
            val child = childFile.readText().trim().toLong()
            try {
                BootstrapProcess.await(process, 50) {
                    ProcessGroup.terminate(process,
                        signal = { signal -> ProcessBuilder("kill", "-$signal", "--", "-$group").start().waitFor() },
                        alive = { ProcessBuilder("kill", "-0", "--", "-$group").start().waitFor() == 0 })
                }
                fail("Timeout accepted")
            } catch (_: java.io.IOException) { }
            assertFalse(process.isAlive)
            assertFalse("Orphan bootstrap child can still mutate the prefix", ProcessHandle.of(child).map { it.isAlive }.orElse(false))
            BootstrapJournal(root) { group -> ProcessBuilder("kill", "-0", "--", "-$group").start().waitFor() == 0 }.recover()
            assertFalse(root.resolve(".opencodie-bootstrap").exists())
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
            FileOps.removeTree(root)
        }
    }

    @Test fun groupAuthorizationWaitsForMissingLauncherRecord() {
        val root = Files.createTempDirectory("pending-group").toFile()
        try {
            val missing = root.resolve("not-created-yet")
            assertNull(BootstrapProcess.groupId(missing, -1))
            missing.writeText("54321\\n")
            assertEquals(54321, BootstrapProcess.groupId(missing, -1))
        } finally { FileOps.removeTree(root) }
    }

}
