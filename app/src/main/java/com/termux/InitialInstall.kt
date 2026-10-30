package com.termux

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

internal class InitialInstall(private val files: File) {
    private val prefix = File(files, "usr")
    private val installing = File(files, ".opencodie-installing")

    fun prepare(): String {
        check(!Files.isSymbolicLink(prefix.toPath())) { "Unverified prefix symlink preserved" }
        if (Files.exists(prefix.toPath(), NOFOLLOW_LINKS)) {
            val id = installing.takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }?.readText()?.trim()
            val ownership = File(prefix, ".opencodie-install-id")
            val owned = id?.matches(Regex("install-v1:[a-f0-9-]{36}")) == true &&
                ownership.isFile && !Files.isSymbolicLink(ownership.toPath()) && ownership.readText().trim() == id
            check(owned || (prefix.isDirectory && prefix.listFiles()?.isEmpty() == true)) {
                "Unverified existing Termux prefix preserved; refusing to erase user packages"
            }
            FileOps.removeTree(prefix)
        }
        val id = "install-v1:${UUID.randomUUID()}"
        FileOps.writeAtomic(installing, "$id\n")
        return id
    }

    fun stamp(staging: File, id: String) {
        check(InitialConfig.writeIfAbsent(File(staging, ".opencodie-install-id"), "$id\n")) {
            "Archive contains a reserved installation marker"
        }
    }
}
