package com.termux

/** Render only fixed categories. Pattern-based secret replacement cannot cover arbitrary upstream output. */
internal object Diagnostics {
    fun fromOutput(text: String): String? {
        val value = text.lowercase()
        return when {
            "eaddrinuse" in value || "address already in use" in value -> "The local server port is already in use."
            "enospc" in value || "no space left" in value -> "Device storage is full; free space and retry."
            "eacces" in value || "permission denied" in value -> "A required runtime path is inaccessible."
            "checksum mismatch" in value || "integrity" in value -> "Bundled runtime integrity verification failed; reinstall a verified APK."
            "bootstrap process may still be running" in value -> "Force-stop OpenCodie in Android settings, then reopen. The unfinished runtime was preserved."
            "unverified" in value -> "The existing runtime is unverified; user files were preserved."
            "unsafe" in value || "symlink" in value || "archive" in value -> "The bundled runtime archive was rejected."
            "timed out" in value || "deadline" in value -> "Runtime startup timed out; retry package configuration."
            "bootstrap launcher stopped before authorization" in value -> "The Termux bootstrap launcher exited before setup began."
            "package configuration failed" in value -> "Termux package configuration failed; retry and report the setup stage."
            else -> null
        }
    }

    fun failure(error: Exception): String {
        // Only fixed categories may reach the UI or Logcat, never raw failure messages.
        // A useful low-level cause (e.g. ENOSPC) may be wrapped by an archive error.
        val causes = ArrayList<Throwable>()
        var current: Throwable? = error
        while (current != null && causes.size < 8 && current !in causes) {
            causes.add(current)
            current = current.cause
        }
        for (cause in causes.asReversed()) {
            fromOutput(cause.message.orEmpty())?.let { return it }
        }
        return when (error) {
            is ChildProcess.Unterminated -> "A previous process could not stop; restart the app before retrying."
            is java.io.IOException -> "Runtime I/O failed during setup; retry or report the setup stage."
            is InterruptedException -> "Runtime startup was interrupted."
            else -> "Runtime operation failed; use a verified APK and preserve the existing runtime for recovery."
        }
    }
}
