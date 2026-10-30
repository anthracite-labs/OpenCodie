package com.termux

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal object StorageLinks {
    fun setup(storage: File, links: Map<String, File>, beforeCreate: (String) -> Unit = {}) {
        val root = storage.toPath()
        check(!Files.isSymbolicLink(root)) { "~/storage is a symlink" }
        check(!Files.exists(root, NOFOLLOW_LINKS) || Files.isDirectory(root, NOFOLLOW_LINKS)) { "~/storage already exists" }
        // Validate the entire plan before creating even the first link.
        val missing = links.filter { (name, target) ->
            check(name.matches(Regex("[a-z]+(?:-[0-9]+)?")) && target.isAbsolute) { "Invalid storage link" }
            val link = root.resolve(name)
            if (Files.isSymbolicLink(link)) {
                check(Files.readSymbolicLink(link).toString() == target.absolutePath) { "Existing ~/storage/$name link points elsewhere" }
                false
            } else {
                check(!Files.exists(link, NOFOLLOW_LINKS)) { "~/storage/$name already exists; keeping user data" }
                true
            }
        }
        val createdRoot = !Files.exists(root, NOFOLLOW_LINKS)
        val created = ArrayList<Pair<Path, Path>>()
        try {
            if (createdRoot) Files.createDirectory(root)
            for ((name, target) in missing) {
                beforeCreate(name)
                val link = root.resolve(name)
                Files.createSymbolicLink(link, target.toPath())
                created.add(link to target.toPath())
            }
        } catch (error: Exception) {
            for ((link, target) in created.asReversed()) {
                if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == target) Files.delete(link)
            }
            if (createdRoot && storage.listFiles()?.isEmpty() == true) Files.delete(root)
            throw error
        }
    }
}
