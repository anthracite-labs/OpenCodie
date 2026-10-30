package com.termux

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class RuntimeArchiveTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, value) in entries) {
                out.putNextEntry(ZipEntry(name)); out.write(value.toByteArray()); out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun reject(bytes: ByteArray, limits: RuntimeArchive.Limits = RuntimeArchive.Limits()) {
        val root = Files.createTempDirectory("archive").toFile()
        try {
            try { RuntimeArchive.extract(ByteArrayInputStream(bytes), root.resolve("staging"), limits = limits); fail("Unsafe ZIP accepted") }
            catch (_: IllegalStateException) { }
            assertFalse(root.resolve("escape").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsTraversalAbsoluteAliasesAndTypeCollisions() {
        for (path in listOf("../escape", "/escape", "a/../../escape", "a//b", "a/./b", "a\\b", ".opencodie-install-id")) {
            reject(zip(path to "bad", "SYMLINKS.txt" to ""))
        }
        reject(zip("bin" to "file", "bin/child" to "bad", "SYMLINKS.txt" to ""))
        reject(zip("bin/child" to "bad", "bin" to "file", "SYMLINKS.txt" to ""))
        val duplicate = zip("bin/a" to "a", "bin/b" to "b", "SYMLINKS.txt" to "")
        val renamed = String(duplicate, Charsets.ISO_8859_1).replace("bin/b", "bin/a").toByteArray(Charsets.ISO_8859_1)
        reject(renamed)
    }

    @Test fun rejectsSymlinkEscapesParentsCollisionsAndCycles() {
        for (links in listOf("../../escape←bin/link", "/storage/emulated/0←bin/link",
            "../../home←bin/link", "a←bin/b\nb←bin/a", "../lib←bin/link\n../lib←bin/link")) {
            reject(zip("SYMLINKS.txt" to links))
        }
        reject(zip("bin/link/file" to "data", "SYMLINKS.txt" to "../lib←bin/link"))
        reject(zip("bin/link" to "data", "SYMLINKS.txt" to "../lib←bin/link"))
        reject(zip("SYMLINKS.txt" to "/system/bin←alias\nalias/../../escape←indirect"))
    }

    @Test fun limitsCountAndActualDecompressedBytesIncludingDiscardedEntries() {
        reject(zip("large" to "x".repeat(100), "SYMLINKS.txt" to ""), RuntimeArchive.Limits(maxFileBytes = 50))
        reject(zip("a" to "1234", "b" to "1234", "SYMLINKS.txt" to ""), RuntimeArchive.Limits(maxTotalBytes = 7))
        reject(zip("a" to "", "b" to "", "SYMLINKS.txt" to ""), RuntimeArchive.Limits(maxEntries = 2))
    }

    @Test fun validRelativeAbsoluteAndSystemLinksAreValidatedBeforeCreation() {
        val root = Files.createTempDirectory("archive").toFile()
        try {
            val links = RuntimeArchive.extract(ByteArrayInputStream(zip("bin/bash" to "binary", "lib/" to "",
                "SYMLINKS.txt" to "bash←./bin/sh\n${Termux.PREFIX}/bin/sh←bin/absolute\n/system/bin/linker64←bin/linker")), root.resolve("staging"))
            assertEquals(3, links.size)
            assertEquals("binary", root.resolve("staging/bin/bash").readText())
            assertFalse(Files.isSymbolicLink(root.resolve("staging/bin/sh").toPath()))
        } finally { root.deleteRecursively() }
    }
}
