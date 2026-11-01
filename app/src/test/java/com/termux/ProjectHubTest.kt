package com.termux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectHubTest {
    @Test
    fun validNamesAccepted() {
        assertTrue(ProjectHub.isValidProjectName("workspace"))
        assertTrue(ProjectHub.isValidProjectName("my-app_2"))
    }

    @Test
    fun traversalAndBlanksRejected() {
        assertFalse(ProjectHub.isValidProjectName("../evil"))
        assertFalse(ProjectHub.isValidProjectName("a/b"))
        assertFalse(ProjectHub.isValidProjectName(""))
        assertFalse(ProjectHub.isValidProjectName(".hidden"))
        assertFalse(ProjectHub.isValidProjectName("has space"))
    }

    @Test
    fun badOverrideFallsBackToDefault() {
        val home = File("/data/data/com.termux/files/home")
        assertEquals(
            File(home, "projects/workspace"),
            ProjectHub.workspaceDir(home, "../evil"),
        )
        assertEquals(
            File(home, "projects/myapp"),
            ProjectHub.workspaceDir(home, "myapp"),
        )
    }

    @Test
    fun branchParsesFirstLine() {
        assertEquals("main", ProjectHub.parseBranch("main\n"))
        assertNull(ProjectHub.parseBranch("  \n"))
    }

    @Test
    fun currentWorkspaceSortsFirst() {
        assertEquals(
            listOf("b", "a", "c"),
            ProjectHub.sortForDisplay(listOf("a", "b", "c"), "b"),
        )
    }
}
