package com.termux

import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

internal object InitialConfig {
    /** Publish completed bytes with an atomic no-replace hard link on the private filesystem. */
    fun writeIfAbsent(file: File, text: String, beforePublish: () -> Unit = {}): Boolean {
        if (Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false
        file.parentFile?.mkdirs()
        val temporary = Files.createTempFile(file.parentFile!!.toPath(), ".opencodie-config-", ".tmp")
        return try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            beforePublish()
            // Unlike rename/ATOMIC_MOVE, link fails if a concurrent config or dangling symlink exists.
            Files.createLink(file.toPath(), temporary)
            true
        } catch (_: FileAlreadyExistsException) {
            false
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
