package de.aidadventure.web.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuggestionStreamTest {
    @Test
    fun `suggestion frames deliver status and tokens incrementally`() {
        val decoder = SseFrameDecoder()
        val states = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        assertFalse(processSuggestionStreamChunk(decoder, "event: waiting\ndata: {}\n\nevent: token\ndata: {\"text\":\"Hi\"}\n", states::add, tokens::add))
        assertTrue(processSuggestionStreamChunk(decoder, "\nevent: generation_ready\ndata: {}\n\nevent: done\ndata: {}\n\n", states::add, tokens::add))
        assertEquals(listOf("waiting", "generation_ready"), states)
        assertEquals(listOf("Hi"), tokens)
    }
}
