package com.termux

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

internal object InitialConfig {
    /** CREATE_NEW also preserves dangling symlinks and configurations created concurrently. */
    fun writeIfAbsent(file: File, text: String): Boolean {
        file.parentFile?.mkdirs()
        return try {
            Files.write(file.toPath(), text.toByteArray(Charsets.UTF_8), CREATE_NEW, WRITE)
            true
        } catch (_: FileAlreadyExistsException) {
            false
        }
    }
}
