package de.aidadventure.server.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiModelsTest {
    @Test
    fun `old turn JSON defaults to Ollama`() {
        val request = Json.decodeFromString<TurnRequest>("""
            {"modelId":"small","scenarioMarkdown":"# S","history":[],"playerAction":"look"}
        """)

        assertEquals(ModelProvider.OLLAMA, request.provider)
        assertNull(request.llamaCppPort)
    }
}
