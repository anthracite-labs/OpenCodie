package com.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunTest {
    private fun state(
        ready: Boolean = true,
        storage: Boolean = true,
        projects: List<String> = listOf("workspace"),
        seen: String? = null,
    ) = FirstRunState(ready, storage, projects, seen, "1.0")

    @Test
    fun runtimeFirstUntilReady() {
        assertEquals(FirstRunStep.RUNTIME, FirstRun.nextStep(state(ready = false)))
    }

    @Test
    fun storageSkippableButOrderedSecond() {
        assertEquals(FirstRunStep.STORAGE, FirstRun.nextStep(state(storage = false)))
    }

    @Test
    fun emptyProjectsAsksForFirstProject() {
        assertEquals(FirstRunStep.PROJECT, FirstRun.nextStep(state(projects = emptyList())))
    }

    @Test
    fun completeStateIsDone() {
        assertEquals(FirstRunStep.DONE, FirstRun.nextStep(state()))
    }

    @Test
    fun shownOncePerVersionAfterAutoPrompt() {
        assertFalse(FirstRun.shouldShow(runtimeReady = false, storagePromptedBefore = true, seenVersion = null, currentVersion = "1.0"))
        assertFalse(FirstRun.shouldShow(runtimeReady = true, storagePromptedBefore = false, seenVersion = null, currentVersion = "1.0"))
        assertFalse(FirstRun.shouldShow(runtimeReady = true, storagePromptedBefore = true, seenVersion = "1.0", currentVersion = "1.0"))
        assertTrue(FirstRun.shouldShow(runtimeReady = true, storagePromptedBefore = true, seenVersion = null, currentVersion = "1.0"))
    }

    @Test
    fun stepsDescribeSkippedStorage() {
        val lines = FirstRun.stepLines(state(storage = false, projects = emptyList()))
        assertTrue(lines[1].contains("skipped for now"))
        assertTrue(lines[2].contains("~/projects"))
    }
}
