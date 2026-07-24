package de.aidadventure.web

import de.aidadventure.web.model.WorldEntity
import de.aidadventure.web.model.WorldState
import kotlin.test.Test
import kotlin.test.assertContains

class WorldStatePopoverTest {
    @Test fun `renders the current state as JSON`() {
        val json = worldStateJson(WorldState(2, mapOf("mira" to WorldEntity("npc", "Mira", "wary"))))
        assertContains(json, "\"revision\":2")
        assertContains(json, "\"mira\"")
        assertContains(json, "\"name\":\"Mira\"")
    }
}
