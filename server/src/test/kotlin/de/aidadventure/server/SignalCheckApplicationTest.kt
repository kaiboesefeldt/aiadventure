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
    @Test
    fun `independent checks follow accepted focus and rerank before final SSE flags`() = testApplication {
        val stages = mutableListOf<String>()
        val checks = mutableListOf<WarmupRequest>()
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
                stages += "warmup"; return CacheMetrics(12, 2)
            }
        }
        val registry = ExpiringPrefillRegistry(gateway)
        val resolver = object : RuntimeFragmentResolver {
            override suspend fun prepare(definitions: List<RuntimeFragmentDefinition>) = Unit
            override suspend fun resolve(history: List<ChatEntry>, definitions: List<RuntimeFragmentDefinition>) =
                RuntimeFragmentDefinition("unrelated path", "Future guidance")
        }
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolver) }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        try {
            val body = jsonClient.post("/api/turn") {
                contentType(ContentType.Application.Json)
                setBody(TurnRequest("live", scenarioMarkdown = "# Game", history = emptyList(), playerAction = "Enter",
                    flags = mapOf("visited" to false, "met" to false),
                    signals = linkedMapOf(
                        "unchanged" to listOf(FlagAssignment("visited", false)),
                        "blocked" to listOf(FlagAssignment("met", true)),
                        "enter" to listOf(FlagAssignment("visited", true)),
                        "meet" to listOf(FlagAssignment("met", true))),
                    signalChecks = listOf(
                        SignalCheckDefinition("unchanged", "Already unchanged"),
                        SignalCheckDefinition("blocked", "Blocked", listOf(FlagCondition("visited", true))),
                        SignalCheckDefinition("enter", "Entered lighthouse"), SignalCheckDefinition("meet", "Met keeper"))))
            }.bodyAsText()
            assertEquals(listOf("world_state", "warmup", "rerank", "check:enter", "check:meet", "warmup"), stages)
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
