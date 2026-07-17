package de.aidadventure.server.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiModelsTest {
    @Test
    fun `turn JSON carries a live session without browser connection settings`() {
        val request = Json.decodeFromString<TurnRequest>("""
            {"sessionId":"live-1","scenarioMarkdown":"# S","history":[],"playerAction":"look"}
        """)

        assertEquals("live-1", request.sessionId)
        assertEquals("", request.modelId)
        assertEquals(8081, request.llamaCppPort)
    }

    @Test
    fun `prefill request carries an increasing revision`() {
        assertEquals(7L, PrefillRequest("live-1", 7, scenarioMarkdown = "# Scenario", history = emptyList(), playerAction = "look").revision)
    }
}
