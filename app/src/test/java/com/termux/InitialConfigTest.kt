package com.termux

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class InitialConfigTest {
    @Test fun customizedAndMalformedConfigsRemainByteIdentical() {
        val root = Files.createTempDirectory("config").toFile()
        try {
            val config = root.resolve("opencode.json")
            val original = byteArrayOf(0, 13, 10, -1, 42)
            config.writeBytes(original)
            assertFalse(InitialConfig.writeIfAbsent(config, "replacement"))
            assertArrayEquals(original, config.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun newConfigIsCreatedOnceAndDanglingSymlinkIsPreserved() {
        val root = Files.createTempDirectory("config").toFile()
        try {
            val config = root.resolve("new/opencode.json")
            assertTrue(InitialConfig.writeIfAbsent(config, "first"))
            assertFalse(InitialConfig.writeIfAbsent(config, "second"))
            assertEquals("first", config.readText())
            val link = root.resolve("link")
            Files.createSymbolicLink(link.toPath(), root.resolve("missing").toPath())
            assertFalse(InitialConfig.writeIfAbsent(link, "replacement"))
            assertFalse(root.resolve("missing").exists())
        } finally { root.deleteRecursively() }
    }
}
