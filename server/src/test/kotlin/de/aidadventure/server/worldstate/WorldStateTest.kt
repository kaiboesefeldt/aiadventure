package de.aidadventure.server.worldstate

import kotlin.test.Test
import kotlin.test.assertEquals

class WorldStateTest {
    @Test fun `snapshot contains the current location and unresolved story situation`() {
        assertEquals(
            "AUTHORITATIVE CURRENT LOCATION: Küche\nUNRESOLVED STORY SITUATION: Finde den Saboteur",
            WorldStateSnapshot.render(WorldState(location = "Küche", unresolvedSituation = "Finde den Saboteur")),
        )
    }

    @Test fun `snapshot renders an unresolved story situation without a location`() {
        assertEquals(
            "UNRESOLVED STORY SITUATION: Finde den Saboteur",
            WorldStateSnapshot.render(WorldState(unresolvedSituation = "Finde den Saboteur")),
        )
    }
}
