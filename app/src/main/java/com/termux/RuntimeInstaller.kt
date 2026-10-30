package com.termux

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.File
import java.security.MessageDigest

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

    private const val EXEC_MODE = 448 // 0700

    // This marker is not in the APK ZIP. The bundled version file is NOT
    // evidence that Termux's post-install scripts finished.
    private const val READY_FILE = ".opencodie-ready"
    private const val INSTALLING_FILE = ".opencodie-installing"

    fun installIfNeeded(context: Context, log: (String) -> Unit) {
        val files = context.filesDir
        BootstrapJournal(files, ::bootstrapGroupAlive).recover()
        val prefix = File(files, "usr")
        val staging = File(files, "usr-staging")
        val ready = File(files, READY_FILE)
        val installing = File(files, INSTALLING_FILE)
        val bundled = asset(context, VERSION_ASSET).trim()
        val installed = ready.takeIf { it.isFile }?.readText()?.trim()

        if (installed == null) {
            verifyChecksum(context)
            val installId = InitialInstall(files).prepare()
            bootstrap(context, prefix, staging, installId, log)
            ensureDirectories(prefix)
            selectDefaultMirror(prefix)
            runSecondStage(prefix, log)
            writeOpenCodeConfig(context, log)
            writeVersion(prefix, bundled)
            writeMarker(ready, bundled)
            installing.delete()
            log("Termux runtime ready")
            return
        }

        check(prefix.isDirectory) { "Verified Termux prefix missing: $prefix" }
        // If an interrupted upgrade left backup files, rollback before retry.
        recoverUpgrade(files, prefix, installed == bundled, log)
        if (installed != bundled) {
            log("Updating bundled OpenCode")
            upgrade(context, prefix, files)
        }
        ensureDirectories(prefix)
        writeOpenCodeConfig(context, log)
        log("Runtime ready")
    }

    /** Select the stock Termux origin mirror before the first pkg call. */
    private fun selectDefaultMirror(prefix: File) {
        val mirrors = File(prefix, "etc/termux/mirrors")
        val default = File(mirrors, "default")
        check(default.isFile) { "Bundled Termux default mirror is missing" }
        val selected = File(prefix, "etc/termux/chosen_mirrors")
        if (!selected.exists()) Os.symlink(default.path, selected.path)
    }

    private fun ensureDirectories(prefix: File) {
        check(File(Termux.HOME).isDirectory || File(Termux.HOME).mkdirs())
        check(File(prefix, "tmp").isDirectory || File(prefix, "tmp").mkdirs())
        check(File(Termux.CACHE).isDirectory || File(Termux.CACHE).mkdirs())
    }

    private fun writeVersion(prefix: File, version: String) {
        File(prefix, VERSION_FILE).apply { parentFile?.mkdirs() }.writeText("$version\n")
    }

    private fun writeMarker(marker: File, version: String) = FileOps.writeAtomic(marker, "$version\n")

    private fun bootstrap(context: Context, prefix: File, staging: File, installId: String, log: (String) -> Unit) {
        FileOps.removeTree(staging)
        try {
            val symlinks = extract(context, staging) { true }
            for ((target, link) in symlinks) {
                File(link).parentFile?.mkdirs()
                Os.symlink(target, link)
            }
            InitialInstall(context.filesDir).stamp(staging, installId)
            check(!prefix.exists()) { "Refusing to replace existing Termux files" }
            check(staging.renameTo(prefix)) { "Could not move the runtime into place" }
            log("Runtime extracted")
        } finally {
            FileOps.removeTree(staging)
        }
    }

    /** Validate staging before RuntimeUpgrade touches the prior working installation. */
    private fun upgrade(context: Context, prefix: File, files: File) {
        verifyChecksum(context)
        val staging = File(files, "opencode-staging")
        FileOps.removeTree(staging)
        try {
            extract(context, staging) { name -> name == "bin/opencode" || name.startsWith("libexec/opencode/") }
            Os.chmod(File(staging, "libexec/opencode/opencode.bin").path, EXEC_MODE)
            Os.chmod(File(staging, "bin/opencode").path, EXEC_MODE)
            RuntimeUpgrade(files, prefix).install(staging, asset(context, VERSION_ASSET).trim())
        } finally { FileOps.removeTree(staging) }
    }

    private fun recoverUpgrade(files: File, prefix: File, committed: Boolean, log: (String) -> Unit) {
        log("Checking interrupted OpenCode upgrade")
        RuntimeUpgrade(files, prefix).recover(committed, File(files, READY_FILE).readText().trim())
    }

    private fun extract(context: Context, destination: File, keep: (String) -> Boolean): List<Pair<String, String>> =
        try {
            context.assets.open(ZIP).use { RuntimeArchive.extract(it, destination, keep) }
        } catch (error: java.io.IOException) {
            throw IllegalStateException("Unsafe or unreadable bundled runtime archive", error)
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
        val groupFile = File.createTempFile("bootstrap-group-", ".pid", File(Termux.CACHE))
        val bash = "${prefix.path}/bin/bash"
        val journal = BootstrapJournal(prefix.parentFile!!, ::bootstrapGroupAlive)
        val id = journal.begin()
        val process = ProcessBuilder(BootstrapProcess.command("${prefix.path}/bin/setsid", bash, script.path, groupFile.path, journal.ack(id).path))
            .directory(File(Termux.HOME))
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    clear()
                    putAll(Termux.environment(Process.myUid()))
                }
            }
            .start()
        var cleaned = false
        fun cleanup() {
            val group = BootstrapProcess.groupId(groupFile, BootstrapProcess.currentGroup())
            if (group == null) {
                // An unauthorized launcher cannot execute scripts; its unique ack will never be published.
                ChildProcess.terminate(process)
            } else {
                ProcessGroup.terminate(process,
                    signal = { signal ->
                        try { Os.kill(-group, signal) } catch (error: ErrnoException) {
                            if (error.errno != OsConstants.ESRCH) throw ChildProcess.Unterminated()
                        }
                    }, alive = { bootstrapGroupAlive(group) })
            }
            cleaned = true
        }
        try {
            BootstrapProcess.authorize(process, groupFile, journal, id, BootstrapProcess.currentGroup())
            BootstrapProcess.await(process, cleanup = ::cleanup)
        } finally {
            if (!cleaned) cleanup()
            if (cleaned) journal.finish(id)
            groupFile.delete()
        }
    }

    private fun bootstrapGroupAlive(group: Int): Boolean = try {
        Os.kill(-group, 0)
        true
    } catch (error: ErrnoException) { error.errno != OsConstants.ESRCH }

    /** Initial OpenCode configuration; later user edits are preserved. */
    private fun writeOpenCodeConfig(contextForConfig: Context, log: (String) -> Unit) {
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
        if (!InitialConfig.writeIfAbsent(config, asset(contextForConfig, "opencode-default.json"))) return
        log("Wrote ${config.path}")
    }

    private fun asset(context: Context, name: String) =
        context.assets.open(name).bufferedReader().use { it.readText() }
}
