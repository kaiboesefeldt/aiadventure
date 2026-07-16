package de.aidadventure.server.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiModelsTest {
    @Test
    fun `turn JSON carries a live session and llama port`() {
        val request = Json.decodeFromString<TurnRequest>("""
            {"sessionId":"live-1","modelId":"small","llamaCppPort":8081,"scenarioMarkdown":"# S","history":[],"playerAction":"look"}
        """)

        assertEquals("live-1", request.sessionId)
        assertEquals(8081, request.llamaCppPort)
    }

    @Test
    fun `prefill request carries an increasing revision`() {
        assertEquals(7L, PrefillRequest("live-1", 7, "model", 8081, "# Scenario", emptyList(), "look").revision)
    }
}
