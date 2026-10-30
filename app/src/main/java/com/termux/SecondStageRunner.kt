package com.termux

import android.os.SystemClock
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Timeout-correct execution of the Termux second-stage bootstrap script.
 *
 * Design notes (all load-bearing):
 *
 * - The timeout is enforced by [ControlledProcess.waitFor] on the calling
 *   thread in bounded slices. A blocked output reader can never suppress it,
 *   because the deadline does not depend on EOF or any pipe state.
 * - Output is drained on a dedicated daemon thread in fixed-size character
 *   chunks. Individual lines are never assumed to be small: arbitrarily long
 *   output without newlines stays bounded via [TAIL_MAX_CHARS].
 * - The clock is injected ([MonotonicClock]). Production uses
 *   [SystemClock.elapsedRealtime], which is monotonic; wall-clock time is
 *   never used for the deadline.
 * - On timeout the child is asked to terminate ([ControlledProcess.destroy]),
 *   then forcibly ([ControlledProcess.destroyForcibly]) after [KILL_GRACE_MS].
 *   Our read end of the pipe is closed so the drain thread unblocks even if
 *   subprocess descendants inherited the write end. If the drain thread still
 *   has not settled after [READER_JOIN_MS] it is abandoned (it is a daemon)
 *   and reported via [SecondStageResult.TimedOut.readerSettled].
 * - Failure to terminate the child is a separate reportable condition
 *   ([SecondStageResult.TimedOut.terminated]), not conflated with the timeout.
 * - Interruption clears the thread flag while bounded cleanup runs, then
 *   restores it afterwards. Cleanup waits are therefore never skipped and
 *   never block indefinitely. All waits/joins use strictly positive bounds.
 * - This runner touches no installation markers and performs no retries. It
 *   only launches one command, waits, terminates on timeout, and reports.
 */
const val SECOND_STAGE_TIMEOUT_MS = 600_000L

private const val WAIT_SLICE_MS = 5_000L
private const val KILL_GRACE_MS = 3_000L
private const val READER_JOIN_MS = 3_000L
private const val STREAM_CLOSE_JOIN_MS = 500L
private const val DRAIN_CHUNK_CHARS = 8_192
private const val TAIL_MAX_CHARS = 8_000
private const val LOG_CHUNK_CHARS = 512

/** Monotonic clock for deadlines. Never use wall-clock time here. */
interface MonotonicClock {
    fun nowMs(): Long
}

/** Production clock backed by [SystemClock.elapsedRealtime]. */
class AndroidMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/** Minimal injectable handle over a launched subprocess. */
interface ControlledProcess {
    val isAlive: Boolean

    /** Returns true when the process exited within the bound. Timeout must be strictly positive. */
    fun waitFor(timeout: Long, unit: TimeUnit): Boolean
    fun exitValue(): Int
    fun destroy()
    fun destroyForcibly()

    /** Merged stdout+stderr of the child. */
    val output: InputStream
    fun closeStreams()
}

/** Launches subprocesses. Production uses [ProcessBuilder]; tests use fakes. */
interface ProcessLauncher {
    fun start(command: List<String>, workdir: File, env: Map<String, String>): ControlledProcess
}

class RealProcessLauncher : ProcessLauncher {
    override fun start(command: List<String>, workdir: File, env: Map<String, String>): ControlledProcess {
        val process = ProcessBuilder(command)
            .directory(workdir)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(env)
            }
            .start()
        return RealControlledProcess(process)
    }
}

private class RealControlledProcess(private val delegate: Process) : ControlledProcess {
    override val isAlive: Boolean get() = delegate.isAlive
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = delegate.waitFor(timeout, unit)
    override fun exitValue(): Int = delegate.exitValue()
    override fun destroy() = delegate.destroy()
    override fun destroyForcibly() {
        delegate.destroyForcibly()
    }
    override val output: InputStream get() = delegate.inputStream
    override fun closeStreams() {
        runCatching { delegate.inputStream.close() }
        runCatching { delegate.outputStream.close() }
        runCatching { delegate.errorStream.close() }
    }
}

/** Outcome of one second-stage execution. */
sealed interface SecondStageResult {
    /** Plain object (not `data object`) for maximum toolchain compatibility. */
    object Success : SecondStageResult
    data class Failed(val exitCode: Int, val tail: String) : SecondStageResult
    data class TimedOut(
        val tail: String,
        /** False when the child survived destroy + destroyForcibly: report separately. */
        val terminated: Boolean,
        /** False when the daemon drain thread was abandoned (descendant-held pipe). */
        val readerSettled: Boolean,
    ) : SecondStageResult
    data class Interrupted(val tail: String) : SecondStageResult
}

/**
 * Best-effort cleanup of control characters for the diagnostic tail.
 * Strips ANSI escape sequences and replaces other C0 controls (except
 * newline/tab) so one hostile/bootstrap-corrupt line cannot corrupt logs.
 * This is control-character hygiene only, not secret redaction: command
 * output is preserved verbatim otherwise.
 */
internal fun sanitizeSecondStageOutput(text: String): String {
    val noAnsi = text.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
    val out = StringBuilder(noAnsi.length)
    for (c in noAnsi) {
        if (c == '\n' || c == '\t' || c >= ' ') out.append(c) else out.append('?')
    }
    return out.toString()
}

/** Drains a stream in bounded chunks on a daemon thread; never blocks the deadline. */
private class BoundedDrain(stream: InputStream, private val log: (String) -> Unit) {
    private val input = InputStreamReader(stream, Charsets.UTF_8)
    private val guard = Any()
    private val tail = StringBuilder()
    val thread: Thread = Thread({
        val buf = CharArray(DRAIN_CHUNK_CHARS)
        try {
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                if (read == 0) continue
                onChunk(String(buf, 0, read))
            }
        } catch (_: Exception) {
            // Stream closed during timeout/interrupt teardown. Not an error by itself.
        }
    }, "second-stage-drain").apply { isDaemon = true }

    private fun onChunk(text: String) {
        val clean = sanitizeSecondStageOutput(text)
        synchronized(guard) {
            tail.append(clean)
            if (tail.length > TAIL_MAX_CHARS) tail.delete(0, tail.length - TAIL_MAX_CHARS)
        }
        val preview = if (clean.length > LOG_CHUNK_CHARS) {
            clean.substring(0, LOG_CHUNK_CHARS) + "..[chunk truncated]"
        } else {
            clean
        }
        log(preview)
    }

    fun snapshot(): String = synchronized(guard) { tail.toString() }
}

class SecondStageRunner(
    private val launcher: ProcessLauncher,
    private val clock: MonotonicClock,
    private val timeoutMs: Long = SECOND_STAGE_TIMEOUT_MS,
) {
    init {
        require(timeoutMs > 0) { "timeoutMs must be strictly positive" }
    }

    fun run(
        command: List<String>,
        workdir: File,
        env: Map<String, String>,
        log: (String) -> Unit,
    ): SecondStageResult {
        val process = launcher.start(command, workdir, env)
        val drain = BoundedDrain(process.output, log)
        drain.thread.start()
        val deadline = clock.nowMs() + timeoutMs
        var interrupted = false
        var finished = false
        while (true) {
            val remaining = deadline - clock.nowMs()
            if (remaining <= 0) break
            try {
                if (process.waitFor(minOf(remaining, WAIT_SLICE_MS), TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
            } catch (_: InterruptedException) {
                interrupted = true
                break
            }
        }
        if (interrupted) {
            // Clear so the bounded cleanup below runs its full bound, then restore.
            Thread.interrupted()
            kill(process)
            try {
                drain.thread.join(READER_JOIN_MS)
            } catch (_: InterruptedException) {
                // Second interrupt during teardown: proceed; flag restored below.
            }
            closeBounded(process)
            val tail = drain.snapshot()
            Thread.currentThread().interrupt()
            return SecondStageResult.Interrupted(tail)
        }
        if (finished) {
            // A descendant can retain the output pipe after the parent exits.
            // Wait for EOF only within the *original* monotonic deadline; a
            // successful parent exit alone must never bypass the watchdog.
            try {
                val remaining = deadline - clock.nowMs()
                if (remaining > 0) drain.thread.join(remaining)
            } catch (_: InterruptedException) {
                Thread.interrupted()
                closeBounded(process)
                Thread.currentThread().interrupt()
                return SecondStageResult.Interrupted(drain.snapshot())
            }
            val code = process.exitValue()
            // Process stream close can itself block behind a reader holding a
            // Java stream lock. Run it on a daemon with a bounded join.
            val streamsClosed = closeBounded(process)
            val tail = drain.snapshot()
            if (drain.thread.isAlive || !streamsClosed) {
                return SecondStageResult.TimedOut(
                    tail = tail,
                    terminated = !process.isAlive,
                    readerSettled = !drain.thread.isAlive,
                )
            }
            return if (code == 0) SecondStageResult.Success else SecondStageResult.Failed(code, tail)
        }
        // Timeout: the deadline passed while the child was still alive, even
        // though stdout may still be open (silent hang) or streaming
        // (continuous-output hang). Terminate, then unblock our reader by
        // closing our read end: descendants holding inherited write ends
        // cannot keep our drain thread blocked after async close.
        var killInterrupted = false
        process.destroy()
        var exited = try {
            process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            killInterrupted = true
            false
        }
        if (!exited) {
            process.destroyForcibly()
            try {
                process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                killInterrupted = true
            }
            exited = !process.isAlive
        }
        closeBounded(process)
        try {
            drain.thread.join(READER_JOIN_MS)
        } catch (_: InterruptedException) {
            killInterrupted = true
        }
        val tail = drain.snapshot()
        if (killInterrupted) Thread.currentThread().interrupt()
        return SecondStageResult.TimedOut(
            tail = tail,
            terminated = exited && !process.isAlive,
            readerSettled = !drain.thread.isAlive,
        )
    }

    private fun kill(process: ControlledProcess) {
        process.destroy()
        try {
            if (!process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            // Keep teardown bounded even when interrupted; the caller restores
            // the interrupt flag. Escalate so no cooperative child is left.
            try {
                process.destroyForcibly()
            } catch (_: Exception) {
                // Report via isAlive checks by the caller, never throw here.
            }
        }
    }

    /**
     * Never close a process pipe on the supervisor thread: InputStream.close()
     * may block behind a read owned by a descendant-held output pipe. The
     * closer is a daemon, and the caller's wait for it is strictly bounded.
     * A false result is treated as incomplete cleanup, never as Success.
     */
    private fun closeBounded(process: ControlledProcess): Boolean {
        val closer = Thread({
            try {
                process.closeStreams()
            } catch (_: Exception) {
                // A failed close must not mask the observed process result.
            }
        }, "second-stage-close").apply { isDaemon = true; start() }
        return try {
            closer.join(STREAM_CLOSE_JOIN_MS)
            !closer.isAlive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
