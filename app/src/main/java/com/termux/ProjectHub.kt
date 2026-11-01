package com.termux

import java.io.File

/**
 * Project Hub slice: pure project-listing and workspace-selection logic.
 *
 * Switching workspaces restarts the supervisor with a new working directory,
 * so names are strictly validated here — never let a display string become
 * a path traversal (`../x` must be rejected, not resolved).
 */
object ProjectHub {
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

    fun isValidProjectName(name: String): Boolean = NAME.matches(name)

    fun defaultWorkspace(home: File): File = File(home, "projects/workspace")

    /** Unknown or malicious overrides fall back to the default workspace. */
    fun workspaceDir(home: File, override: String?): File =
        if (override != null && isValidProjectName(override)) File(home, "projects/$override")
        else defaultWorkspace(home)

    /** First non-blank line of `git branch --show-current`, or null when detached/absent. */
    fun parseBranch(output: String): String? =
        output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

    /** Current workspace first, then alphabetical. */
    fun sortForDisplay(names: List<String>, current: String): List<String> =
        names.sortedWith(compareBy({ it != current }, { it }))
}
