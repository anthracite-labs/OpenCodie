package com.termux

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression tests for the second-stage bootstrap timeout.
 *
 * All subprocess behavior is faked: nothing here touches the live Termux
 * installation. The clock is real monotonic time ([System.nanoTime]) with
 * short bounds so CI stays fast; the production clock is
 * [AndroidMonotonicClock].
 *
 * RED status: these tests do not compile against the pre-fix
 * RuntimeInstaller, which has no injectable launcher/clock and blocks
 * indefinitely on a hung child. They are NOT RUN on-device (no JVM); run
 * `./gradlew :app:testDebugUnitTest` in CI.
 */
class SecondStageRunnerTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private class TestClock : MonotonicClock {
        override fun nowMs(): Long = System.nanoTime() / 1_000_000L
    }

    private class FakeProcess(
        private val chunks: List<ByteArray> = emptyList(),
        private val delayBetweenChunksMs: Long = 0,
        private val repeatLast: Boolean = false,
        private val exitCode: Int = 0,
        /** Delay after output before reporting exit; Long.MAX_VALUE = never exits (hang). */
        private val exitDelayMs: Long = 0,
        private val closePipeOnExit: Boolean = true,
        private val ignoreDestroy: Boolean = false,
        private val failClose: Boolean = false,
    ) : ControlledProcess {
        private val pipeIn = PipedInputStream(65536)
        private val pipeOut = PipedOutputStream(pipeIn)
        private val exited = CountDownLatch(1)
        private val destroyed = java.util.concurrent.atomic.AtomicBoolean(false)
        val destroyCalls = AtomicInteger(0)
        val destroyForciblyCalls = AtomicInteger(0)
        var closeCalls = 0
            private set

        init {
            val t = Thread {
                try {
                    if (repeatLast && chunks.isNotEmpty()) {
                        val last = chunks.last()
                        while (!destroyed.get()) {
                            try {
                                pipeOut.write(last)
                            } catch (_: Exception) {
                                break
                            }
                        }
                    } else {
                        for (c in chunks) {
                            pipeOut.write(c)
                            if (delayBetweenChunksMs > 0) Thread.sleep(delayBetweenChunksMs)
                        }
                    }
                    if (exitDelayMs < Long.MAX_VALUE / 2) {
                        if (exitDelayMs > 0) Thread.sleep(exitDelayMs)
                        if (closePipeOnExit) runCatching { pipeOut.close() }
                        exited.countDown()
                    }
                } catch (_: Exception) {
                    // Torn down by destroy/close during the test.
                }
            }
            t.isDaemon = true
            t.start()
        }

        override val isAlive: Boolean get() = exited.count > 0

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            require(timeout > 0) { "runner must only use strictly positive waits (got $timeout)" }
            return exited.await(timeout, unit)
        }

        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException("process has not exited")
            return exitCode
        }

        override fun destroy() {
            destroyCalls.incrementAndGet()
            destroyed.set(true)
            if (!ignoreDestroy) {
                exited.countDown()
                runCatching { pipeOut.close() }
            }
        }

        override fun destroyForcibly() {
            destroyForciblyCalls.incrementAndGet()
            destroyed.set(true)
            exited.countDown()
            runCatching { pipeOut.close() }
        }

        override val output: InputStream get() = pipeIn

        override fun closeStreams() {
            closeCalls++
            if (failClose) throw IOException("injected close failure")
            runCatching { pipeIn.close() }
            runCatching { pipeOut.close() }
        }
    }

    private class FakeLauncher(val process: FakeProcess) : ProcessLauncher {
        var lastCommand: List<String>? = null
            private set
        var lastWorkdir: File? = null
            private set
        var lastEnv: Map<String, String>? = null
            private set
        var starts = 0
            private set

        override fun start(command: List<String>, workdir: File, env: Map<String, String>): ControlledProcess {
            starts++
            lastCommand = command
            lastWorkdir = workdir
            lastEnv = env
            return process
        }
    }

    private fun runner(
        process: FakeProcess,
        timeoutMs: Long = 5_000L,
    ): Pair<SecondStageRunner, FakeLauncher> {
        val launcher = FakeLauncher(process)
        return SecondStageRunner(launcher, TestClock(), timeoutMs) to launcher
    }

    private fun drainThreadsAlive(): Int =
        Thread.getAllStackTraces().keys.count { it.name == "second-stage-drain" && it.isAlive }

    @Test
    fun normalCompletion_returnsSuccessAndLogsOutput() {
        val (r, launcher) = runner(FakeProcess(chunks = listOf("Setting up libc\nDone\n".toByteArray())))
        val logged = mutableListOf<String>()
        val result = r.run(listOf("bash", "second-stage.sh"), tmp.root, mapOf("PREFIX" to "/x"), { logged.add(it) })
        assertEquals(SecondStageResult.Success, result)
        assertEquals(1, launcher.starts)
        assertEquals(listOf("bash", "second-stage.sh"), launcher.lastCommand)
        assertTrue("expected output to reach log, got: $logged", logged.any { it.contains("Setting up libc") })
    }

    @Test
    fun nonzeroExit_reportsCodeAndSanitizedTail() {
        val ansi = "\u001B[31mred text\u001B[0m failed\n".toByteArray()
        val (r, _) = runner(FakeProcess(chunks = listOf(ansi), exitCode = 3))
        val result = r.run(listOf("bash", "s.sh"), tmp.root, emptyMap()) {}
        assertTrue("expected Failed, got $result", result is SecondStageResult.Failed)
        result as SecondStageResult.Failed
        assertEquals(3, result.exitCode)
        assertTrue("tail should keep text, got: ${result.tail}", result.tail.contains("red text"))
        assertFalse("tail must not contain raw ESC, got: ${result.tail}", result.tail.contains('\u001B'))
    }

    @Test(timeout = 20_000L)
    fun silentHang_timesOutAndTerminates() {
        val proc = FakeProcess(exitDelayMs = Long.MAX_VALUE)
        val (r, _) = runner(proc, timeoutMs = 300L)
        val result = r.run(listOf("bash", "s.sh"), tmp.root, emptyMap()) {}
        assertTrue("expected TimedOut, got $result", result is SecondStageResult.TimedOut)
        result as SecondStageResult.TimedOut
        assertTrue("child must be terminated", result.terminated)
        assertTrue("destroy must have been attempted", proc.destroyCalls.get() >= 1)
        assertFalse("process must not be alive", proc.isAlive)
    }

    @Test(timeout = 20_000L)
    fun continuousOutputHang_timesOutWithBoundedTail() {
        val proc = FakeProcess(
            chunks = listOf("log line ...\n".toByteArray()),
            repeatLast = true,
            exitDelayMs = Long.MAX_VALUE,
        )
        val (r, _) = runner(proc, timeoutMs = 400L)
        val result = r.run(listOf("bash", "s.sh"), tmp.root, emptyMap()) {}
        assertTrue("expected TimedOut, got $result", result is SecondStageResult.TimedOut)
        result as SecondStageResult.TimedOut
        assertTrue("terminated", result.terminated)
        assertTrue("tail should hold streamed output", result.tail.isNotEmpty())
        assertTrue("tail must stay bounded, was ${result.tail.length}", result.tail.length <= 8_000)
    }

    @Test(timeout = 30_000L)
    fun extremelyLongOutputWithoutNewline_successStaysBounded() {
        val filler = ByteArray(300_000) { 'x'.code.toByte() }
        val end = "END-MARKER-0123456789".toByteArray()
        val proc = FakeProcess(chunks = listOf(filler + end), exitCode = 0)
        val (r, _) = runner(proc)
        val logged = mutableListOf<String>()
        val result = r.run(listOf("bash", "s.sh"), tmp.root, emptyMap(), { logged.add(it) })
        assertEquals(SecondStageResult.Success, result)
        // Each drained chunk is logged as a capped preview, so status logging
        // stays proportional to chunk count, never to line length.
        assertTrue("logged previews must stay bounded, was ${logged.sumOf { it.length }}",
            logged.sumOf { it.length } <= 40 * (512 + 32))
    }

    @Test(timeout = 30_000L)
    fun extremelyLongOutputWithoutNewline_failedTailKeepsEnd() {
        val filler = ByteArray(300_000) { 'x'.code.toByte() }
        val end = "END-MARKER-0123456789".toByteArray()
        val proc = FakeProcess(chunks = listOf(filler + end), exitCode = 1)
        val (r, _) = runner(proc)
        val result = r.run(listOf("bash", "s.sh"), tmp.root, emptyMap()) {}
        assertTrue("expected Failed, got $result", result is SecondStageResult.Failed)
        result as SecondStageResult.Failed
        assertTrue("tail must stay bounded, was ${result.tail.length}", result.tail.length <= 8_000)
        assertTrue("tail must keep the END of the stream", result.tail.contains("END-MARKER-0123456789"))
    }

    @Test(timeout = 20_000L)
    fun timeoutDuringActiveOutput_reportsPartialTail() {
        val proc = FakeProcess(
            chunks = listOf("early-chunk-A\n".toByteArray(), "early-chunk-B\n".toByteArray()),
            delayBetweenChunksMs = 50L,
            exitDelayMs = Long.MAX_VALUE,
        )
        val (r, _) = runner(proc, timeoutMs = 350L)
        val result = r.run(listOf("bash", "s.sh"), tmp.root, emptyMap()) {}
        assertTrue("expected TimedOut, got $result", result is SecondStageResult.TimedOut)
        result as SecondStageResult.TimedOut
        assertTrue("partial output must be present, got: ${result.tail}", result.tail.contains("early-chunk-A"))
    }

    @Test(timeout = 30_000L)
    fun interruption_returnsInterruptedAndRestoresFlag() {
        val proc = FakeProcess(exitDelayMs = Long.MAX_VALUE)
        val (r, _) = runner(proc, timeoutMs = 60_000L)
        var workerSawInterruptAfterRun = false
        var outcome: SecondStageResult? = null
        val worker = Thread {
            outcome = r.run(listOf("bash", "s.sh"), tmp.root, emptyMap()) {}
            workerSawInterruptAfterRun = Thread.currentThread().isInterrupted
        }
        worker.start()
        Thread.sleep(300L)
        worker.interrupt()
        worker.join(15_000L)
        assertFalse("worker must finish after interrupt", worker.isAlive)
        assertTrue("expected Interrupted, got $outcome", outcome is SecondStageResult.Interrupted)
        assertTrue("interrupt flag must be restored after cleanup", workerSawInterruptAfterRun)
        assertTrue("child must be destroyed", proc.destroyCalls.get() >= 1)
    }

    @Test(timeout = 20_000L)
    fun childIgnoringDestroy_escalatesToForcibleAndReports() {
        val proc = FakeProcess(exitDelayMs = Long.MAX_VALUE, ignoreDestroy = true)
        val (r, _) = runner(proc, timeoutMs = 300L)
        val result = r.run(listOf("bash", "s.sh"), tmp.root, emptyMap()) {}
        assertTrue("expected TimedOut, got $result", result is SecondStageResult.TimedOut)
        result as SecondStageResult.TimedOut
        assertTrue("graceful destroy attempted first", proc.destroyCalls.get() >= 1)
        assertEquals("forcible fallback must fire exactly once", 1, proc.destroyForciblyCalls.get())
        assertTrue("forcible kill must terminate", result.terminated)
    }

    @Test(timeout = 20_000L)
    fun streamsClosedAndReaderThreadSettles() {
        val proc = FakeProcess(chunks = listOf("hello\n".toByteArray()))
        val (r, _) = runner(proc)
        val before = drainThreadsAlive()
        val result = r.run(listOf("bash", "s.sh"), tmp.root, emptyMap()) {}
        assertEquals(SecondStageResult.Success, result)
        assertEquals("closeStreams must be called exactly once", 1, proc.closeCalls)
        // The runner joins the drain thread with a positive bound before returning.
        assertEquals("no drain threads may leak, before=$before", before, drainThreadsAlive())
    }

    @Test(timeout = 20_000L)
    fun cleanupFailure_doesNotMaskOutcome() {
        val proc = FakeProcess(chunks = listOf("ok\n".toByteArray()), failClose = true)
        val (r, _) = runner(proc)
        val result = r.run(listOf("bash", "s.sh"), tmp.root, emptyMap()) {}
        // closeStreams threw; the outcome must still be delivered, never an escape.
        assertEquals(SecondStageResult.Success, result)
        assertEquals(1, proc.closeCalls)
    }

    @Test(timeout = 20_000L)
    fun exitNearTimeout_countsAsSuccess() {
        val proc = FakeProcess(chunks = listOf("late\n".toByteArray()), exitDelayMs = 1_000L)
        val (r, _) = runner(proc, timeoutMs = 3_000L)
        val result = r.run(listOf("bash", "s.sh"), tmp.root, emptyMap()) {}
        assertEquals(SecondStageResult.Success, result)
    }

    @Test(timeout = 20_000L)
    fun noUnintendedModificationOfInstallationMarkers() {
        val ready = File(tmp.root, ".opencodie-ready").apply { writeText("v1\n") }
        val installing = File(tmp.root, ".opencodie-installing").apply { writeText("v1\n") }
        val user = File(tmp.root, "user-data.txt").apply { writeText("keep me\n") }
        val (r, _) = runner(FakeProcess(chunks = listOf("ok\n".toByteArray())))
        val result = r.run(listOf("bash", "s.sh"), tmp.root, emptyMap()) {}
        assertEquals(SecondStageResult.Success, result)
        assertEquals("v1\n", ready.readText())
        assertEquals("v1\n", installing.readText())
        assertEquals("keep me\n", user.readText())
    }
}
