package de.aidadventure.web.api

import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import kotlin.test.Test
import kotlin.test.assertEquals

class TurnRequestMapperTest {
    @Test
    fun `request preserves session history before new action`() {
        val session = GameSession(
            "s",
            "scenario",
            "Run",
            "small",
            listOf(GameMessage("player", "look", 1)),
            1,
            1,
        )

        val request = TurnRequestMapper.map(session, Scenario("scenario", "x.md", "# X", 1), "open")

        assertEquals(listOf("look"), request.history.map { it.content })
        assertEquals("open", request.playerAction)
    }
}
