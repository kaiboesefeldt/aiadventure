package de.aidadventure.server.llamacpp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LlamaCppPortTest {
    @Test
    fun `llama cpp port defaults to 8081 and rejects out of range values`() {
        assertEquals(8081, LlamaCppPort.DEFAULT)
        assertEquals(1, LlamaCppPort.parse("1"))
        assertEquals(65535, LlamaCppPort.parse("65535"))
        assertFailsWith<IllegalArgumentException> { LlamaCppPort.parse("0") }
        assertFailsWith<IllegalArgumentException> { LlamaCppPort.parse("65536") }
        assertFailsWith<IllegalArgumentException> { LlamaCppPort.parse("abc") }
    }
}
