package de.aidadventure.server.worldstate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WorldStateResolverTest {
    @Test
    fun `accepts a moved location occurring in the game master response`() {
        assertEquals(
            WorldState("Market Square", "Find the lighthouse saboteur"),
            WorldStateAnalysis.apply(
                request("She arrives at the Market Square."),
                "{\"location\":\"Market Square\",\"narrativeFocus\":\"Find the lighthouse saboteur\"}",
            ),
        )
    }

    @Test
    fun `retains previous state when structured results are invalid`() {
        assertNull(
            WorldStateAnalysis.apply(
                request("Nothing changes.", narrativeFocus = "Existing situation"),
                "{\"location\":\"Wrong place\",\"narrativeFocus\":\"2\"}",
            ),
        )
    }

    @Test
    fun `accepts an empty focus to clear the previous focus`() {
        assertEquals(
            WorldState("Harbour"),
            WorldStateAnalysis.apply(
                request("She remains at the harbour.", narrativeFocus = "Existing situation"),
                "{\"location\":\"Harbour\",\"narrativeFocus\":\"\"}",
            ),
        )
    }

    @Test fun `accepts a catalogued lighthouse after an explicit arrival`() {
        assertEquals(WorldState("Leuchtturm"), WorldStateAnalysis.apply(
            request("Du verlässt den Kai und betrittst den Leuchtturm.", locations = mapOf("Kai" to "", "Leuchtturm" to "Turm an der Mole"), location = "Kai"),
            "{\"location\":\"Leuchtturm\",\"narrativeFocus\":\"\"}",
        ))
    }
    @Test fun `accepts a catalogued location when the response uses an indirect reference`() {
        assertEquals(WorldState("Leuchtturm"), WorldStateAnalysis.apply(
            request("Du verlässt den Kai und betrittst den Turm.", locations = mapOf("Kai" to "", "Leuchtturm" to "Turm an der Mole"), location = "Kai"),
            "{\"location\":\"Leuchtturm\",\"narrativeFocus\":\"\"}",
        ))
    }

    @Test fun `invalid fields fall back independently`() {
        assertEquals(WorldState("Harbour", "A new focus"), WorldStateAnalysis.apply(
            request("No move", narrativeFocus = "Old focus"),
            "{\"location\":\"Wrong place\",\"narrativeFocus\":\"A new focus\"}",
        ))
        assertEquals(WorldState("Market Square", "Old focus"), WorldStateAnalysis.apply(
            request("Moved", narrativeFocus = "Old focus"),
            "{\"location\":\"Market Square\"}",
        ))
    }

    @Test fun `without a catalog schema requests focus only`() {
        val schema = WorldStateAnalysis.combinedSchema(emptyMap())
        assertEquals(setOf("narrativeFocus"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(setOf("narrativeFocus"), schema.getValue("properties").jsonObject.keys)
        assertEquals(WorldState("Harbour", "A new focus"), WorldStateAnalysis.apply(
            request("No catalog", locations = emptyMap()),
            "{\"narrativeFocus\":\"A new focus\"}",
        ))
    }

    private fun request(response: String, location: String = "Harbour", narrativeFocus: String = "", locations: Map<String, String> = mapOf("Harbour" to "", "Market Square" to "")) =
        WorldStateResolutionRequest("id", "# S", WorldState(location, narrativeFocus), "walk", response, locations)
}
