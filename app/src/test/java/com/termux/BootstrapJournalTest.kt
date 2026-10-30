package com.termux

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BootstrapJournalTest {
    @Test fun processDeathBeforeAuthorizationCannotLeaveAnExecutableOldBootstrap() {
        val root = Files.createTempDirectory("journal").toFile()
        try {
            val old = BootstrapJournal(root) { fail("Pending launcher has no execution permission"); true }
            val id = old.begin()
            BootstrapJournal(root) { true }.recover()
            assertFalse(old.ack(id).exists())
            assertFalse(root.resolve(".opencodie-bootstrap").exists())
        } finally { FileOps.removeTree(root) }
    }

    @Test fun appRestartPreservesPrefixUntilThePersistedGroupIsConfirmedGone() {
        val root = Files.createTempDirectory("journal").toFile()
        try {
            root.resolve("usr").mkdirs()
            root.resolve("usr/package").writeText("keep")
            val old = BootstrapJournal(root) { true }
            val id = old.begin()
            old.activate(id, 12345)
            try { BootstrapJournal(root) { true }.recover(); fail("Live or reused group accepted") }
            catch (_: IllegalStateException) { }
            assertEquals("keep", root.resolve("usr/package").readText())
            BootstrapJournal(root) { false }.recover()
            assertFalse(old.ack(id).exists())
        } finally { FileOps.removeTree(root) }
    }
}
