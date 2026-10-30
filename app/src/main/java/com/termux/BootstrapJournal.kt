package com.termux

import java.io.File
import java.util.UUID

/** A pending launcher cannot execute package scripts until its active group record is durable. */
internal class BootstrapJournal(private val files: File, private val groupAlive: (Int) -> Boolean) {
    private val marker = File(files, ".opencodie-bootstrap")

    fun recover() {
        if (!marker.exists()) return
        val record = read()
        if (record[0] == "active") {
            val group = record[2].toIntOrNull()
            check(group != null && group > 1 && !groupAlive(group)) {
                "Unverified bootstrap process may still be running; force-stop OpenCodie in Android settings, then reopen. User files preserved."
            }
        } else {
            // No active record means no execution permission was published to this unique launcher.
            check(!ack(record[1]).exists()) { "Unverified bootstrap handshake preserved" }
        }
        finish(record[1])
    }

    fun begin(): String {
        check(!marker.exists()) { "Unrecovered bootstrap journal" }
        val id = UUID.randomUUID().toString()
        FileOps.writeAtomic(marker, "pending:$id\n")
        return id
    }

    fun activate(id: String, group: Int) {
        check(read()[1] == id && group > 1)
        FileOps.writeAtomic(marker, "active:$id:$group\n")
        FileOps.writeAtomic(ack(id), "go\n")
    }

    fun ack(id: String): File = File(files, ".opencodie-bootstrap-$id.go")

    fun finish(id: String) {
        if (!marker.exists() || read()[1] != id) return
        check(!ack(id).exists() || ack(id).delete())
        check(marker.delete())
    }

    private fun read(): List<String> {
        check(marker.length() <= 128 && !java.nio.file.Files.isSymbolicLink(marker.toPath())) { "Unverified bootstrap journal preserved" }
        val fields = marker.readText().trim().split(':')
        check(fields.size in 2..3 && fields[1].matches(Regex("[a-f0-9-]{36}")) &&
            ((fields[0] == "pending" && fields.size == 2) || (fields[0] == "active" && fields.size == 3))) {
            "Unverified bootstrap journal preserved"
        }
        return fields
    }
}
