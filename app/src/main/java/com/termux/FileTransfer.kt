package com.termux

/**
 * File-Transfer Polish slice: pure formatting logic for the save flow.
 *
 * Compact-label rule (ui-ux-pro-max `Compact Label Overflow`): a filename
 * chip stays whole when practical; unavoidable truncation keeps the extension
 * visible and the full value is always one tap away (dialog title).
 */
object FileTransfer {
    /** Human progress line: percent when the total is known, KB otherwise. */
    fun progressText(bytesCopied: Long, totalBytes: Long): String {
        val kb = bytesCopied / 1024
        if (totalBytes <= 0) return "Saving… ${kb} KB"
        val pct = (bytesCopied * 100 / totalBytes).coerceIn(0, 100)
        return "Saving… $pct%"
    }

    /**
     * Middle-truncate long names, keeping head + extension. Short names
     * pass through untouched (no wrapping chip).
     */
    fun fileLabel(name: String, maxLen: Int = 32): String {
        if (name.length <= maxLen) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot) else ""
        val keep = maxLen - ext.length - 1
        if (keep <= 0) return name.take(maxLen - 1) + "…"
        return name.take(keep / 2) + "…" + name.takeLast(keep - keep / 2) + ext
    }
}
