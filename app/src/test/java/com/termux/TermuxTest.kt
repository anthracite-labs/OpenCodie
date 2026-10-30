package com.termux

import org.junit.Assert.*
import org.junit.Test

class TermuxTest {
    @Test fun environmentsAreIsolatedAndUseAndroidProfileId() {
        val first = Termux.environment(210123)
        assertEquals("2", first["TERMUX__USER_ID"])
        assertEquals(Termux.PREFIX + "/bin", first["PATH"])
        first["OPENCODE_PASSWORD"] = "test-only"
        val next = Termux.environment(10123)
        assertEquals("0", next["TERMUX__USER_ID"])
        assertFalse(next.containsKey("OPENCODE_PASSWORD"))
        assertEquals(Termux.PREFIX + "/tmp", next["TMPDIR"])
    }
}
