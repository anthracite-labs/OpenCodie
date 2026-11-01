package com.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTimelineTest {
    @Test fun runtimeEventsHaveRealStageBoundaries() {
        val timeline = StartupTimeline()
        assertEquals(StartupStep.RUNTIME, timeline.snapshot().active)
        timeline.advance(StartupEvent.UNPACKING_RUNTIME)
        assertEquals(StartupStep.RUNTIME, timeline.snapshot().active)
        timeline.advance(StartupEvent.CONFIGURING_PACKAGES)
        assertEquals(StartupStep.PACKAGES, timeline.snapshot().active)
        assertEquals(1, timeline.snapshot().completedCount)
        timeline.advance(StartupEvent.RUNTIME_READY)
        assertEquals(StartupStep.SERVER, timeline.snapshot().active)
        assertEquals(2, timeline.snapshot().completedCount)
    }

    @Test fun serverIsNotCompleteBeforeReadinessAndAuthenticatedWebView() {
        val timeline = StartupTimeline()
        timeline.advance(StartupEvent.RUNTIME_READY)
        timeline.advance(StartupEvent.STARTING_SERVER)
        assertEquals(StartupStep.SERVER, timeline.snapshot().active)
        timeline.advance(StartupEvent.WAITING_FOR_SERVER)
        assertEquals(StartupStep.SERVER, timeline.snapshot().active)
        timeline.advance(StartupEvent.SERVER_READY)
        assertEquals(StartupStep.WEBVIEW, timeline.snapshot().active)
        timeline.advance(StartupEvent.WEBVIEW_READY)
        assertEquals(StartupStep.READY, timeline.snapshot().active)
    }

    @Test fun failedStageDoesNotPretendProgressCompletes() {
        val timeline = StartupTimeline()
        timeline.advance(StartupEvent.CONFIGURING_PACKAGES)
        timeline.fail()
        assertEquals(StartupStep.PACKAGES, timeline.snapshot().active)
        assertEquals(true, timeline.snapshot().failed)
    }

    @Test fun recreationReconstructsFromSharedRuntimeSnapshot() {
        val timeline = StartupTimeline()
        timeline.advance(StartupEvent.SERVER_READY)
        val snapshot = timeline.snapshot()
        assertEquals(StartupStep.WEBVIEW, StartupTimeline.describe(snapshot.event).active)
        assertEquals(snapshot.completedCount, StartupTimeline.describe(snapshot.event).completedCount)
    }

    @Test fun verifiedRuntimeStepsRemainDoneAfterServerRestart() {
        val timeline = StartupTimeline()
        timeline.advance(StartupEvent.RUNTIME_READY)
        timeline.advance(StartupEvent.SERVER_READY)
        timeline.advance(StartupEvent.WEBVIEW_READY)
        timeline.restartServer()
        assertEquals(StartupStep.SERVER, timeline.snapshot().active)
        assertEquals(2, timeline.snapshot().completedCount)
        timeline.advance(StartupEvent.SERVER_READY)
        assertEquals(StartupStep.WEBVIEW, timeline.snapshot().active)
    }

    @Test fun actualFailureRetainsItsLastStageUntilARealRetry() {
        val timeline = StartupTimeline()
        timeline.advance(StartupEvent.CONFIGURING_PACKAGES)
        timeline.fail()
        assertEquals(true, timeline.snapshot().failed)
        assertEquals(StartupStep.PACKAGES, timeline.snapshot().active)
        timeline.advance(StartupEvent.VERIFYING_INSTALL)
        assertEquals(false, timeline.snapshot().failed)
        assertEquals(StartupStep.PACKAGES, timeline.snapshot().active)
    }

    @Test fun noPercentWithoutMeasuredWork() {
        val timeline = StartupTimeline()
        timeline.advance(StartupEvent.UNPACKING_RUNTIME)
        assertEquals(null, timeline.snapshot().percent)
        timeline.advance(StartupEvent.CONFIGURING_PACKAGES)
        assertEquals(null, timeline.snapshot().percent)
    }

    @Test fun earlierEventsCannotReverseVerifiedProgress() {
        val timeline = StartupTimeline()
        timeline.advance(StartupEvent.RUNTIME_READY)
        timeline.advance(StartupEvent.UNPACKING_RUNTIME)
        assertEquals(StartupStep.SERVER, timeline.snapshot().active)
    }
    @Test fun automaticRetryKeepsLastReasonAndDoesNotFlashFatalState() {
        val timeline = StartupTimeline()
        timeline.advance(StartupEvent.RUNTIME_READY)
        timeline.advance(StartupEvent.SERVER_READY)
        timeline.noteServerFailure("OpenCode server readiness timed out")
        assertEquals(StartupStep.SERVER, timeline.snapshot().active)
        assertEquals(false, timeline.snapshot().failed)
        assertEquals(true, timeline.snapshot().retrying)
        assertEquals("OpenCode server readiness timed out", timeline.snapshot().lastFailure)
        timeline.restartServer()
        assertEquals(true, timeline.snapshot().retrying)
        assertEquals("OpenCode server readiness timed out", timeline.snapshot().lastFailure)
        timeline.advance(StartupEvent.WAITING_FOR_SERVER)
        assertEquals(true, timeline.snapshot().retrying)
        timeline.advance(StartupEvent.SERVER_READY)
        assertEquals(false, timeline.snapshot().retrying)
        assertEquals("OpenCode server readiness timed out", timeline.snapshot().lastFailure)
    }

    @Test fun fatalInstallerFailureRemainsFatal() {
        val timeline = StartupTimeline()
        timeline.advance(StartupEvent.CONFIGURING_PACKAGES)
        timeline.fail()
        assertTrue(timeline.snapshot().failed)
        assertFalse(timeline.snapshot().retrying)
        assertEquals(StartupStep.PACKAGES, timeline.snapshot().active)
    }
}
