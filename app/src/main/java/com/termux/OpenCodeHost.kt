package com.termux

import android.content.Context
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
 *
 * One supervisor thread owns the process: it starts the server, publishes a
 * pairing link when ready, and restarts the server with backoff if it exits.
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

    private var appContext: Context? = null
    private val supervisor = HostSupervisor { token -> supervise(checkNotNull(appContext), token) }

    private var password: String = ""
    private val output = StringBuilder()

    /** Start/stop return promptly; the old thread acknowledges shutdown before replacement. */
    @Synchronized
    fun start(context: Context) {
        appContext = context.applicationContext
        supervisor.start()
    }

    fun stop() = supervisor.stop()

    private fun supervise(context: Context, token: HostSupervisor.Cancellation) {
        try {
            RuntimeInstaller.installIfNeeded(context) { status = it }
        } catch (e: Exception) {
            fail("Could not prepare the Termux runtime", e)
            return
        }

        var backoff = 2_000L
        while (!token.cancelled) {
            var started: Process? = null
            try {
                status = "Starting OpenCode"
                error = null
                pairingUrl = null
                started = launch(context)
                token.attach(started)
                if (token.cancelled) break
                status = "Waiting for OpenCode"
                pairingUrl = awaitPairing(started, token)
                status = "Ready"
                backoff = 2_000L
                val code = started.waitFor()
                if (token.cancelled) break
                Log.w(TAG, "OpenCode exited with $code")
                status = "OpenCode stopped (exit $code), restarting"
            } catch (e: Exception) {
                if (!token.cancelled) fail("OpenCode stopped", e)
            } finally {
                // A failed readiness/pairing attempt must not leave a server
                // holding the loopback port when the supervisor retries.
                started?.let { child ->
                    try {
                        ChildProcess.terminate(child)
                    } catch (e: Exception) {
                        // Refuse a second owner if the old process cannot be confirmed dead.
                        token.restartAllowed = false
                        fail("Could not stop the local server; restart the app", e)
                        return
                    }
                }
                pairingUrl = null
            }
            if (token.cancelled) break
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                if (token.cancelled) break
            }
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
        status = "Stopped"
    }

    private fun launch(context: Context): Process {
        password = storedPassword(context)
        val workspace = File(Termux.HOME, "projects/workspace")
        check(workspace.isDirectory || workspace.mkdirs()) { "Cannot create project workspace: $workspace" }
        val command = "exec ${Termux.PREFIX}/bin/opencode serve --hostname $HOST --port $PORT"
        val builder = ProcessBuilder("${Termux.PREFIX}/bin/bash", "-lc", command)
            .directory(workspace)
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            putAll(Termux.environment(android.os.Process.myUid()))
            put("OPENCODE_PASSWORD", password)
        }
        val started = builder.start()
        Thread {
            try {
                started.inputStream.bufferedReader().forEachLine { line ->
                    Log.i(TAG, line)
                    synchronized(output) {
                        output.append(line).append('\n')
                        if (output.length > LOG_LIMIT) output.delete(0, output.length - LOG_LIMIT)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "server output closed", e)
            }
        }.apply { isDaemon = true }.start()
        return started
    }

    /** Polls /api/info until the server answers, then redeems a pairing code. */
    private fun awaitPairing(started: Process, token: HostSupervisor.Cancellation): String {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (!token.cancelled && System.currentTimeMillis() < deadline) {
            if (!started.isAlive) throw IllegalStateException("OpenCode exited during startup:\n${log()}")
            if (statusCode("GET", "/api/info") == 200) return pairingURL()
            Thread.sleep(500)
        }
        throw IllegalStateException("OpenCode did not become ready:\n${log()}")
    }

    private fun pairingURL(): String {
        val request = connection("POST", "/api/pair")
        val body = try {
            check(request.responseCode == 200) { "pairing failed: HTTP ${request.responseCode}" }
            request.inputStream.bufferedReader().use { it.readText() }
        } finally {
            request.disconnect()
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
    private fun authorization(): String =
        "Basic " + Base64.encodeToString("opencode:$password".toByteArray(), Base64.NO_WRAP)

    /** One locally generated password per install; rotating it signs sessions out. */
    private fun storedPassword(context: Context): String {
        val preferences = context.getSharedPreferences("opencode", Context.MODE_PRIVATE)
        preferences.getString(PASSWORD_KEY, null)?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val generated = bytes.joinToString("") { "%02x".format(it) }
        preferences.edit().putString(PASSWORD_KEY, generated).apply()
        return generated
    }

    private fun fail(message: String, e: Exception) {
        Log.e(TAG, message, e)
        status = message
        error = "$message: ${e.message ?: e.toString()}"
    }

    private fun log(): String = synchronized(output) { output.toString() }
}
