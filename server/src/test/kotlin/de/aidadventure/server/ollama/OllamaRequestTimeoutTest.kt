package de.aidadventure.server.ollama

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaRequestTimeoutTest {
    @Test
    fun `parses a model warmup timeout`() {
        assertEquals(Duration.ofMinutes(10), OllamaRequestTimeout.parse("10m"))
    }
}
