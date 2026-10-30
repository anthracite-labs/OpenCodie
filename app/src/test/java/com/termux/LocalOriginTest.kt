package com.termux

import org.junit.Assert.*
import org.junit.Test

class LocalOriginTest {
    @Test fun onlyExactLoopbackOriginIsTrusted() {
        assertTrue(LocalOrigin.allows("http://127.0.0.1:4096/api/info?x=1"))
        for (url in listOf("file:///data/data/com.termux/files/home/secret", "content://provider/file",
            "https://127.0.0.1:4096/", "http://127.0.0.1:4097/", "http://localhost:4096/",
            "http://127.0.0.1:4096@evil.test/", "http://evil.test@127.0.0.1:4096/",
            "http://127.0.0.1:4096.evil.test/", "http://127.0.0.1:4096\\@evil.test/", "not a URL")) {
            assertFalse(url, LocalOrigin.allows(url))
        }
    }
}
