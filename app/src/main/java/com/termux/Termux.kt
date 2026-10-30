package com.termux

import java.io.File

/**
 * The Termux environment OpenCodie runs in.
 *
 * The Termux prefix lives in this app's private data directory, so the package
 * identity ("com.termux") and every path below are fixed: APT metadata, package
 * conffiles and scripts all reference them absolutely.
 */
object Termux {
    const val DATA_DIR = "/data/data/com.termux"
    const val ROOTFS = "$DATA_DIR/files"
    const val PREFIX = "$ROOTFS/usr"
    const val HOME = "$ROOTFS/home"
    const val CACHE = "$DATA_DIR/cache"

    /** Environment for every Termux process we start (server, PTY daemon, shells). */
    fun environment(uid: Int): MutableMap<String, String> = linkedMapOf(
        "HOME" to HOME,
        "PREFIX" to PREFIX,
        "PATH" to "$PREFIX/bin",
        "TMPDIR" to "$PREFIX/tmp",
        "SHELL" to "$PREFIX/bin/bash",
        "TERM" to "xterm-256color",
        "LANG" to "en_US.UTF-8",
        "TERMUX__ROOTFS" to ROOTFS,
        "TERMUX__HOME" to HOME,
        "TERMUX__PREFIX" to PREFIX,
        "TERMUX__CACHE_DIR" to CACHE,
        "TERMUX__USER_ID" to uid.toString(),
        "TERMUX_APP__PACKAGE_NAME" to "com.termux",
        "TERMUX_APP__DATA_DIR" to DATA_DIR,
        "TERMUX_APP_PACKAGE_MANAGER" to "apt",
        // /tmp is not writable on Android; the PTY daemon keeps its sockets in TMPDIR.
        "OPENCODE_PTY_SOCKET_DIR" to "$PREFIX/tmp",
    ).apply {
        // termux-exec corrects shebangs and execution paths for the Termux
        // environment; Termux's login shell preloads it the same way.
        File("$PREFIX/lib/libtermux-exec-ld-preload.so").takeIf { it.isFile }
            ?.let { put("LD_PRELOAD", it.path) }
    }
}
