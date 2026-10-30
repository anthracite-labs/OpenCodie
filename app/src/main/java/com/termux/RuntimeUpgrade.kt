package com.termux

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Only the two APK-owned paths are replaced; the ready marker commits the transaction. */
internal class RuntimeUpgrade(
    private val files: File,
    private val prefix: File,
    private val checkpoint: (String) -> Unit = {},
) {
    private val active = File(prefix, "libexec/opencode")
    private val launcher = File(prefix, "bin/opencode")
    private val backup = File(files, "opencode-backup")
    private val launcherBackup = File(files, "opencode-launcher-backup")
    private val ready = File(files, ".opencodie-ready")
    private val version = File(prefix, "etc/opencode-apk/version")

    fun install(staging: File, bundled: String) {
        check(!backup.exists() && !launcherBackup.exists()) { "Unrecovered OpenCode backup" }
        val incoming = File(staging, "libexec/opencode")
        val newLauncher = File(staging, "bin/opencode")
        check(Files.isRegularFile(File(incoming, "opencode.bin").toPath(), NOFOLLOW_LINKS) &&
            Files.isRegularFile(newLauncher.toPath(), NOFOLLOW_LINKS) &&
            Files.isRegularFile(launcher.toPath(), NOFOLLOW_LINKS) &&
            Files.isDirectory(active.toPath(), NOFOLLOW_LINKS)) { "Missing or unsafe OpenCode executables" }
        for (path in listOf(active, launcher, version)) {
            check(path.canonicalPath.startsWith(prefix.canonicalPath + File.separator)) { "APK-owned path escapes prefix" }
        }
        val oldVersion = ready.readText().trim()
        try {
            val temporaryBackup = File(files, "opencode-launcher-backup.tmp")
            Files.copy(launcher.toPath(), temporaryBackup.toPath(), COPY_ATTRIBUTES, REPLACE_EXISTING)
            check(temporaryBackup.renameTo(launcherBackup))
            checkpoint("launcher-backed-up")
            check(active.renameTo(backup)) { "Could not back up old OpenCode" }
            checkpoint("binary-backed-up")
            check(incoming.renameTo(active)) { "Could not activate new OpenCode" }
            checkpoint("binary-activated")
            val replacement = File(launcher.parentFile, launcher.name + ".incoming")
            Files.copy(newLauncher.toPath(), replacement.toPath(), COPY_ATTRIBUTES, REPLACE_EXISTING)
            checkpoint("launcher-staged")
            check(replacement.renameTo(launcher)) { "Could not activate launcher" }
            checkpoint("launcher-activated")
            FileOps.writeAtomic(version, "$bundled\n")
            checkpoint("version-written")
            FileOps.writeAtomic(ready, "$bundled\n")
            checkpoint("ready-written")
            recover(true, bundled)
            checkpoint("backup-cleared")
        } catch (error: Exception) {
            recover(ready.readText().trim() == bundled, oldVersion)
            throw error
        }
    }

    fun recover(committed: Boolean, installed: String) {
        check(!Files.isSymbolicLink(prefix.toPath()) && !Files.isSymbolicLink(backup.toPath()) &&
            !Files.isSymbolicLink(launcherBackup.toPath())) { "Unverified runtime recovery paths preserved" }
        for (path in listOf(active, launcher, version)) {
            check(path.canonicalPath.startsWith(prefix.canonicalPath + File.separator)) { "Unverified recovery path leaves prefix" }
        }
        if (committed) {
            FileOps.removeTree(backup)
            Files.deleteIfExists(launcherBackup.toPath())
            return
        }
        if (backup.exists()) {
            // Restore launcher first: the binary backup remains the rollback journal until the last rename.
            if (launcherBackup.isFile) check(launcherBackup.renameTo(launcher)) { "Could not restore launcher" }
            checkpoint("launcher-restored")
            FileOps.removeTree(active)
            checkpoint("active-cleared")
            check(backup.renameTo(active)) { "Could not restore OpenCode" }
            checkpoint("binary-restored")
            FileOps.writeAtomic(version, "$installed\n")
        } else {
            Files.deleteIfExists(launcherBackup.toPath())
        }
        if (version.takeIf { it.isFile }?.readText()?.trim() != installed) {
            FileOps.writeAtomic(version, "$installed\n")
        }
    }
}
