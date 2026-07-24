package de.aidadventure.server.worldstate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorldStateReducerTest {
    @Test fun `accepts a new entity and a fact at the current revision`() {
        val state = WorldState()
        val result = WorldStateReducer.apply(state, WorldStatePatch(0, listOf(
            WorldStateOperation.CreateEntity("letter", "item", "sealed letter", "Mira's letter"),
            WorldStateOperation.SetFact("holder", "letter", "player"),
        )))
        assertEquals(1, result?.revision)
        assertEquals("sealed letter", result?.entities?.get("letter")?.name)
        assertEquals("player", result?.facts?.get("holder:letter"))
    }

    @Test fun `rejects a patch for another revision or an unknown entity`() {
        assertNull(WorldStateReducer.apply(WorldState(), WorldStatePatch(1, emptyList())))
        assertNull(WorldStateReducer.apply(WorldState(), WorldStatePatch(0, listOf(WorldStateOperation.SetFact("holder", "missing", "player")))))
    }
}
