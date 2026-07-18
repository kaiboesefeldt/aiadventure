package de.aidadventure.web.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurnStreamProcessorTest {
    @Test
    fun `delivers each decoded token before the completion frame arrives`() {
        val tokens = mutableListOf<String>()
        val fragments = mutableListOf<String>()
        val decoder = SseFrameDecoder()

        val beforeCompletion = processTurnStreamChunk(
            decoder = decoder,
            chunk = "event: token\ndata: {\"text\":\"The door opens.\"}\n\n",
            onFragment = fragments::add,
            onToken = tokens::add,
        )

        assertNull(beforeCompletion)
        assertEquals(emptyList(), fragments)
        assertEquals(listOf("The door opens."), tokens)

        val completion = processTurnStreamChunk(
            decoder = decoder,
            chunk = "event: done\ndata: {\"metrics\":{\"totalDurationMs\":null,\"promptTokens\":3,\"cacheTokens\":null}}\n\n",
            onFragment = fragments::add,
            onToken = tokens::add,
        )

        assertEquals(3, completion?.promptTokens)
    }

    @Test
    fun `delivers runtime fragment before following token`() {
        val fragments = mutableListOf<String>()
        val tokens = mutableListOf<String>()

        processTurnStreamChunk(
            decoder = SseFrameDecoder(),
            chunk = "event: fragment\ndata: {\"fragment\":\"Mira warns the player\"}\n\nevent: token\ndata: {\"text\":\"The beacon fades.\"}\n\n",
            onFragment = fragments::add,
            onToken = tokens::add,
        )

        assertEquals(listOf("Mira warns the player"), fragments)
        assertEquals(listOf("The beacon fades."), tokens)
    }
}
