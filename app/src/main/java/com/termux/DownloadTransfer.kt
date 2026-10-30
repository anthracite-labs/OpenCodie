package com.termux

import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

internal data class DownloadRequest(val url: String, val cookie: String?)

internal class PendingDownloads {
    private val requests = mutableMapOf<Int, DownloadRequest>()
    private var next = 1000

    @Synchronized fun add(url: String, cookie: String?): Int {
        check(LocalOrigin.allows(url)) { "Download origin refused" }
        check(requests.size < 16) { "Too many pending downloads" }
        while (requests.containsKey(next)) next = if (next == 65534) 1000 else next + 1
        val id = next
        next = if (next == 65534) 1000 else next + 1
        requests[id] = DownloadRequest(url, cookie)
        return id
    }

    @Synchronized fun take(id: Int): DownloadRequest? = requests.remove(id)
    @Synchronized fun clear() = requests.clear()
}

internal class DownloadTransfer(private val readTimeoutMillis: Int = 15_000) {
    @Volatile var cancelled = false
        private set
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile var bytesCopied = 0L
        private set

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    fun copy(request: DownloadRequest, openOutput: () -> OutputStream, progress: (Long) -> Unit = {}) {
        var url = request.url
        val deadline = System.nanoTime() + 300_000_000_000L
        for (redirect in 0..5) {
            checkActive(deadline)
            if (!LocalOrigin.allows(url)) throw IOException("Download origin refused")
            val current = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 10_000
                readTimeout = readTimeoutMillis
                request.cookie?.let { setRequestProperty("Cookie", it) }
            }
            connection = current
            try {
                checkActive(deadline)
                val status = current.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = current.getHeaderField("Location") ?: throw IOException("Redirect has no location")
                    url = URL(URL(url), location).toExternalForm()
                    if (!LocalOrigin.allows(url)) throw IOException("Download redirect refused")
                    continue
                }
                if (status !in 200..299) throw IOException("Download returned HTTP $status")
                var bytes = 0L
                current.inputStream.use { input ->
                    checkActive(deadline)
                    openOutput().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            checkActive(deadline)
                            val size = input.read(buffer)
                            if (size < 0) break
                            checkActive(deadline)
                            bytes += size
                            if (bytes > 2L * 1024 * 1024 * 1024) throw IOException("Download size limit exceeded")
                            output.write(buffer, 0, size)
                            bytesCopied = bytes
                            if (bytes % (1024 * 1024) < size) progress(bytes)
                        }
                    }
                }
                checkActive(deadline)
                if (current.contentLengthLong >= 0 && bytes != current.contentLengthLong) throw IOException("Download was incomplete")
                return
            } finally {
                current.disconnect()
                connection = null
            }
        }
        throw IOException("Too many download redirects")
    }

    private fun checkActive(deadline: Long) {
        if (cancelled || Thread.currentThread().isInterrupted) throw IOException("Download canceled")
        if (System.nanoTime() >= deadline) throw IOException("Download deadline exceeded")
    }
}
