package com.termux

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class DownloadTest {
    @Test fun pickerResponsesKeepTheirOwnUrlAndCookieWhenCompletedOutOfOrder() {
        val pending = PendingDownloads()
        val a = pending.add("${LocalOrigin.URL}/a", "session=a")
        val b = pending.add("${LocalOrigin.URL}/b", "session=b")
        assertEquals("${LocalOrigin.URL}/a", pending.take(a)!!.url)
        assertEquals("session=b", pending.take(b)!!.cookie)
        assertNull(pending.take(a))
    }

    @Test fun realHttpTransfersPreserveBytesAllowLocalRedirectsAndRejectOffOrigin() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 4096), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val bytes = byteArrayOf(0, 1, -1, 13, 10)
        val external = AtomicBoolean()
        val other = HttpServer.create(InetSocketAddress("127.0.0.1", 4097), 0)
        other.createContext("/") { external.set(true); it.sendResponseHeaders(200, -1); it.close() }
        server.createContext("/bytes") { request ->
            assertEquals("session=test", request.requestHeaders.getFirst("Cookie"))
            request.sendResponseHeaders(200, bytes.size.toLong()); request.responseBody.use { it.write(bytes) }
        }
        server.createContext("/local") { it.responseHeaders.set("Location", "/bytes"); it.sendResponseHeaders(302, -1); it.close() }
        server.createContext("/external") { it.responseHeaders.set("Location", "http://127.0.0.1:4097/secret"); it.sendResponseHeaders(302, -1); it.close() }
        server.createContext("/error") { it.sendResponseHeaders(500, -1); it.close() }
        server.start(); other.start()
        try {
            val output = ByteArrayOutputStream()
            DownloadTransfer().copy(DownloadRequest("${LocalOrigin.URL}/local", "session=test"), { output })
            assertArrayEquals(bytes, output.toByteArray())
            for (path in listOf("/external", "/error")) {
                val opened = AtomicBoolean()
                try {
                    DownloadTransfer().copy(DownloadRequest(LocalOrigin.URL + path, "session=test"), { opened.set(true); ByteArrayOutputStream() })
                    fail("Unsafe response accepted")
                } catch (_: IOException) { }
                assertFalse("Destination must stay untouched until response is accepted", opened.get())
            }
            assertFalse("Cookie-bearing request reached another origin", external.get())
        } finally { server.stop(0); other.stop(0); executor.shutdownNow() }
    }

    @Test fun stalledAndCancelledTransfersExitWithinReadTimeout() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 4096), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.createContext("/stall") {
            it.sendResponseHeaders(200, 100)
            started.countDown()
            try { release.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            it.close()
        }
        server.start()
        try {
            val transfer = DownloadTransfer(readTimeoutMillis = 100)
            val exited = CountDownLatch(1)
            val thread = Thread {
                try { transfer.copy(DownloadRequest("${LocalOrigin.URL}/stall", null), { ByteArrayOutputStream() }); fail("Stall accepted") }
                catch (_: IOException) { }
                finally { exited.countDown() }
            }
            thread.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            transfer.cancel()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            assertTrue(transfer.cancelled)
        } finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }
}
