package de.aidadventure.server.ollama

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeepAliveDurationTest {
    @Test
    fun `parses hours minutes seconds and bare seconds`() {
        assertEquals(7_200, KeepAliveDuration.parseSeconds("2h"))
        assertEquals(600, KeepAliveDuration.parseSeconds("10m"))
        assertEquals(45, KeepAliveDuration.parseSeconds("45s"))
        assertEquals(30, KeepAliveDuration.parseSeconds("30"))
    }

    @Test
    fun `rejects invalid and nonpositive durations`() {
        listOf("", "five", "4d", "0", "-1s", "1.5m").forEach { value ->
            assertFailsWith<IllegalArgumentException>("Expected $value to be rejected") {
                KeepAliveDuration.parseSeconds(value)
            }
        }
    }
}
