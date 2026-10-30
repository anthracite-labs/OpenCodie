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

    // This marker is not in the APK ZIP. The bundled version file is NOT
    // evidence that Termux's post-install scripts finished.
    private const val READY_FILE = ".opencodie-ready"
    private const val INSTALLING_FILE = ".opencodie-installing"

    fun installIfNeeded(context: Context, log: (String) -> Unit) {
        val files = context.filesDir
        val prefix = File(files, "usr")
        val staging = File(files, "usr-staging")
        val ready = File(files, READY_FILE)
        val installing = File(files, INSTALLING_FILE)
        val bundled = asset(context, VERSION_ASSET).trim()
        val installed = ready.takeIf { it.isFile }?.readText()?.trim()

        if (installed == null) {
            if (installing.isFile) {
                // This app owns an interrupted *first* install. Starting again
                // is required: the upstream second-stage lock is permanent.
                log("Retrying interrupted initial installation")
                check(prefix.deleteRecursively()) { "Could not clear incomplete bootstrap" }
            } else {
                check(!prefix.exists() || (prefix.isDirectory && prefix.listFiles()?.isEmpty() == true)) {
                    "Unverified existing Termux prefix preserved; refusing to erase user packages"
                }
                if (prefix.exists()) check(prefix.delete()) { "Could not clear empty prefix" }
            }
            installing.writeText("$bundled\n")
            bootstrap(context, prefix, staging, log)
            ensureDirectories(prefix)
            runSecondStage(prefix, log)
            writeOpenCodeConfig(log)
            writeVersion(prefix, bundled)
            writeMarker(ready, bundled)
            installing.delete()
            log("Termux runtime $bundled ready")
            return
        }

        check(prefix.isDirectory) { "Verified Termux prefix missing: $prefix" }
        // If an interrupted upgrade left backup files, rollback before retry.
        recoverUpgrade(files, prefix, installed == bundled, log)
        if (installed != bundled) {
            log("Upgrading OpenCode $installed -> $bundled")
            upgrade(context, prefix, files)
            writeVersion(prefix, bundled)
            writeMarker(ready, bundled)
            clearUpgradeBackup(files)
        }
        ensureDirectories(prefix)
        writeOpenCodeConfig(log)
        log("Runtime $bundled ready")
    }

    private fun ensureDirectories(prefix: File) {
        check(File(Termux.HOME).isDirectory || File(Termux.HOME).mkdirs())
        check(File(prefix, "tmp").isDirectory || File(prefix, "tmp").mkdirs())
        check(File(Termux.CACHE).isDirectory || File(Termux.CACHE).mkdirs())
    }

    private fun writeVersion(prefix: File, version: String) {
        File(prefix, VERSION_FILE).apply { parentFile?.mkdirs() }.writeText("$version\n")
    }

    private fun writeMarker(marker: File, version: String) {
        val temp = File(marker.parentFile, "${marker.name}.tmp")
        temp.writeText("$version\n")
        check(temp.renameTo(marker)) { "Could not record completed runtime version" }
    }

    private fun bootstrap(context: Context, prefix: File, staging: File, log: (String) -> Unit) {
        verifyChecksum(context)
        staging.deleteRecursively()
        try {
            val symlinks = extract(context, staging) { true }
            check(symlinks.isNotEmpty()) { "Runtime archive has no SYMLINKS.txt" }
            for ((target, link) in symlinks) {
                File(link).parentFile?.mkdirs()
                Os.symlink(target, link)
            }
            check(!prefix.exists()) { "Refusing to replace existing Termux files" }
            check(staging.renameTo(prefix)) { "Could not move the runtime into place" }
            log("Runtime extracted")
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Keep both old executable and launcher until the new ready marker commits. */
    private fun upgrade(context: Context, prefix: File, files: File) {
        verifyChecksum(context)
        val staging = File(files, "opencode-staging")
        val active = File(prefix, "libexec/opencode")
        val backup = File(files, "opencode-backup")
        val launcher = File(prefix, "bin/opencode")
        val launcherBackup = File(files, "opencode-launcher-backup")
        check(!backup.exists() && !launcherBackup.exists()) { "Unrecovered OpenCode backup" }
        staging.deleteRecursively()
        try {
            extract(context, staging) { name -> APK_OWNED.any { name.startsWith(it) } }
            val incoming = File(staging, "libexec/opencode")
            val newLauncher = File(staging, "bin/opencode")
            val binary = File(incoming, "opencode.bin")
            check(binary.isFile && newLauncher.isFile && launcher.isFile && active.isDirectory) {
                "Missing OpenCode executable or launcher"
            }
            Os.chmod(binary.path, EXEC_MODE)
            Os.chmod(newLauncher.path, EXEC_MODE)
            launcher.copyTo(launcherBackup)
            check(active.renameTo(backup)) { "Could not back up old OpenCode" }
            check(incoming.renameTo(active)) { "Could not activate new OpenCode" }
            val replacement = File(launcher.parentFile, "${launcher.name}.incoming")
            newLauncher.copyTo(replacement, overwrite = true)
            Os.chmod(replacement.path, EXEC_MODE)
            check(replacement.renameTo(launcher)) { "Could not switch OpenCode launcher" }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun recoverUpgrade(files: File, prefix: File, committed: Boolean, log: (String) -> Unit) {
        val backup = File(files, "opencode-backup")
        val launcherBackup = File(files, "opencode-launcher-backup")
        if (!backup.exists()) {
            launcherBackup.delete()
            return
        }
        if (committed) {
            clearUpgradeBackup(files)
            return
        }
        log("Recovering interrupted OpenCode upgrade")
        check(File(prefix, "libexec/opencode").deleteRecursively())
        check(backup.renameTo(File(prefix, "libexec/opencode")))
        if (launcherBackup.isFile) {
            check(launcherBackup.renameTo(File(prefix, "bin/opencode")))
        }
    }

    private fun clearUpgradeBackup(files: File) {
        File(files, "opencode-backup").deleteRecursively()
        File(files, "opencode-launcher-backup").delete()
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
                    // All extraction and symlink destinations must stay inside staging.
                    // A valid checksum alone does not make ZIP paths safe.
                    if (name != "SYMLINKS.txt") safePath(destination, name)
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
                            symlinks += parts[0] to safePath(destination, parts[1].removePrefix("./")).path
                        }
                    } else if (keep(name)) {
                        val target = safePath(destination, name)
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

    private fun safePath(root: File, name: String): File {
        check(!name.startsWith("/") && name.split('/').none { it == ".." }) {
            "Unsafe runtime archive path: $name"
        }
        val canonicalRoot = root.canonicalFile
        val result = File(root, name).canonicalFile
        check(result == canonicalRoot || result.path.startsWith(canonicalRoot.path + File.separator)) {
            "Runtime archive path leaves staging: $name"
        }
        return result
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
        // Stock OpenCode global instructions; never overwrite the user's edits.
        val instructions = File(Termux.HOME, ".config/opencode/AGENTS.md")
        if (!instructions.exists()) {
            instructions.parentFile?.mkdirs()
            instructions.writeText(
                """
                # OpenCodie Android environment

                - This is native Termux on Android. Use pkg, never sudo, to install Termux packages.
                - The OpenCode shell tool is noninteractive: use `pkg install -y PACKAGE` for requested installs. Never claim success before checking the exit status.
                - Create Git repositories inside named project directories under ~/projects; never initialize Git in ~ or ~/projects itself.
                - If a long-running command is backgrounded, verify its eventual outcome before reporting success.
                """.trimIndent() + "\n",
            )
            log("Wrote ${instructions.path}")
        }
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
