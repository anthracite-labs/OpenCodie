package com.termux

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class HostSupervisorTest {
    @Test fun unacknowledgedChildShutdownBlocksFurtherStarts() {
        val done = CountDownLatch(1)
        val runs = AtomicInteger()
        val host = HostSupervisor { token ->
            runs.incrementAndGet()
            token.restartAllowed = false
            done.countDown()
        }
        host.start()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)
        repeat(10) { host.start(); Thread.sleep(5) }
        assertEquals(1, runs.get())
    }

    @Test fun stopStartQueuesRestartUntilTheOldOwnerAcknowledgesShutdown() {
        val entered = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val restarted = CountDownLatch(1)
        val runs = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val host = HostSupervisor { token ->
            maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                if (runs.incrementAndGet() == 1) {
                    entered.countDown()
                    while (retired.count > 0) try { retired.await() } catch (_: InterruptedException) { }
                } else {
                    restarted.countDown()
                    while (!token.cancelled) try { Thread.sleep(5) } catch (_: InterruptedException) { }
                }
            } finally { active.decrementAndGet() }
        }
        try {
            host.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            host.stop()
            host.start()
            assertEquals(1, runs.get())
            retired.countDown()
            assertTrue("A requested restart must survive a live old supervisor", restarted.await(2, TimeUnit.SECONDS))
            assertEquals(1, maximum.get())
        } finally { retired.countDown(); host.stop() }
    }

    @Test fun repeatedStartsAreIdempotentAndIntentionalStopDoesNotRestart() {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val runs = AtomicInteger()
        val host = HostSupervisor { token ->
            runs.incrementAndGet()
            entered.countDown()
            try {
                while (!token.cancelled) try { Thread.sleep(5) } catch (_: InterruptedException) { }
            } finally { exited.countDown() }
        }
        repeat(100) { host.start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        host.stop()
        assertTrue(exited.await(2, TimeUnit.SECONDS))
        assertEquals(1, runs.get())
    }

    @Test fun unexpectedWorkerExceptionReportsFailureWithoutStartingAnotherOwner() {
        val observed = CountDownLatch(1)
        val runs = AtomicInteger()
        val host = HostSupervisor(onCrash = { observed.countDown() }) {
            runs.incrementAndGet()
            throw IllegalStateException("Unexpected host failure")
        }
        host.start()
        assertTrue("Worker failure must be handled", observed.await(2, TimeUnit.SECONDS))
        repeat(5) { host.start() }
        assertEquals("Do not start a second owner after an unverified crash", 1, runs.get())
    }

}
