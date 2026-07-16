package de.aidadventure.web.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RequestBodyEncodingTest {
    @Test
    fun `turn requests use their concrete serializer`() {
        val encoded = encodeTurnRequest(
            TurnRequest("model", "# Scenario", emptyList(), "look", 8081, "session-1"),
        )

        assertEquals("session-1", Json.parseToJsonElement(encoded).jsonObject["sessionId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `prefill requests use their concrete serializer`() {
        val encoded = encodePrefillRequest(
            PrefillRequest("session-1", 3, "model", 8081, "# Scenario", emptyList(), "look"),
        )

        assertEquals("3", Json.parseToJsonElement(encoded).jsonObject["revision"]?.jsonPrimitive?.content)
    }
}
