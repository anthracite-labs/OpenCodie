package com.termux

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RuntimeArchiveFixtureTest {
    @Test fun genuineBundledRuntimePassesExtractionAndLinkValidation() {
        val path = System.getenv("OPENCODIE_RUNTIME_ARCHIVE")
        assumeTrue("Set OPENCODIE_RUNTIME_ARCHIVE to run the genuine fixture gate", path != null)
        val archive = File(path!!)
        assertTrue("Genuine runtime archive must exist", archive.isFile)
        val root = Files.createTempDirectory("genuine-runtime").toFile()
        try {
            val links = archive.inputStream().use { RuntimeArchive.extract(it, root) }
            assertTrue(links.isNotEmpty())
            assertTrue(root.resolve("bin/bash").isFile)
            assertTrue(root.resolve("libexec/opencode/opencode.bin").isFile)
        } finally { FileOps.removeTree(root) }
    }
}
