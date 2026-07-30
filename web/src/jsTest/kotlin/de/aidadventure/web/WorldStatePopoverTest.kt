package de.aidadventure.web

import de.aidadventure.web.model.WorldState
import de.aidadventure.web.persistence.decodePersistedGameSession
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldStatePopoverTest {
    @Test fun `renders the persisted location and unresolved situation`() =
        assertEquals("{\"location\":\"Küche\",\"unresolvedSituation\":\"Finde den Saboteur\"}", worldStateJson(WorldState("Küche", "Finde den Saboteur")))

    @Test fun `persists an unresolved situation without a location`() =
        assertEquals("{\"unresolvedSituation\":\"Finde den Saboteur\"}", worldStateJson(WorldState(unresolvedSituation = "Finde den Saboteur")))

    @Test fun `reads an old active thread as the unresolved situation`() =
        assertEquals(WorldState(unresolvedSituation = "Finde den Saboteur"), decodePersistedGameSession("""{"id":"session-1","scenarioId":"harbor","title":"Harbor","messages":[],"createdAt":1,"updatedAt":1,"worldState":{"activeThread":"Finde den Saboteur"}}""").worldState)
}
