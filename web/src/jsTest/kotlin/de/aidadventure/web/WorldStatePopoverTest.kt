package de.aidadventure.web

import de.aidadventure.web.model.WorldState
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldStatePopoverTest {
    @Test fun `renders the persisted location and active thread`() =
        assertEquals("{\"location\":\"Küche\",\"activeThread\":\"Finde den Saboteur\"}", worldStateJson(WorldState("Küche", "Finde den Saboteur")))

    @Test fun `persists an active thread without a location`() =
        assertEquals("{\"activeThread\":\"Finde den Saboteur\"}", worldStateJson(WorldState(activeThread = "Finde den Saboteur")))
}
