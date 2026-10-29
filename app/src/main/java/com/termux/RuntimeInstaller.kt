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
 * First-launch provisioning of the Termux userspace bundled in the APK.
 *
 * The archive layout is the Termux bootstrap layout: entries relative to
 * ${Termux.PREFIX} plus a SYMLINKS.txt of `target←link` lines, so extraction is
 * the same procedure TermuxInstaller performs after downloading a bootstrap —
 * except that the archive ships inside the APK and nothing is fetched.
 */
object RuntimeInstaller {
    private const val ZIP = "runtime-aarch64.zip"
    private const val VERSION_ASSET = "runtime.version"
    private const val SHA256_ASSET = "runtime.sha256"
    private const val VERSION_FILE = "etc/opencode-apk/version"

    /** APK-owned paths inside the prefix; an APK upgrade replaces only these. */
    private val APK_OWNED = listOf("bin/opencode", "libexec/opencode/", "etc/opencode-apk/")

    private const val EXEC_MODE = 448 // 0700

    fun installIfNeeded(context: Context, log: (String) -> Unit) {
        val files = context.filesDir
        val prefix = File(files, "usr")
        val bundled = asset(context, VERSION_ASSET).trim()
        val installed = File(prefix, VERSION_FILE).takeIf { it.isFile }?.readText()?.trim()

        when {
            installed == null -> {
                log("Installing bundled runtime $bundled")
                verifyChecksum(context)
                installBootstrap(context, files, prefix)
            }
            installed != bundled -> {
                log("Updating OpenCode to $bundled")
                extract(context, prefix) { name -> APK_OWNED.any { name.startsWith(it) } }
            }
            else -> log("Runtime $bundled already installed")
        }

        File(prefix, VERSION_FILE).apply { parentFile?.mkdirs() }.writeText("$bundled\n")
        // home, tmp and cache are outside the bootstrap archive and must exist
        // before anything runs (APT, dpkg and the shell all rely on them).
        File(Termux.HOME).mkdirs()
        File(prefix, "tmp").mkdirs()
        File(Termux.CACHE).mkdirs()

        runSecondStage(prefix, log)
        writeOpenCodeConfig(log)
    }

    /** Unpacks into a staging prefix, restores symlinks, then renames it into place. */
    private fun installBootstrap(context: Context, files: File, prefix: File) {
        val staging = File(files, "usr-staging")
        staging.deleteRecursively()
        val symlinks = extract(context, staging) { true }
        check(symlinks.isNotEmpty()) { "Runtime archive has no SYMLINKS.txt" }
        for ((target, link) in symlinks) Os.symlink(target, link)
        prefix.deleteRecursively()
        check(staging.renameTo(prefix)) { "Could not move staging prefix into place" }
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
     * Runs Termux's bootstrap second stage (the postinst maintainer scripts that
     * manual extraction skips). It is guarded by its own lock symlink and is a
     * one-time operation for the lifetime of the prefix.
     */
    private fun runSecondStage(prefix: File, log: (String) -> Unit) {
        val script = File(prefix, "etc").walkTopDown()
            .firstOrNull { it.isFile && it.name == "termux-bootstrap-second-stage.sh" } ?: return
        if (File(script.parentFile, "${script.name}.lock").exists()) return
        log("Finishing Termux package configuration")
        val process = ProcessBuilder("${prefix.path}/bin/bash", script.path)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    clear()
                    putAll(Termux.environment(Process.myUid()))
                }
            }
            .start()
        process.inputStream.bufferedReader().forEachLine { log(it) }
        check(process.waitFor() == 0) { "Termux bootstrap second stage failed" }
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
