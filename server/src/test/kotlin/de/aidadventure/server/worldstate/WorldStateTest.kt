package de.aidadventure.server.worldstate

import kotlin.test.Test
import kotlin.test.assertEquals

class WorldStateTest {
    @Test fun `snapshot contains only the current location`() {
        assertEquals("AUTHORITATIVE CURRENT LOCATION: Küche", WorldStateSnapshot.render(WorldState(location = "Küche")))
    }
}
