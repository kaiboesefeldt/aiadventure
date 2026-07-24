package de.aidadventure.server.worldstate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldStateReducerTest {
    @Test fun `accepts a new entity and a fact at the current revision`() {
        val state = WorldState()
        val result = WorldStateReducer.apply(state, WorldStatePatch(0, listOf(
            WorldStateOperation.CreateEntity("sealed-letter", "item", "sealed letter", "Mira's letter"),
            WorldStateOperation.SetFact("holder", "sealed-letter", "player"),
        )))
        assertEquals(1, result?.revision)
        assertEquals("sealed letter", result?.entities?.get("sealed-letter")?.name)
        assertEquals("player", result?.facts?.get("holder:sealed-letter"))
    }

    @Test fun `rejects a patch for another revision or an unknown entity`() {
        assertNull(WorldStateReducer.apply(WorldState(), WorldStatePatch(1, emptyList())))
        assertNull(WorldStateReducer.apply(WorldState(), WorldStatePatch(0, listOf(WorldStateOperation.SetFact("holder", "missing", "player")))))
    }

    @Test fun `requires a canonical id and valid kind for an entity`() {
        val invalidId = WorldStatePatch(0, listOf(
            WorldStateOperation.CreateEntity("lowercase-kebab-id-1", "location", "Leuchtturm", "Dunkel am Hafen"),
        ))
        val invalidKind = WorldStatePatch(0, listOf(
            WorldStateOperation.CreateEntity("leuchtturm", "building", "Leuchtturm", "Dunkel am Hafen"),
        ))

        assertNull(WorldStateReducer.apply(WorldState(), invalidId))
        assertNull(WorldStateReducer.apply(WorldState(), invalidKind))
    }

    @Test fun `rejects another entity with the same canonical name`() {
        val state = WorldState(1, mapOf("legacy-lighthouse" to WorldEntity("location", "Leuchtturm", "Dunkel am Hafen")))
        val duplicate = WorldStatePatch(1, listOf(
            WorldStateOperation.CreateEntity("leuchtturm", "location", "Leuchtturm", "Dunkel am Hafen"),
        ))

        assertNull(WorldStateReducer.apply(state, duplicate))
    }

    @Test fun `snapshot retains entity identifiers and kinds`() {
        val snapshot = WorldStateReducer.snapshot(
            WorldState(1, mapOf("leuchtturm" to WorldEntity("location", "Leuchtturm", "Dunkel am Hafen"))),
        )

        assertTrue(snapshot.contains("id=leuchtturm kind=location name=Leuchtturm"))
    }
}
