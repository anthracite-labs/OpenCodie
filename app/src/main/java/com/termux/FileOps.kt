package com.termux

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult.CONTINUE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object FileOps {
    /** Unlike File.walk/deleteRecursively, never traverse symlinks in a Termux prefix. */
    fun removeTree(file: File) {
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(path)
                return CONTINUE
            }
            override fun postVisitDirectory(path: Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.delete(path)
                return CONTINUE
            }
        })
    }

    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        check(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(temporary.toPath())) { "Unsafe runtime marker" }
        FileOutputStream(temporary).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
}
