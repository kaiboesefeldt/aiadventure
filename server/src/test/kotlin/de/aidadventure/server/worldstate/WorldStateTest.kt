package de.aidadventure.server.worldstate

import kotlin.test.Test
import kotlin.test.assertEquals

class WorldStateTest {
    @Test fun `snapshot contains the current location and narrative focus`() {
        assertEquals(
            "AUTHORITATIVE CURRENT LOCATION: Küche\nNARRATIVE FOCUS: Der verrostete Mechanismus",
            WorldStateSnapshot.render(WorldState(location = "Küche", narrativeFocus = "Der verrostete Mechanismus")),
        )
    }

    @Test fun `snapshot renders a narrative focus without a location`() {
        assertEquals(
            "NARRATIVE FOCUS: Der verrostete Mechanismus",
            WorldStateSnapshot.render(WorldState(narrativeFocus = "Der verrostete Mechanismus")),
        )
    }
}
