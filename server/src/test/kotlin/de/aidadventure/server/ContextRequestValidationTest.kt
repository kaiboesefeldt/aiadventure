package de.aidadventure.server

import de.aidadventure.server.game.ContextEpochs
import de.aidadventure.server.model.*
import kotlin.test.*

class ContextRequestValidationTest {
    private val raw = listOf(ChatEntry("player", "look"), ChatEntry("game-master", "A keeper greets you."))
    private val warmup = WarmupRequest("session", "configured-llama-cpp", 8081, "# Harbor", raw)
    private val context = ContextCompression(active = ContextEpochs.checkpoint(warmup, "Met the keeper."), epoch = 1)

    @Test
    fun `all endpoints reject a checkpoint for different raw source`() {
        val changed = raw.map { it.copy(content = "edited") }
        assertNotNull(RequestValidator.validate(warmup.copy(history = changed, contextCompression = context)))
        assertNotNull(RequestValidator.validate(TurnRequest("session", "configured-llama-cpp", 8081, "# Harbor", changed, "go", contextCompression = context)))
        assertNotNull(RequestValidator.validate(PrefillRequest("session", 1, "configured-llama-cpp", 8081, "# Harbor", changed, "go", contextCompression = context)))
    }

    @Test
    fun `long original history is accepted with a valid checkpoint`() {
        val history = List(200) { raw }.flatten()
        assertNull(RequestValidator.validate(warmup.copy(history = history, contextCompression = context)))
        assertNull(RequestValidator.validate(TurnRequest("session", "configured-llama-cpp", 8081, "# Harbor", history, "go", contextCompression = context)))
        assertNull(RequestValidator.validate(PrefillRequest("session", 1, "configured-llama-cpp", 8081, "# Harbor", history, "go", contextCompression = context)))
    }

    @Test
    fun `raw archive remains bounded independently of compression`() {
        assertNotNull(RequestValidator.validate(warmup.copy(history = List(100_001) { raw.first() })))
        assertNotNull(RequestValidator.validate(warmup.copy(history = List(1_001) { ChatEntry("player", "x".repeat(20_000)) })))
    }
}
