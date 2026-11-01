package com.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferTest {
    @Test
    fun percentWhenTotalKnown() {
        assertEquals("Saving… 50%", FileTransfer.progressText(512, 1024))
        assertEquals("Saving… 100%", FileTransfer.progressText(2048, 1024))
    }

    @Test
    fun kilobytesWhenTotalUnknown() {
        assertEquals("Saving… 4 KB", FileTransfer.progressText(4096, -1))
    }

    @Test
    fun shortNamesUntouched() {
        assertEquals("notes.md", FileTransfer.fileLabel("notes.md"))
    }

    @Test
    fun longNamesKeepExtension() {
        val label = FileTransfer.fileLabel("averylongfilenameforreal relaysession.md")
        assertTrue(label.endsWith(".md"))
        assertTrue(label.contains("…"))
        assertTrue(label.length <= 36)
    }

    @Test
    fun giantExtensionDegradesGracefully() {
        val label = FileTransfer.fileLabel("x." + "y".repeat(40))
        assertEquals(32, label.length)
    }
}
