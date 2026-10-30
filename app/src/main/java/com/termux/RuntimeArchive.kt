package com.termux

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.TreeMap
import java.util.zip.ZipInputStream

/** Untrusted archive bytes may write only into fresh staging. Links are returned, never followed during extraction. */
internal object RuntimeArchive {
    data class Limits(
        val maxEntries: Int = 30_000,
        val maxFileBytes: Long = 512L * 1024 * 1024,
        val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
        val maxLinkBytes: Long = 4L * 1024 * 1024,
    )

    private enum class Kind { FILE, DIRECTORY, LINK }

    fun extract(input: InputStream, destination: File, keep: (String) -> Boolean = { true }, limits: Limits = Limits()): List<Pair<String, String>> {
        check(!Files.isSymbolicLink(destination.toPath())) { "Staging is a symlink" }
        check(!destination.exists() || (destination.isDirectory && destination.listFiles()?.isEmpty() == true)) { "Staging must be empty" }
        check(destination.isDirectory || destination.mkdirs()) { "Cannot create staging" }
        val entries = TreeMap<String, Kind>()
        val links = linkedMapOf<String, String>()
        var count = 0
        var total = 0L
        var metadata: String? = null

        fun record(name: String, kind: Kind) {
            check(++count <= limits.maxEntries) { "Archive entry limit exceeded" }
            check(!entries.containsKey(name)) { "Duplicate archive path" }
            var parent = name.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                check(entries[parent] == null || entries[parent] == Kind.DIRECTORY) { "Archive parent is not a directory" }
                parent = parent.substringBeforeLast('/', "")
            }
            val descendant = entries.ceilingKey("$name/")
            check(kind == Kind.DIRECTORY || descendant?.startsWith("$name/") != true) { "Archive type collision" }
            entries[name] = kind
        }

        ZipInputStream(input.buffered(64 * 1024)).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = normalized(entry.name)
                record(name, if (entry.isDirectory) Kind.DIRECTORY else Kind.FILE)
                val isLinks = name == "SYMLINKS.txt"
                check(!isLinks || !entry.isDirectory) { "Invalid symlink metadata" }
                // Resolve after keep(): a callback or concurrent actor must not introduce a link parent
                // between path validation and file creation.
                val selected = !isLinks && keep(name)
                val target = File(destination, name)
                check(target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) { "Archive leaves staging" }
                if (selected) check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                if (selected && entry.isDirectory) check(target.isDirectory || target.mkdirs())
                val text = if (isLinks) ByteArrayOutputStream() else null
                val output = if (selected && !entry.isDirectory) Files.newOutputStream(
                    target.toPath(),
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                ) else null
                var size = 0L
                try {
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        size += read
                        total += read
                        check(size <= limits.maxFileBytes && total <= limits.maxTotalBytes) { "Archive size limit exceeded" }
                        check(!entry.isDirectory || size == 0L) { "Directory contains data" }
                        check(!isLinks || size <= limits.maxLinkBytes) { "Symlink metadata too large" }
                        output?.write(buffer, 0, read)
                        text?.write(buffer, 0, read)
                    }
                } finally { output?.close() }
                if (selected && !entry.isDirectory && (name.startsWith("bin/") || name.startsWith("libexec/") || name.startsWith("lib/apt/"))) {
                    check(target.setExecutable(true, true)) { "Cannot mark executable" }
                }
                if (isLinks) metadata = text!!.toString("UTF-8")
                zip.closeEntry()
            }
        }
        check(metadata != null) { "Runtime archive has no SYMLINKS.txt" }
        for (line in metadata.lines().filter { it.isNotBlank() }) {
            val parts = line.split('←')
            check(parts.size == 2 && parts[0].isNotEmpty() && !parts[0].contains('\u0000')) { "Malformed symlink" }
            val name = normalized(parts[1])
            record(name, Kind.LINK)
            links[name] = parts[0]
        }
        for ((name, target) in links) validateTarget(name, target, links)
        return links.map { (name, target) -> target to File(destination, name).path }
    }

    private fun normalized(raw: String): String {
        val name = raw.removePrefix("./").removeSuffix("/")
        check(name.isNotEmpty() && name.length <= 4096 && !name.startsWith('/') &&
            !name.contains('\\') && !name.contains('\u0000') &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
            name != ".opencodie-install-id") { "Unsafe archive path" }
        return name
    }

    /** Resolve link components in order, including .. after another link, rather than only normalizing text. */
    private fun validateTarget(name: String, target: String, links: Map<String, String>) {
        val initial = if (target.startsWith('/')) target else "${Termux.PREFIX}/${name.substringBeforeLast('/', "")}/$target"
        val pending = ArrayDeque(initial.split('/'))
        val stack = ArrayList<String>()
        var expansions = 0
        while (pending.isNotEmpty()) {
            when (val part = pending.removeFirst()) {
                "", "." -> Unit
                ".." -> { check(stack.isNotEmpty()) { "Symlink escapes filesystem" }; stack.removeAt(stack.lastIndex) }
                else -> {
                    stack.add(part)
                    val absolute = "/" + stack.joinToString("/")
                    val relative = absolute.removePrefix(Termux.PREFIX + "/")
                    val next = if (absolute.startsWith(Termux.PREFIX + "/")) links[relative] else null
                    if (next != null) {
                        check(++expansions <= 40) { "Symlink cycle or excessive chain" }
                        stack.removeAt(stack.lastIndex)
                        if (next.startsWith('/')) stack.clear()
                        for (component in next.split('/').asReversed()) pending.addFirst(component)
                    }
                }
            }
        }
        val resolved = "/" + stack.joinToString("/")
        check(resolved == Termux.PREFIX || resolved.startsWith(Termux.PREFIX + "/") ||
            resolved == "/system/bin" || resolved.startsWith("/system/bin/") ||
            resolved.startsWith("/apex/com.android.runtime/bin/") || resolved == "/dev/null") {
            "Symlink target outside allowed Termux or Android system paths"
        }
    }
}
