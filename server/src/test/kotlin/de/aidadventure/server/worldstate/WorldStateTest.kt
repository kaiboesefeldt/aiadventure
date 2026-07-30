package de.aidadventure.server.worldstate

import kotlin.test.Test
import kotlin.test.assertEquals

class WorldStateTest {
    @Test fun `snapshot contains the current location and open story thread`() {
        assertEquals(
            "AUTHORITATIVE CURRENT LOCATION: Küche\nOPEN STORY THREAD: Finde den Saboteur",
            WorldStateSnapshot.render(WorldState(location = "Küche", activeThread = "Finde den Saboteur")),
        )
    }

    @Test fun `snapshot renders an open story thread without a location`() {
        assertEquals(
            "OPEN STORY THREAD: Finde den Saboteur",
            WorldStateSnapshot.render(WorldState(activeThread = "Finde den Saboteur")),
        )
    }
}
