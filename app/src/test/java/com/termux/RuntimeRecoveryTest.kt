package com.termux

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RuntimeRecoveryTest {
    private class Crash : Error()

    @Test fun recoveryNeverTraversesARelocatedUserDirectory() {
        val root = Files.createTempDirectory("recovery").toFile()
        val external = Files.createTempDirectory("user-data").toFile()
        try {
            val prefix = root.resolve("usr").apply { mkdirs() }
            external.resolve("opencode").mkdirs()
            external.resolve("opencode/keep").writeText("user")
            Files.createSymbolicLink(prefix.resolve("libexec").toPath(), external.toPath())
            root.resolve("opencode-backup").mkdirs()
            root.resolve("opencode-backup/old").writeText("old")
            try { RuntimeUpgrade(root, prefix).recover(false, "old"); fail("External recovery path accepted") }
            catch (_: IllegalStateException) { }
            assertEquals("user", external.resolve("opencode/keep").readText())
            assertEquals("old", root.resolve("opencode-backup/old").readText())
        } finally { FileOps.removeTree(root); FileOps.removeTree(external) }
    }

    @Test fun everyUpgradeInterruptionRecoversOldOrCommittedNewBytes() {
        for (point in listOf("launcher-backed-up", "binary-backed-up", "binary-activated",
            "launcher-staged", "launcher-activated", "version-written", "ready-written", "backup-cleared")) {
            val root = Files.createTempDirectory("upgrade").toFile()
            try {
                val prefix = root.resolve("usr")
                prefix.resolve("libexec/opencode").mkdirs()
                prefix.resolve("libexec/opencode/opencode.bin").writeText("old binary")
                prefix.resolve("bin").mkdirs()
                prefix.resolve("bin/opencode").apply { writeText("old launcher"); setExecutable(true) }
                val custom = prefix.resolve("user-package").apply { writeText("user bytes") }
                val home = root.resolve("home").apply { mkdirs() }
                home.resolve("custom.json").writeText("custom config")
                root.resolve(".opencodie-ready").writeText("old\n")
                val staging = root.resolve("staging")
                staging.resolve("libexec/opencode").mkdirs()
                staging.resolve("libexec/opencode/opencode.bin").writeText("new binary")
                staging.resolve("bin").mkdirs()
                staging.resolve("bin/opencode").apply { writeText("new launcher"); setExecutable(true) }
                try {
                    RuntimeUpgrade(root, prefix) { if (it == point) throw Crash() }.install(staging, "new")
                    fail("Fault point was not reached: $point")
                } catch (_: Crash) { }
                val committed = root.resolve(".opencodie-ready").readText().trim() == "new"
                RuntimeUpgrade(root, prefix).recover(committed, if (committed) "new" else "old")
                val expected = if (committed) "new" else "old"
                assertEquals("$point binary", "$expected binary", prefix.resolve("libexec/opencode/opencode.bin").readText())
                assertEquals("$point launcher", "$expected launcher", prefix.resolve("bin/opencode").readText())
                assertTrue(prefix.resolve("bin/opencode").canExecute())
                assertEquals("user bytes", custom.readText())
                assertEquals("custom config", home.resolve("custom.json").readText())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun interruptionDuringRollbackCanBeRetried() {
        val root = Files.createTempDirectory("rollback").toFile()
        try {
            val prefix = root.resolve("usr")
            prefix.resolve("libexec/opencode").mkdirs()
            prefix.resolve("libexec/opencode/opencode.bin").writeText("new")
            prefix.resolve("bin").mkdirs()
            prefix.resolve("bin/opencode").writeText("new")
            root.resolve("opencode-backup").mkdirs()
            root.resolve("opencode-backup/opencode.bin").writeText("old")
            root.resolve("opencode-launcher-backup").writeText("old")
            try {
                RuntimeUpgrade(root, prefix) { if (it == "launcher-restored") throw Crash() }.recover(false, "old")
            } catch (_: Crash) { }
            RuntimeUpgrade(root, prefix).recover(false, "old")
            assertEquals("old", prefix.resolve("bin/opencode").readText())
            assertEquals("old", prefix.resolve("libexec/opencode/opencode.bin").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun oldOrUnverifiedInitialInstallMarkerNeverAuthorizesDeletion() {
        val root = Files.createTempDirectory("install").toFile()
        try {
            root.resolve("usr").mkdirs()
            root.resolve("usr/package").writeText("user package")
            root.resolve(".opencodie-installing").writeText("v2.2.2\n")
            try { InitialInstall(root).prepare(); fail("Unknown prefix erased") } catch (_: IllegalStateException) { }
            assertEquals("user package", root.resolve("usr/package").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun ownedIncompletePrefixIsRetriedWithoutFollowingSymlinks() {
        val root = Files.createTempDirectory("install").toFile()
        val external = Files.createTempDirectory("user-data").toFile()
        try {
            val installer = InitialInstall(root)
            val id = installer.prepare()
            val prefix = root.resolve("usr").apply { mkdirs() }
            installer.stamp(prefix, id)
            external.resolve("keep").writeText("user")
            Files.createSymbolicLink(prefix.resolve("link").toPath(), external.toPath())
            installer.prepare()
            assertFalse(prefix.exists())
            assertEquals("user", external.resolve("keep").readText())
        } finally { root.deleteRecursively(); external.deleteRecursively() }
    }

    @Test fun stalledBootstrapIsTerminatedByDeadline() {
        val child = ProcessBuilder("sh", "-c", "exec sleep 60").start()
        val start = System.nanoTime()
        try { BootstrapProcess.await(child, 50); fail("Stalled bootstrap accepted") } catch (_: IOException) { }
        assertFalse(child.isAlive)
        assertTrue(System.nanoTime() - start < 8_000_000_000L)
    }
}
