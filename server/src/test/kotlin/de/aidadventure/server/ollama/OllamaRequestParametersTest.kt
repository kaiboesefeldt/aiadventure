package de.aidadventure.server.ollama

import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaRequestParametersTest {
    @Test
    fun `default parameters disable Ollama thinking`() {
        val parameters = defaultRequestParameters(600)

        assertEquals(false, parameters.think())
        assertEquals(600, parameters.keepAlive())
    }
}
