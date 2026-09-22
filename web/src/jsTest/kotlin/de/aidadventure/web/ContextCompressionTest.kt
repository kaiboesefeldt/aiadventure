package de.aidadventure.web

import de.aidadventure.web.api.*
import de.aidadventure.web.model.*
import de.aidadventure.web.persistence.SessionRepository
import de.aidadventure.web.persistence.decodePersistedGameSession
import kotlin.test.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.serialization.json.Json

@OptIn(DelicateCoroutinesApi::class)
class ContextCompressionTest {
    private val checkpointJson = """{"recap":"The keeper owes us a favour.","coversThroughTurn":1,"coversThroughEntry":2,"sourceFingerprint":"source-digest","stateRevision":1,"modelId":"primary"}"""
    private val compressionJson = """{"epoch":1,"active":$checkpointJson,"pending":null}"""
    private val scenario = Scenario("scenario", "Harbor", "# Harbor", 1)
    private val original = GameSession("game", "scenario", "Harbor", messages = listOf(
        GameMessage("player", "look", 1, hidden = true),
        GameMessage("game-master", "A keeper greets you.", 2),
    ), createdAt = 1, updatedAt = 2)

    private fun encodedSessionWithCompression(): String {
        val encoded = Json.encodeToString(GameSession.serializer(), original)
        return encoded.dropLast(1) + ",\"contextCompression\":$compressionJson}"
    }

    @Test
    fun `checkpoint survives persistent session decoding with original raw history`() {
        val restored = decodePersistedGameSession(encodedSessionWithCompression())
        val encoded = Json.encodeToString(GameSession.serializer(), restored)
        assertTrue(encoded.contains("The keeper owes us a favour."))
        assertEquals(original.messages, restored.messages)
    }

    @Test
    fun `done event checkpoint is saved with completed turn and sent in all next requests`() = GlobalScope.promise<Unit> {
        val completion = checkNotNull(processTurnStreamChunk(
            SseFrameDecoder(),
            "event: done\ndata: {\"metrics\":{\"totalDurationMs\":null,\"promptTokens\":12,\"cacheTokens\":8},\"contextCompression\":$compressionJson}\n\n",
            onFragment = {}, onToken = {},
        ))
        val repository = CompressionSessions()
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
                onInputReady()
                onToken("The keeper points east.")
                return completion
            }
        }
        val result = TurnSubmission(repository, api, clock = { 3 }).submit(original, scenario, "ask for directions", {}, {}).session
        assertEquals(result, repository.saved.single())
        assertEquals(original.messages, result.messages.take(2))
        assertEquals(4, result.messages.size)
        val encoded = Json.encodeToString(GameSession.serializer(), result)
        assertTrue(encoded.contains("The keeper owes us a favour."), "Completion must persist the checkpoint")
        val restored = decodePersistedGameSession(encoded)
        val turn = TurnRequestMapper.map(restored, scenario, "go east", restored.id)
        val prefill = PrefillRequestMapper.map(restored, scenario, restored.id, 1, "go east")
        val warmup = WarmupRequestMapper.map(restored, scenario)
        listOf(encodeTurnRequest(turn), encodePrefillRequest(prefill), encodeWarmupRequest(warmup)).forEach {
            assertTrue(it.contains("The keeper owes us a favour."), "All prompt operations must send checkpoint")
        }
        assertEquals(result.messages.map { ChatEntry(it.role, it.content) }, turn.history)
        assertEquals(turn.history, prefill.history)
        assertEquals(turn.history, warmup.history)
    }

    @Test
    fun `legacy sessions and done events default to no compression`() {
        val restored = decodePersistedGameSession(Json.encodeToString(GameSession.serializer(), original))
        assertEquals(ContextCompression(), restored.contextCompression)
        val completion = processTurnStreamChunk(
            SseFrameDecoder(),
            "event: done\ndata: {\"metrics\":{\"totalDurationMs\":null,\"promptTokens\":3,\"cacheTokens\":null}}\n\n",
            onFragment = {}, onToken = {},
        )
        assertEquals(ContextCompression(), completion?.contextCompression)
    }

    @Test
    fun `pending checkpoint survives actual IndexedDB reopen without becoming active`() = GlobalScope.promise<Unit> {
        val checkpoint = Json.decodeFromString(ContextCheckpoint.serializer(), checkpointJson)
        val initial = original.copy(contextCompression = ContextCompression(pending = checkpoint))
        val name = "compression-test-${kotlin.js.Date.now()}"
        val database = de.aidadventure.web.persistence.AdventureDatabase.open(name)
        try { database.sessions.save(initial) } finally { database.close() }
        val reopened = de.aidadventure.web.persistence.AdventureDatabase.open(name)
        try {
            val saved = assertNotNull(reopened.sessions.find(initial.id))
            assertEquals(initial, saved)
            assertNull(saved.contextCompression.active)
            assertEquals(checkpoint, saved.contextCompression.pending)
            assertEquals(initial.messages, saved.messages)
        } finally {
            reopened.close()
            js("globalThis.indexedDB").deleteDatabase(name)
        }
    }

    @Test
    fun `failed turn preserves saved checkpoint and transcript`() = GlobalScope.promise<Unit> {
        val initial = decodePersistedGameSession(encodedSessionWithCompression())
        val repository = CompressionSessions()
        val updates = mutableListOf<GameSession>()
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
                onToken("partial")
                throw AdventureApiException("The adventure exceeds the model context.")
            }
        }
        assertFailsWith<AdventureApiException> {
            TurnSubmission(repository, api).submit(initial, scenario, "go", {}, updates::add)
        }
        assertTrue(repository.saved.isEmpty())
        assertTrue(Json.encodeToString(GameSession.serializer(), updates.last()).contains("The keeper owes us a favour."))
        assertEquals(initial.messages, updates.last().messages.dropLast(1))
    }
}

private class CompressionSessions : SessionRepository {
    val saved = mutableListOf<GameSession>()
    override suspend fun list() = saved.toList()
    override suspend fun save(session: GameSession) { saved += session }
    override suspend fun find(id: String) = saved.lastOrNull { it.id == id }
    override suspend fun delete(id: String) = Unit
}
