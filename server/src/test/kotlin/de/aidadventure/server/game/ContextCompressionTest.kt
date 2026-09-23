package de.aidadventure.server.game

import de.aidadventure.server.model.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class ContextCompressionTest {
    private val raw = listOf(ChatEntry("player", "look"), ChatEntry("game-master", "A long adventure. ".repeat(80)))
    @Test fun `validated checkpoint replaces only covered source and retains immutable prefix`() {
        val request = WarmupRequest("session", "model", 8081, "scenario", raw)
        val checkpoint = ContextEpochs.checkpoint(request, "A traveller arrived.")
        val compressed = request.copy(contextCompression = ContextCompression(1, checkpoint))
        val warm = PromptFactory().buildWarmup(compressed)
        val turn = PromptFactory().build(TurnRequest("session", "model", 8081, "scenario", raw, "continue", contextCompression = compressed.contextCompression))
        assertEquals(warm, turn.dropLast(1))
        assertEquals(2, warm.size)
        assertEquals(raw, request.history)
        assertFailsWith<IllegalArgumentException> { PromptFactory().buildWarmup(compressed.copy(history = raw.map { it.copy(content = "changed") })) }
    }
    @Test fun `pending checkpoint activates after three completed turns and pressure activates sooner`() {
        val request = WarmupRequest("session", "model", 8081, "scenario", raw)
        val pending = ContextCompression(pending = ContextEpochs.checkpoint(request, "Arrival."))
        assertEquals(pending, ContextEpochs.select(request.copy(contextCompression = pending)))
        assertEquals(1L, ContextEpochs.select(request.copy(contextCompression = pending), pressure = true).epoch)
        val tail = List(3) { listOf(ChatEntry("player", "go"), ChatEntry("game-master", "Moved.")) }.flatten()
        assertEquals(1L, ContextEpochs.select(request.copy(history = raw + tail, contextCompression = pending)).epoch)
    }
    @Test fun `empty oversized and mismatched checkpoints cannot hide source`() {
        val request = WarmupRequest("session", "model", 8081, "scenario", raw)
        assertFailsWith<IllegalArgumentException> { ContextEpochs.checkpoint(request, " ") }
        assertFailsWith<IllegalArgumentException> { ContextEpochs.checkpoint(request, "x".repeat(16001)) }
        val checkpoint = ContextEpochs.checkpoint(request, "Arrival.")
        assertFailsWith<IllegalArgumentException> { ContextEpochs.select(request.copy(sessionId = "another", contextCompression = ContextCompression(1, checkpoint))) }
    }
    @Test fun `old saved requests retain empty epoch`() {
        val request = Json.decodeFromString<WarmupRequest>("""{"sessionId":"s","scenarioMarkdown":"s","history":[]}""")
        assertEquals(ContextCompression(), request.contextCompression)
    }
}
