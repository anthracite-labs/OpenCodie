package com.termux

import android.content.Context
import android.os.Process as AndroidProcess
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Supervises the single local OpenCode server and hands the WebView a pairing
 * link. The server is OpenCode's own `serve` command bound to loopback; the
 * WebView authenticates with the session cookie it receives by redeeming the
 * pairing code, so no authentication of our own is added.
 */
object OpenCodeHost {
    const val HOST = "127.0.0.1"
    const val PORT = 4096

    private const val TAG = "OpenCodeHost"
    private const val PASSWORD_KEY = "opencode_password"
    private const val READY_TIMEOUT_MS = 180_000L
    private const val LOG_LIMIT = 8_000

    @Volatile var status = "Starting"
    @Volatile var pairingUrl: String? = null
    @Volatile var error: String? = null

    private var process: Process? = null
    private var password: String = ""
    private val output = StringBuilder()

    /** Installs the runtime if needed, starts the server and waits until it is usable. */
    fun prepare(context: Context) {
        if (process?.isAlive == true) return
        error = null
        try {
            status = "Preparing runtime"
            RuntimeInstaller.installIfNeeded(context) { status = it }
            status = "Starting OpenCode"
            start(context)
            status = "Waiting for OpenCode"
            pairingUrl = awaitPairing()
            status = "Ready"
        } catch (e: Exception) {
            Log.e(TAG, "OpenCode startup failed", e)
            error = "${e.message ?: e.toString()}\n\n${log()}"
        }
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    private fun start(context: Context) {
        password = storedPassword(context)
        File(Termux.HOME).mkdirs()
        val command = "exec ${Termux.PREFIX}/bin/opencode serve --hostname $HOST --port $PORT"
        val builder = ProcessBuilder("${Termux.PREFIX}/bin/bash", "-lc", command)
            .directory(File(Termux.HOME))
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            putAll(Termux.environment(AndroidProcess.myUid()))
            put("OPENCODE_PASSWORD", password)
        }
        val started = builder.start()
        process = started
        Thread {
            started.inputStream.bufferedReader().forEachLine { line ->
                Log.i(TAG, line)
                synchronized(output) {
                    output.append(line).append('\n')
                    if (output.length > LOG_LIMIT) output.delete(0, output.length - LOG_LIMIT)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Polls /api/info until the server answers, then redeems a pairing code. */
    private fun awaitPairing(): String {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) throw IllegalStateException("OpenCode exited during startup")
            if (statusCode("GET", "/api/info") == 200) return pairingURL()
            Thread.sleep(500)
        }
        throw IllegalStateException("OpenCode did not become ready")
    }

    private fun pairingURL(): String {
        val body = connection("POST", "/api/pair").let { request ->
            try {
                check(request.responseCode == 200) { "pairing failed: HTTP ${request.responseCode}" }
                request.inputStream.bufferedReader().use { it.readText() }
            } finally {
                request.disconnect()
            }
        }
        val code = JSONObject(body).getString("code")
        return "http://$HOST:$PORT/auth/connect/$code"
    }

    private fun statusCode(method: String, path: String): Int {
        val request = connection(method, path)
        return try {
            request.responseCode
        } catch (e: Exception) {
            0
        } finally {
            request.disconnect()
        }
    }

    private fun connection(method: String, path: String): HttpURLConnection =
        (URL("http://$HOST:$PORT$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3_000
            readTimeout = 5_000
            setRequestProperty("Authorization", authorization())
        }

    /** OpenCode accepts HTTP Basic credentials with the fixed username "opencode". */
    private fun authorization(): String {
        val credentials = "opencode:$password".toByteArray()
        return "Basic " + Base64.encodeToString(credentials, Base64.NO_WRAP)
    }

    /** One locally generated password per install; rotating it signs sessions out. */
    private fun storedPassword(context: Context): String {
        val preferences = context.getSharedPreferences("opencode", Context.MODE_PRIVATE)
        preferences.getString(PASSWORD_KEY, null)?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val generated = bytes.joinToString("") { "%02x".format(it) }
        preferences.edit().putString(PASSWORD_KEY, generated).apply()
        return generated
    }

    private fun log(): String = synchronized(output) { output.toString() }
}
