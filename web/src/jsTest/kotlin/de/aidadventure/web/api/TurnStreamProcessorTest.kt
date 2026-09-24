package de.aidadventure.web.api

import de.aidadventure.web.model.StreamDiagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurnStreamProcessorTest {
    @Test
    fun `signals input readiness before the delayed completion frame`() {
        var readySignals = 0

        val completion = processTurnStreamChunk(
            decoder = SseFrameDecoder(),
            chunk = "event: input_ready\ndata: {}\n\n",
            onInputReady = { readySignals++ },
            onFragment = {},
            onToken = {},
        )

        assertNull(completion)
        assertEquals(1, readySignals)
    }

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

        assertEquals(3, completion?.metrics?.promptTokens)
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

    @Test
    fun `delivers diagnostics in wire order with fields roles and metrics`() {
        val seen = mutableListOf<String>()
        val diagnostics = mutableListOf<StreamDiagnostic>()
        val decoder = SseFrameDecoder()
        val beforeDone = processTurnStreamChunk(
            decoder,
            "event: diagnostic\ndata: {\"kind\":\"turn_status\",\"stage\":\"started\",\"transcriptRole\":\"player\"}\n\n" +
                "event: token\ndata: {\"text\":\"answer\"}\n\n" +
                "event: diagnostic\ndata: {\"kind\":\"background_rerank\",\"stage\":\"prior_completed\",\"fields\":{\"source\":\"prior_turn\"},\"metrics\":{\"cacheTokens\":8}}\n\n",
            onFragment = {}, onToken = { seen += "token:$it" },
            onDiagnostic = { diagnostics += it; seen += "diagnostic:${it.stage}" },
        )
        assertNull(beforeDone)
        assertEquals(listOf("diagnostic:started", "token:answer", "diagnostic:prior_completed"), seen)
        assertEquals("player", diagnostics[0].transcriptRole)
        assertEquals("prior_turn", diagnostics[1].fields["source"])
        assertEquals(8, diagnostics[1].metrics?.cacheTokens)
    }

    @Test
    fun `old stream without diagnostics completes with no diagnostic callbacks`() {
        val diagnostics = mutableListOf<StreamDiagnostic>()
        val completion = processTurnStreamChunk(
            SseFrameDecoder(),
            "event: token\ndata: {\"text\":\"answer\"}\n\nevent: done\ndata: {\"metrics\":{\"totalDurationMs\":null,\"promptTokens\":3,\"cacheTokens\":null}}\n\n",
            onFragment = {}, onToken = {}, onDiagnostic = diagnostics::add,
        )
        assertEquals(3, completion?.metrics?.promptTokens)
        assertEquals(emptyList(), diagnostics)
    }
}
