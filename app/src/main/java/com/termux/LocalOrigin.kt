package com.termux

import java.net.URI

internal object LocalOrigin {
    const val URL = "http://127.0.0.1:4096"

    fun allows(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "http" && uri.rawAuthority == "127.0.0.1:4096" && uri.rawUserInfo == null
    } catch (_: Exception) { false }
}
