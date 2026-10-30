package com.termux

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class ChildProcessTest {
    private class SlowShutdown : Process() {
        val waitStarted = CountDownLatch(1)
        val forciblyStopped = AtomicBoolean(false)
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else 0
        override fun destroy() { /* A process may ignore SIGTERM. */ }
        override fun isAlive(): Boolean = !forciblyStopped.get()
        override fun destroyForcibly(): Process { forciblyStopped.set(true); return this }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitStarted.countDown()
            Thread.sleep(unit.toMillis(timeout).coerceAtMost(500))
            return !isAlive
        }
    }

    @Test fun interruptionDuringShutdownStillKillsChildAndRestoresInterrupt() {
        val process = SlowShutdown()
        val terminated = AtomicBoolean(false)
        val restored = AtomicBoolean(false)
        val worker = Thread {
            try { ChildProcess.terminate(process); terminated.set(true) }
            finally { restored.set(Thread.currentThread().isInterrupted) }
        }
        worker.start()
        assertTrue(process.waitStarted.await(2, TimeUnit.SECONDS))
        worker.interrupt()
        worker.join(8_000)
        try {
            assertFalse("Termination must return", worker.isAlive)
            assertTrue("Interrupt must not abandon child cleanup", terminated.get())
            assertTrue(process.forciblyStopped.get())
            assertTrue("Interrupt status must be restored", restored.get())
        } finally {
            if (worker.isAlive) worker.interrupt()
        }
    }
}
