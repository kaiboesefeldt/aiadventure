package de.aidadventure.web.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurnStreamProcessorTest {
    @Test
    fun `delivers each decoded token before the completion frame arrives`() {
        val tokens = mutableListOf<String>()
        val decoder = SseFrameDecoder()

        val beforeCompletion = processTurnStreamChunk(
            decoder = decoder,
            chunk = "event: token\ndata: {\"text\":\"The door opens.\"}\n\n",
            onToken = tokens::add,
        )

        assertNull(beforeCompletion)
        assertEquals(listOf("The door opens."), tokens)

        val completion = processTurnStreamChunk(
            decoder = decoder,
            chunk = "event: done\ndata: {\"metrics\":{\"totalDurationMs\":null,\"promptTokens\":3,\"cacheTokens\":null}}\n\n",
            onToken = tokens::add,
        )

        assertEquals(3, completion?.promptTokens)
    }
}
