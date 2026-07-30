package de.aidadventure.web

import de.aidadventure.web.model.WorldState
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldStatePopoverTest {
    @Test fun `renders the persisted location`() = assertEquals("{\"location\":\"Küche\"}", worldStateJson(WorldState("Küche")))
}
