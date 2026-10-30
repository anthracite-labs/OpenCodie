package com.termux

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class StorageLinksTest {
    @Test fun preflightConflictLeavesExistingFilesAndMissingLinksUnchanged() {
        val root = Files.createTempDirectory("storage").toFile()
        try {
            val storage = root.resolve("storage").apply { mkdirs() }
            storage.resolve("documents").writeText("user data")
            try {
                StorageLinks.setup(storage, linkedMapOf("shared" to root.resolve("shared"), "documents" to root.resolve("documents")))
                fail("Conflicting user file was replaced")
            } catch (_: IllegalStateException) { }
            assertEquals("user data", storage.resolve("documents").readText())
            assertFalse(Files.exists(storage.resolve("shared").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        } finally { FileOps.removeTree(root) }
    }

    @Test fun partialFailureRemovesOnlyNewLinksAndRetryIsIdempotent() {
        val root = Files.createTempDirectory("storage").toFile()
        try {
            val storage = root.resolve("storage").apply { mkdirs() }
            val target = root.resolve("target")
            Files.createSymbolicLink(storage.resolve("shared").toPath(), target.toPath())
            val links = linkedMapOf("shared" to target, "documents" to target, "downloads" to target)
            try {
                StorageLinks.setup(storage, links) { if (it == "downloads") throw IOException("simulated") }
                fail("Injected failure did not occur")
            } catch (_: IOException) { }
            assertTrue(Files.isSymbolicLink(storage.resolve("shared").toPath()))
            assertFalse(Files.exists(storage.resolve("documents").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            StorageLinks.setup(storage, links)
            StorageLinks.setup(storage, links)
            assertEquals(3, storage.listFiles()!!.size)
        } finally { FileOps.removeTree(root) }
    }
}
