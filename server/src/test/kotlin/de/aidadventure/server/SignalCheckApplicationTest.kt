package de.aidadventure.server

import de.aidadventure.server.llamacpp.*
import de.aidadventure.server.model.*
import de.aidadventure.server.prefill.*
import de.aidadventure.server.runtimefragments.RuntimeFragmentResolver
import de.aidadventure.server.worldstate.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.*
import kotlin.test.*

class SignalCheckApplicationTest {
    @Test fun `direct only text runs after initial turn without focus retrieval or classifiers and persists in SSE`() = testApplication {
        val warmups = mutableListOf<WarmupRequest>()
        val gateway = object : PrefillGateway {
            override fun acquireIdleSlot(port: Int, excludedSlots: Set<LlamaCppSlot>) = LlamaCppSlot(7)
            override fun releaseSlot(port: Int, slot: LlamaCppSlot) = Unit
            override suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot) = CacheMetrics(4, 1)
            override fun stream(request: TurnRequest, slot: LlamaCppSlot) = flowOf(TurnStreamEvent.Token("Started"), TurnStreamEvent.Done(TurnMetrics(1, 4, 1)))
            override suspend fun resolveWorldState(primaryRequest: TurnRequest, gameMasterResponse: String, request: WorldStateResolutionRequest, slot: LlamaCppSlot) = WorldState()
            override suspend fun classifyFragment(request: FragmentRelevanceRequest, slot: LlamaCppSlot): String = error("No direct classifier")
            override suspend fun checkSignal(request: WarmupRequest, definition: SignalCheckDefinition, slot: LlamaCppSlot, onCall: (StreamDiagnostic) -> Unit): String = error("No direct signal classifier")
            override suspend fun warmup(request: WarmupRequest, slot: LlamaCppSlot): CacheMetrics { warmups += request; return CacheMetrics(12, 2) }
        }
        val registry = ExpiringPrefillRegistry(gateway)
        val resolver = object : RuntimeFragmentResolver {
            override suspend fun prepare(definitions: List<RuntimeFragmentDefinition>) { assertTrue(definitions.isEmpty()) }
            override suspend fun resolve(history: List<ChatEntry>, definitions: List<RuntimeFragmentDefinition>): RuntimeFragmentDefinition? = error("No direct retrieval")
        }
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolver) }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        try {
            val direct = RuntimeFragmentDefinition("", "Deterministic guidance", direct = true)
            val response = jsonClient.post("/api/turn") {
                contentType(ContentType.Application.Json)
                setBody(TurnRequest("direct", scenarioMarkdown = "# Game", history = emptyList(), playerAction = "Start", runtimeFragments = listOf(direct)))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText().substringAfter("event: done"), "\"nextRuntimeContext\":\"Deterministic guidance\"")
            assertEquals("Deterministic guidance", warmups.last().history.last().content)
        } finally { registry.remove(CoordinatorKey("direct", "configured-llama-cpp", 8081)) }
    }

    @Test
    fun `independent checks follow accepted focus and rerank before final SSE flags`() = testApplication {
        val stages = mutableListOf<String>()
        val checks = mutableListOf<WarmupRequest>()
        val warmups = mutableListOf<WarmupRequest>()
        val gateway = object : PrefillGateway {
            override fun acquireIdleSlot(port: Int, excludedSlots: Set<LlamaCppSlot>) = LlamaCppSlot(7)
            override fun releaseSlot(port: Int, slot: LlamaCppSlot) = Unit
            override suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot) = CacheMetrics(4, 1)
            override fun stream(request: TurnRequest, slot: LlamaCppSlot) = flowOf(
                TurnStreamEvent.Token("You enter the lighthouse; the keeper greets you."), TurnStreamEvent.Done(TurnMetrics(1, 4, 1)))
            override suspend fun resolveWorldState(primaryRequest: TurnRequest, gameMasterResponse: String,
                request: WorldStateResolutionRequest, slot: LlamaCppSlot): WorldState {
                stages += "world_state"
                return WorldState("Lighthouse", "Talk to keeper")
            }
            override suspend fun classifyFragment(request: FragmentRelevanceRequest, slot: LlamaCppSlot): String {
                stages += "rerank"; return "Y"
            }
            override suspend fun checkSignal(request: WarmupRequest, definition: SignalCheckDefinition, slot: LlamaCppSlot,
                onCall: (StreamDiagnostic) -> Unit): String {
                stages += "check:${definition.name}"; checks += request
                assertEquals(LlamaCppSlot(7), slot)
                onCall(StreamDiagnostic("llm_call", "signal_check", fields = mapOf("prompt" to "complete prompt", "output" to "Y"),
                    metrics = DiagnosticMetrics(cacheTokens = 12, evaluatedTokens = 2)))
                return "Y"
            }
            override suspend fun warmup(request: WarmupRequest, slot: LlamaCppSlot): CacheMetrics {
                warmups += request
                stages += "warmup"; return CacheMetrics(12, 2)
            }
        }
        val registry = ExpiringPrefillRegistry(gateway)
        val resolver = object : RuntimeFragmentResolver {
            override suspend fun prepare(definitions: List<RuntimeFragmentDefinition>) = Unit
            override suspend fun resolve(history: List<ChatEntry>, definitions: List<RuntimeFragmentDefinition>): RuntimeFragmentDefinition {
                assertEquals(listOf("unrelated path"), definitions.map { it.matchText })
                assertTrue(definitions.none { it.direct })
                return definitions.single()
            }
        }
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolver) }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        try {
            val body = jsonClient.post("/api/turn") {
                contentType(ContentType.Application.Json)
                setBody(TurnRequest("live", scenarioMarkdown = "# Game", history = emptyList(), playerAction = "Enter",
                    flags = mapOf("visited" to false, "met" to false),
                    runtimeFragments = listOf(
                        RuntimeFragmentDefinition("", "Direct hint", listOf(FlagCondition("visited", false)), direct = true),
                        RuntimeFragmentDefinition("unrelated path", "Future guidance"),
                        RuntimeFragmentDefinition("blocked path", "Blocked", listOf(FlagCondition("visited", true)))),
                    signals = linkedMapOf(
                        "unchanged" to listOf(FlagAssignment("visited", false)),
                        "blocked" to listOf(FlagAssignment("met", true)),
                        "enter" to listOf(FlagAssignment("visited", true)),
                        "meet" to listOf(FlagAssignment("met", true))),
                    signalChecks = listOf(
                        SignalCheckDefinition("unchanged", "Already unchanged"),
                        SignalCheckDefinition("blocked", "Blocked", listOf(FlagCondition("visited", true))),
                        SignalCheckDefinition("enter", "Entered lighthouse", emittedTexts = listOf("First hint", "Second hint")), SignalCheckDefinition("meet", "Met keeper"))))
            }.bodyAsText()
            assertEquals(listOf("world_state", "warmup", "rerank", "check:enter", "check:meet", "warmup"), stages)
            assertEquals("Direct hint\n\nFirst hint\n\nSecond hint\n\nFuture guidance", warmups.last().history.last().content)
            assertContains(body.substringAfter("event: done"), "nextRuntimeContext")
            assertEquals(2, checks.size)
            checks.forEach { check ->
                assertEquals(WorldState("Lighthouse", "Talk to keeper"), check.worldState)
                assertFalse(check.history.any { it.content == "Future guidance" })
                assertContains(check.history.last().content, "enter the lighthouse")
            }
            assertContains(body.substringAfter("event: done"), "\"flags\":{\"visited\":true,\"met\":true}")
            assertTrue(body.indexOf("event: input_ready") < body.indexOf("\"kind\":\"signal_check\""))
            assertTrue(body.indexOf("\"kind\":\"signal_check\"") < body.indexOf("event: done"))
            assertContains(body, "\"cacheTokens\":12")
        } finally { registry.remove(CoordinatorKey("live", "configured-llama-cpp", 8081)) }
    }
}
