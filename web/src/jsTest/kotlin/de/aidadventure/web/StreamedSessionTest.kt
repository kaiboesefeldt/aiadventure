package de.aidadventure.web

import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.model.GameSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class StreamedSessionTest {
    @Test
    fun `streamed session takes precedence while a turn is in progress`() {
        val persisted = session(messages = emptyList())
        val streamed = session(messages = listOf(GameMessage("game-master", "The door opens.", 2)))

        assertEquals(streamed, sessionForDisplay(persisted, streamed))
    }

    @Test
    fun `persisted session is used when there is no streamed update`() {
        val persisted = session(messages = emptyList())

        assertEquals(persisted, sessionForDisplay(persisted, null))
    }

    @Test
    fun `runtime fragment is excluded from transcript`() {
        assertFalse(isVisibleTranscriptMessage(GameMessage("runtime-fragment", "secret", 1)))
    }

    @Test
    fun `runtime fragment belongs only to its following completed game master message`() {
        val messages = listOf(
            GameMessage("runtime-fragment", "hidden context", 1),
            GameMessage("player", "look", 2),
            GameMessage("game-master", "The tower answers.", 3),
        )

        assertEquals("hidden context", runtimeFragmentForGameMaster(messages, 2))
        assertNull(runtimeFragmentForGameMaster(messages, 1))
    }

    @Test
    fun `unrelated and partial messages have no runtime fragment`() {
        assertNull(runtimeFragmentForGameMaster(listOf(GameMessage("game-master", "first", 1)), 0))
        assertNull(runtimeFragmentForGameMaster(listOf(
            GameMessage("runtime-fragment", "hidden context", 1),
            GameMessage("game-master", "partial", 2),
        ), 1))
    }

    private fun session(messages: List<GameMessage>) = GameSession(
        id = "session",
        scenarioId = "scenario",
        title = "Run",
        modelId = "model",
        messages = messages,
        createdAt = 1,
        updatedAt = 1,
        llamaCppPort = 8081,
    )
}
