package de.aidadventure.web.api

import kotlin.test.Test
import kotlin.test.assertEquals

class SseFrameDecoderTest {
    @Test
    fun `decoder emits a token only after its blank-line terminator`() {
        val decoder = SseFrameDecoder()

        assertEquals(emptyList(), decoder.push("event: token\ndata: {\"text\":\"The "))
        assertEquals(listOf(SseFrame("token", "{\"text\":\"The door\"}")), decoder.push("door\"}\n\n"))
    }

    @Test
    fun `decoder accepts CRLF framed done events`() {
        val decoder = SseFrameDecoder()

        assertEquals(
            listOf(SseFrame("done", "{\"metrics\":{\"promptTokens\":3}}")),
            decoder.push("event: done\r\ndata: {\"metrics\":{\"promptTokens\":3}}\r\n\r\n"),
        )
    }
}
