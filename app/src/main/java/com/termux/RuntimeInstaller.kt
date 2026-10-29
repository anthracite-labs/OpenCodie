package com.termux

import android.content.Context
import android.os.Process
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Provisioning of the Termux userspace bundled in the APK.
 *
 * The archive layout is the Termux bootstrap layout: entries relative to
 * ${Termux.PREFIX} plus a SYMLINKS.txt of `target←link` lines, so extraction is
 * the same procedure TermuxInstaller performs after downloading a bootstrap —
 * except that the archive ships inside the APK and nothing is fetched.
 *
 * The installed-version marker is written last, so an interrupted first launch or
 * a failed package configuration never looks like a completed installation.
 */
object RuntimeInstaller {
    private const val ZIP = "runtime-aarch64.zip"
    private const val VERSION_ASSET = "runtime.version"
    private const val SHA256_ASSET = "runtime.sha256"
    private const val VERSION_FILE = "etc/opencode-apk/version"

    /** APK-owned binaries replaced on upgrade; everything else in the prefix is the user's. */
    private val APK_OWNED = listOf("bin/opencode", "libexec/opencode/")

    private const val EXEC_MODE = 448 // 0700

    fun installIfNeeded(context: Context, log: (String) -> Unit) {
        val files = context.filesDir
        val prefix = File(files, "usr")
        val staging = File(files, "usr-staging")

        // A leftover staging directory means a previous extraction was interrupted.
        staging.deleteRecursively()

        val bundled = asset(context, VERSION_ASSET).trim()
        val installed = File(prefix, VERSION_FILE).takeIf { it.isFile }?.readText()?.trim()

        when {
            installed == null -> {
                log("Installing bundled runtime $bundled")
                bootstrap(context, prefix, staging, log)
            }
            installed != bundled -> {
                log("Updating OpenCode $installed -> $bundled")
                upgrade(context, prefix, log)
            }
            else -> log("Runtime $bundled ready")
        }

        // home, tmp and cache live outside the bootstrap archive and must exist
        // before anything runs (APT, dpkg and the shell all rely on them).
        File(Termux.HOME).mkdirs()
        File(prefix, "tmp").mkdirs()
        File(Termux.CACHE).mkdirs()

        runSecondStage(prefix, log)
        writeOpenCodeConfig(log)

        // Completion marker: only now is the runtime usable.
        File(prefix, VERSION_FILE).apply { parentFile?.mkdirs() }.writeText("$bundled\n")
    }

    /**
     * Unpacks into a staging prefix, restores symlinks, then renames it into
     * place. User data (home, cache) lives outside the prefix, so a failed
     * first bootstrap is retried cleanly on the next launch.
     */
    private fun bootstrap(context: Context, prefix: File, staging: File, log: (String) -> Unit) {
        verifyChecksum(context)
        try {
            val symlinks = extract(context, staging) { true }
            check(symlinks.isNotEmpty()) { "Runtime archive has no SYMLINKS.txt" }
            for ((target, link) in symlinks) Os.symlink(target, link)
            prefix.deleteRecursively()
            check(staging.renameTo(prefix)) { "Could not move the runtime into place" }
            log("Runtime extracted")
        } catch (e: Exception) {
            staging.deleteRecursively()
            prefix.deleteRecursively()
            throw e
        }
    }

    /** Stages the new executable and switches it without modifying installed packages. */
    private fun upgrade(context: Context, prefix: File, log: (String) -> Unit) {
        verifyChecksum(context)
        val staging = File(prefix.parentFile, "opencode-staging")
        val active = File(prefix, "libexec/opencode")
        val backup = File(prefix.parentFile, "opencode-backup")
        val launcher = File(prefix, "bin/opencode")

        // Recover the previous binary if power was lost between the two renames.
        if (backup.exists() && !active.exists()) {
            check(backup.renameTo(active)) { "Could not restore previous OpenCode binary" }
        }
        if (backup.exists()) backup.deleteRecursively()
        staging.deleteRecursively()
        try {
            extract(context, staging) { name -> APK_OWNED.any { name.startsWith(it) } }
            val incoming = File(staging, "libexec/opencode")
            val stagedLauncher = File(staging, "bin/opencode")
            val stagedBinary = File(incoming, "opencode.bin")
            check(stagedBinary.isFile && stagedLauncher.isFile) {
                "Bundled runtime is missing OpenCode executable or launcher"
            }
            Os.chmod(stagedBinary.path, EXEC_MODE)
            Os.chmod(stagedLauncher.path, EXEC_MODE)

            // Move the entire versioned executable directory rather than mixing
            // old and new native files. Keep the previous directory for rollback.
            check(!active.exists() || active.renameTo(backup)) { "Could not back up OpenCode" }
            try {
                check(incoming.renameTo(active)) { "Could not activate new OpenCode" }
                val replacement = File(launcher.parentFile, "${launcher.name}.incoming")
                stagedLauncher.copyTo(replacement, overwrite = true)
                Os.chmod(replacement.path, EXEC_MODE)
                check(replacement.renameTo(launcher)) { "Could not replace OpenCode launcher" }
            } catch (e: Exception) {
                active.deleteRecursively()
                if (backup.exists()) check(backup.renameTo(active)) { "OpenCode rollback failed" }
                throw e
            }
            backup.deleteRecursively()
            log("OpenCode files replaced")
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Extracts [keep] entries of the bundled archive into [destination] and
     * returns the symlinks recorded by the archive.
     */
    private fun extract(context: Context, destination: File, keep: (String) -> Boolean): List<Pair<String, String>> {
        val symlinks = ArrayList<Pair<String, String>>()
        val buffer = ByteArray(64 * 1024)
        context.assets.open(ZIP).use { asset ->
            ZipInputStream(asset.buffered(1 shl 16)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name == "SYMLINKS.txt") {
                        val text = ByteArrayOutputStream()
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            text.write(buffer, 0, read)
                        }
                        for (line in text.toString("UTF-8").lines().filter { it.isNotBlank() }) {
                            val parts = line.split('←')
                            check(parts.size == 2) { "Malformed symlink line: $line" }
                            symlinks += parts[0] to File(destination, parts[1].removePrefix("./")).path
                        }
                    } else if (keep(name)) {
                        val target = File(destination, name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                }
                            }
                            if (name.startsWith("bin/") || name.startsWith("libexec/") ||
                                name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")
                            ) {
                                Os.chmod(target.path, EXEC_MODE)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        return symlinks
    }

    /** SHA-256 of the bundled archive against the checksum written at build time. */
    private fun verifyChecksum(context: Context) {
        val expected = asset(context, SHA256_ASSET).trim()
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(ZIP).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { "Bundled runtime checksum mismatch" }
    }

    /**
     * Runs Termux's bootstrap second stage: the postinst maintainer scripts that
     * manual extraction skips. It is guarded by its own lock symlink and is a
     * one-time operation for the lifetime of the prefix, so a retry after a
     * completed run is a no-op.
     */
    private fun runSecondStage(prefix: File, log: (String) -> Unit) {
        val script = File(prefix, "etc").walkTopDown()
            .firstOrNull { it.isFile && it.name == "termux-bootstrap-second-stage.sh" }
            ?: error("Bundled Termux bootstrap second-stage script is missing")
        if (File(script.parentFile, "${script.name}.lock").exists()) return
        log("Configuring Termux packages")
        val process = ProcessBuilder("${prefix.path}/bin/bash", script.path)
            .directory(File(Termux.HOME))
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    clear()
                    putAll(Termux.environment(Process.myUid()))
                }
            }
            .start()
        val tail = StringBuilder()
        process.inputStream.bufferedReader().forEachLine { line ->
            log(line)
            tail.append(line).append('\n')
            if (tail.length > 2_000) tail.delete(0, tail.length - 2_000)
        }
        val code = process.waitFor()
        check(code == 0) { "Termux bootstrap second stage failed (exit $code)\n$tail" }
    }

    /** Initial OpenCode configuration; later user edits are preserved. */
    private fun writeOpenCodeConfig(log: (String) -> Unit) {
        val config = File(Termux.HOME, ".config/opencode/opencode.json")
        if (config.exists()) return
        config.parentFile?.mkdirs()
        config.writeText(
            """
            {
              "${'$'}schema": "https://opencode.ai/config.json",
              "shell": "${Termux.PREFIX}/bin/bash",
              "permissions": [
                {
                  "action": "shell",
                  "resource": "*",
                  "effect": "ask"
                }
              ],
              "update": "disable"
            }

            """.trimIndent(),
        )
        log("Wrote ${config.path}")
    }

    private fun asset(context: Context, name: String) =
        context.assets.open(name).bufferedReader().use { it.readText() }
}
