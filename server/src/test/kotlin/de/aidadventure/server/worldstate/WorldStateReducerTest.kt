package de.aidadventure.server.worldstate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldStateReducerTest {
    @Test fun `accepts a named entity and fact without an internal id`() {
        val result = WorldStateReducer.applyProposal(WorldState(), WorldStateProposal(
            baseRevision = 0,
            entities = listOf(WorldStateEntityProposal("Leuchtturm", "location", "Seit drei Nächten dunkel.")),
            facts = listOf(WorldStateFactProposal("Unruhe", "Leuchtturm", "true")),
        ))

        assertEquals(WorldEntity("location", "Leuchtturm", "Seit drei Nächten dunkel."), result?.entities?.get("leuchtturm"))
        assertEquals("true", result?.facts?.get("Unruhe:leuchtturm"))
    }

    @Test fun `rejects a proposal for another revision or an unknown entity`() {
        assertNull(WorldStateReducer.applyProposal(WorldState(), WorldStateProposal(1)))
        assertNull(WorldStateReducer.applyProposal(WorldState(), WorldStateProposal(
            0,
            facts = listOf(WorldStateFactProposal("holder", "Missing", "player")),
        )))
    }

    @Test fun `requires a name that can become an id and a valid kind`() {
        val invalidName = WorldStateProposal(0, entities = listOf(WorldStateEntityProposal("---", "location", "Dunkel am Hafen")))
        val invalidKind = WorldStateProposal(0, entities = listOf(WorldStateEntityProposal("Leuchtturm", "building", "Dunkel am Hafen")))

        assertNull(WorldStateReducer.applyProposal(WorldState(), invalidName))
        assertNull(WorldStateReducer.applyProposal(WorldState(), invalidKind))
    }

    @Test fun `snapshot retains entity identifiers and kinds`() {
        val snapshot = WorldStateReducer.snapshot(
            WorldState(1, mapOf("leuchtturm" to WorldEntity("location", "Leuchtturm", "Dunkel am Hafen"))),
        )

        assertTrue(snapshot.contains("id=leuchtturm kind=location name=Leuchtturm"))
    }
}
