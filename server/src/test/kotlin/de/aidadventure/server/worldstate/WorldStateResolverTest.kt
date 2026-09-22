package de.aidadventure.server.worldstate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorldStateResolverTest {
    @Test
    fun `accepts a moved location occurring in the game master response`() {
        assertEquals(
            WorldState("Market Square", "Find the lighthouse saboteur"),
            WorldStateAnalysis.apply(
                request("She arrives at the Market Square."),
                "{\"movement\":\"move\"}",
                "{\"location\":\"Market Square\"}",
                "{\"narrativeFocus\":\"Find the lighthouse saboteur\"}",
            ),
        )
    }

    @Test
    fun `retains previous state when structured results are invalid`() {
        assertNull(
            WorldStateAnalysis.apply(
                request("Nothing changes.", narrativeFocus = "Existing situation"),
                "{\"movement\":\"stay\"}",
                "{\"location\":\"Wrong place\"}",
                "{\"narrativeFocus\":\"2\"}",
            ),
        )
    }

    @Test
    fun `accepts an empty focus to clear the previous focus`() {
        assertEquals(
            WorldState("Harbour"),
            WorldStateAnalysis.apply(
                request("She remains at the harbour.", narrativeFocus = "Existing situation"),
                "{\"movement\":\"stay\"}",
                null,
                "{\"narrativeFocus\":\"\"}",
            ),
        )
    }

    private fun request(response: String, location: String = "Harbour", narrativeFocus: String = "") =
        WorldStateResolutionRequest("id", "# S", WorldState(location, narrativeFocus), "walk", response)
}
