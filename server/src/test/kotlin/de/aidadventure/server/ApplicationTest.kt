package de.aidadventure.server

import com.sun.net.httpserver.HttpServer
import de.aidadventure.server.llamacpp.LlamaCppGateway
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.FlagAssignment
import de.aidadventure.server.model.FlagCondition
import de.aidadventure.server.model.RuntimeFragmentDefinition
import de.aidadventure.server.model.PrefillDisposition
import de.aidadventure.server.model.PrefillRequest
import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import de.aidadventure.server.model.StreamDiagnostic
import de.aidadventure.server.model.WarmupRequest
import de.aidadventure.server.prefill.CoordinatorKey
import de.aidadventure.server.prefill.PrefillCoordinator
import de.aidadventure.server.prefill.PrefillRegistry
import de.aidadventure.server.runtimefragments.RuntimeFragmentResolver
import de.aidadventure.server.worldstate.WorldState
import de.aidadventure.server.worldstate.WorldStateResolutionRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import java.io.IOException
import io.ktor.serialization.kotlinx.json.json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun `location entry detection includes the initial location and only later changes`() {
        assertEquals(listOf("Kai"), enteredLocationsForTurn(emptyList(), "Kai", "Kai"))
        val history = listOf(ChatEntry("player", "Explore"))
        assertEquals(listOf("Leuchtturm"), enteredLocationsForTurn(history, "Kai", "Leuchtturm"))
        assertEquals(emptyList(), enteredLocationsForTurn(history, "Kai", "Kai"))
        assertEquals(emptyList(), enteredLocationsForTurn(emptyList(), "", ""))
    }

    @Test
    fun `selected guidance never commits an event flag`() = testApplication {
        val registry = FakeRegistry()
        registry.coordinator.turnEvents = flowOf(
            TurnStreamEvent.Fragment("Enter the lighthouse if possible.", listOf("enter")),
            TurnStreamEvent.Token("The locked door remains shut."),
            TurnStreamEvent.Done(TurnMetrics(42, 24, 18)),
        )
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolving(null)) }
        configureJsonClient()
        val body = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest().copy(flags = mapOf("visited" to false),
                signals = mapOf("enter" to listOf(FlagAssignment("visited", true)))))
        }.bodyAsText()
        val done = body.split("\n\n").single { it.startsWith("event: done\n") }
        assertContains(done, "\"visited\":false")
    }

    @Test
    fun `latest turn background outcome is available through follow up SSE without another turn`() = testApplication {
        val registry = FakeRegistry()
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolving(null)) }
        configureJsonClient()

        val turnBody = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest(worldState = WorldState(location = "Harbour")))
        }.bodyAsText()
        val scheduled = turnBody.split("\n\n").first { it.contains("\"kind\":\"background_rerank\"") }
        val operationId = Json.parseToJsonElement(scheduled.substringAfter("data: ")).jsonObject
            .getValue("fields").jsonObject.getValue("operationId").jsonPrimitive.content
        runBlocking { withTimeout(1_000) { registry.coordinator.rerankCompleted.await() } }

        val followUp = client.get("/api/turn/background?sessionId=live-1&operationId=$operationId")
        val followUpBody = followUp.bodyAsText()
        assertEquals(HttpStatusCode.OK, followUp.status)
        assertContains(followUpBody, "event: diagnostic")
        assertContains(followUpBody, "\"operationId\":\"$operationId\"")
        assertContains(followUpBody, "\"stage\":\"prior_completed\"")
        assertFalse(followUpBody.contains("look"))
        assertFalse(followUpBody.contains("The door opens."))
        val retry = client.get("/api/turn/background?sessionId=live-1&operationId=$operationId")
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(followUpBody, retry.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest,
            client.get("/api/turn/background?sessionId=live-1&operationId=invalid").status)
        assertEquals(1, registry.coordinator.turnRequests.size)
    }

    @Test
    fun `cancelled turn keeps serialized diagnostics free of transcript text`() = testApplication {
        val registry = FakeRegistry()
        registry.coordinator.turnEvents = flow {
            emit(TurnStreamEvent.Diagnostic(StreamDiagnostic("generation", "started", transcriptRole = "game-master")))
            emit(TurnStreamEvent.Token("SECRET_GAME_MASTER_OUTPUT"))
            throw CancellationException("SECRET_PLAYER_ACTION")
        }
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolving(null)) }
        configureJsonClient()

        val lines = mutableListOf<String>()
        val failure = runBlocking {
            withTimeout(2_000) {
                runCatching { client.preparePost("/api/turn") {
                    contentType(ContentType.Application.Json)
                    setBody(turnRequest().copy(playerAction = "SECRET_PLAYER_ACTION"))
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    while (true) {
                        val line = channel.readUTF8Line() ?: break
                        lines += line
                    }
                } }.exceptionOrNull()
            }
        }
        val body = lines.joinToString("\n")
        val diagnostics = body.split("event: diagnostic\n").drop(1).map { it.substringBefore("\n\n") }
        assertContains(body, "\"kind\":\"turn_status\",\"stage\":\"cancelled\"")
        assertFalse(diagnostics.joinToString().contains("SECRET_PLAYER_ACTION"))
        assertFalse(diagnostics.joinToString().contains("SECRET_GAME_MASTER_OUTPUT"))
        assertFalse(body.contains("event: done"))
        assertFalse(body.contains("event: error"))
        assertTrue(failure == null || failure is CancellationException)
    }

    @Test
    fun `failed turn emits a safe terminal diagnostic before error`() = testApplication {
        val registry = FakeRegistry()
        registry.coordinator.beginTurnFailure = IOException("SECRET_PLAYER_ACTION")
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolving(null)) }
        configureJsonClient()

        val body = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest().copy(playerAction = "SECRET_PLAYER_ACTION"))
        }.bodyAsText()

        val diagnosticFrames = body.split("\n\n").filter { it.startsWith("event: diagnostic\n") }
        val terminal = Json.parseToJsonElement(diagnosticFrames.last().substringAfter("data: ")).jsonObject
        assertEquals("turn_status", terminal.getValue("kind").jsonPrimitive.content)
        assertEquals("failed", terminal.getValue("stage").jsonPrimitive.content)
        assertFalse(diagnosticFrames.joinToString().contains("SECRET_PLAYER_ACTION"))
        assertTrue(body.indexOf("\"kind\":\"turn_status\"") < body.indexOf("event: error"))
    }

    @Test
    fun `turn diagnostics describe stages in order without copying transcript text`() = testApplication {
        val registry = FakeRegistry()
        registry.coordinator.turnEvents = flowOf(
            TurnStreamEvent.Fragment("A hidden route opens.", listOf("found-map")),
            TurnStreamEvent.Token("SECRET_GAME_MASTER_OUTPUT"),
            TurnStreamEvent.Done(TurnMetrics(42, 24, 18)),
        )
        registry.coordinator.acceptedWorldState = WorldState("Harbour", "Find the keeper")
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolving(null)) }
        configureJsonClient()

        val body = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest(worldState = WorldState("Quay", "Find a map")).copy(
                playerAction = "SECRET_PLAYER_ACTION",
                flags = mapOf("has-map" to false, "already-known" to false),
                signals = mapOf("found-map" to listOf(
                    FlagAssignment("has-map", true),
                    FlagAssignment("already-known", false),
                )),
            ))
        }.bodyAsText()

        val diagnostics = body.split("\n\n").filter { it.startsWith("event: diagnostic\n") }
            .map { Json.parseToJsonElement(it.substringAfter("data: ")).jsonObject }
        assertEquals(
            listOf("turn_state", "fragment", "turn_metrics", "world_state", "context_compression", "background_rerank", "turn_status"),
            diagnostics.map { it.getValue("kind").jsonPrimitive.content },
        )
        assertEquals("Quay", diagnostics.first().getValue("fields").jsonObject.getValue("location").jsonPrimitive.content)
        assertEquals("42", diagnostics[2].getValue("metrics").jsonObject.getValue("totalDurationMs").jsonPrimitive.content)
        assertEquals("Harbour", diagnostics[3].getValue("fields").jsonObject.getValue("location").jsonPrimitive.content)
        assertFalse(diagnostics.joinToString().contains("SECRET_PLAYER_ACTION"))
        assertFalse(diagnostics.joinToString().contains("SECRET_GAME_MASTER_OUTPUT"))
        assertFalse(diagnostics.any { it.getValue("kind").jsonPrimitive.content == "signal" })
        assertContains(body, "event: token\ndata: {\"text\":\"SECRET_GAME_MASTER_OUTPUT\"}")
        assertContains(body, "event: done\n")
    }

    @Test
    fun `prefill context limit returns actionable HTTP error`() = testApplication {
        val registry = FakeRegistry()
        registry.coordinator.submitFailure = de.aidadventure.server.game.ContextLimitException()
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolving(null)) }
        configureJsonClient()
        val response = client.post("/api/prefill") {
            contentType(ContentType.Application.Json)
            setBody(prefillRequest())
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertContains(response.bodyAsText(), "context_limit")
    }

    @Test
    fun `long raw histories no longer hit the old 200 entry ceiling`() = testApplication {
        val registry = FakeRegistry()
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolving(null)) }
        configureJsonClient()
        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest(history = List(101) { visiblePair() }.flatten()))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "event: done")
    }

    @Test
    fun `completed checkpoint reaches browser and subsequent warmup unchanged`() = testApplication {
        val registry = FakeRegistry()
        val completedHistory = visiblePair() + ChatEntry("player", "look") + ChatEntry("game-master", "The door opens.")
        val checkpoint = de.aidadventure.server.game.ContextEpochs.checkpoint(
            WarmupRequest("live-1", "configured-llama-cpp", 8081, "# Scenario", completedHistory),
            "A keeper greeted the player and opened the door.",
        )
        val compression = de.aidadventure.server.model.ContextCompression(pending = checkpoint)
        registry.coordinator.turnEvents = flowOf(
            TurnStreamEvent.Token("The door opens."),
            TurnStreamEvent.InputReady,
            TurnStreamEvent.Done(TurnMetrics(null, 2, 1), compression),
        )
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolving(null)) }
        configureJsonClient()
        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest(history = visiblePair(), worldState = WorldState(location = "Harbour")))
        }
        val body = response.bodyAsText()
        assertContains(body, "A keeper greeted the player and opened the door.")
        assertContains(body, "\"pendingCoversThroughTurn\":\"2\"")
        assertEquals(1, Regex("event: input_ready").findAll(body).count())
        assertEquals(compression, registry.coordinator.rerankRequests.single().contextCompression)
        assertEquals(completedHistory, registry.coordinator.rerankRequests.single().history)
    }

    @Test
    fun `world state resolution uses the completed prompt epoch`() = testApplication {
        val registry = FakeRegistry()
        val history = visiblePair()
        val checkpoint = de.aidadventure.server.game.ContextEpochs.checkpoint(
            WarmupRequest("live-1", "configured-llama-cpp", 8081, "# Scenario", history),
            "The player reached the harbour.",
        )
        val compression = de.aidadventure.server.model.ContextCompression(epoch = 1, active = checkpoint)
        registry.coordinator.turnEvents = flowOf(
            TurnStreamEvent.Token("The door opens."),
            TurnStreamEvent.Done(TurnMetrics(null, 2, 1), compression),
        )
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolving(null)) }
        configureJsonClient()

        client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest(history = history, worldState = WorldState(location = "Harbour")))
        }

        val primary = registry.coordinator.worldStatePrimaryRequests.single()
        assertEquals(compression, primary.contextCompression)
        assertEquals(history, primary.history)
    }

    @Test
    fun `context overflow is a specific stream error`() = testApplication {
        val registry = FakeRegistry()
        registry.coordinator.beginTurnFailure = de.aidadventure.server.game.ContextLimitException()
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolving(null)) }
        configureJsonClient()
        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest())
        }
        assertContains(response.bodyAsText(), "context_limit")
        assertFalse(response.bodyAsText().contains("llama_cpp_unavailable"))
    }

    @Test
    fun `prefill and current turn do not resolve a runtime fragment`() = testApplication {
        val registry = FakeRegistry()
        val resolver = RecordingResolver("keep quiet")
        application {
            adventureModule(
                prefillRegistry = registry,
                runtimeFragmentResolver = resolver,
            )
        }
        configureJsonClient()

        client.post("/api/prefill") {
            contentType(ContentType.Application.Json)
            setBody(prefillRequest())
        }
        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest(history = visiblePair(), worldState = WorldState(location = "Harbour")))
        }

        assertContains(response.bodyAsText(), "event: token")
        assertTrue(!response.bodyAsText().contains("event: fragment"))
        runBlocking { withTimeout(1_000) { registry.coordinator.rerankCompleted.await() } }
        assertEquals(
            visiblePair() +
                de.aidadventure.server.model.ChatEntry("player", "look") +
                de.aidadventure.server.model.ChatEntry("game-master", "The door opens."),
            resolver.histories.single(),
        )
        assertEquals(
            visiblePair(),
            registry.coordinator.turnRequests.single().history,
        )
    }

    @Test
    fun `completed turn reranks the next resolved fragment before later input prefill`() = testApplication {
        val registry = FakeRegistry()
        application {
            adventureModule(
                prefillRegistry = registry,
                runtimeFragmentResolver = resolving("next fragment"),
            )
        }
        configureJsonClient()

        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest(history = visiblePair(), worldState = WorldState(location = "Harbour")))
        }

        assertContains(response.bodyAsText(), "event: done")
        runBlocking { withTimeout(1_000) { registry.coordinator.rerankCompleted.await() } }
        assertEquals(
            de.aidadventure.server.model.RuntimeFragmentDefinition("match", "next fragment"),
            registry.coordinator.rerankCandidates.single(),
        )
        assertEquals(
            listOf(
                ChatEntry("player", "ask Mira about the lighthouse"),
                ChatEntry("game-master", "Mira looks away."),
                ChatEntry("player", "look"),
                ChatEntry("game-master", "The door opens."),
            ),
            registry.coordinator.rerankRequests.single().history,
        )
        assertTrue(registry.coordinator.warmupRequests.isEmpty())
    }

    @Test
    fun `selected signal-only guidance leaves flags unchanged during next retrieval`() = testApplication {
        val registry = FakeRegistry()
        registry.coordinator.turnEvents = flowOf(
            TurnStreamEvent.Fragment("", listOf("found-map")),
            TurnStreamEvent.Token("The map is found."),
            TurnStreamEvent.Done(TurnMetrics(null, 2, 1)),
        )
        val available = RuntimeFragmentDefinition("map now available", "A route appears.", listOf(FlagCondition("has-map", true)))
        val unavailable = RuntimeFragmentDefinition("map still hidden", "The map remains hidden.", listOf(FlagCondition("has-map", false)))
        val resolver = object : RuntimeFragmentResolver {
            override suspend fun prepare(definitions: List<RuntimeFragmentDefinition>) = Unit
            override suspend fun resolve(history: List<ChatEntry>, definitions: List<RuntimeFragmentDefinition>): RuntimeFragmentDefinition? =
                definitions.singleOrNull()
        }
        application { adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolver) }
        configureJsonClient()

        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest(worldState = WorldState(location = "Harbour")).copy(
                runtimeFragments = listOf(available, unavailable),
                flags = mapOf("has-map" to false),
                signals = mapOf("found-map" to listOf(FlagAssignment("has-map", true))),
            ))
        }

        assertContains(response.bodyAsText(), "\"flags\":{\"has-map\":false}")
        assertFalse(response.bodyAsText().contains("event: fragment"))
        runBlocking { withTimeout(1_000) { registry.coordinator.rerankCompleted.await() } }
        assertEquals(mapOf("has-map" to false), registry.coordinator.rerankRequests.single().flags)
        assertEquals(unavailable, registry.coordinator.rerankCandidates.single())
    }

    @Test
    fun `completed turn without a next fragment reserves canonical background warmup`() = testApplication {
        val registry = FakeRegistry()
        application {
            adventureModule(
                prefillRegistry = registry,
                runtimeFragmentResolver = resolving(null),
            )
        }
        configureJsonClient()

        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest(history = visiblePair(), worldState = WorldState(location = "Harbour")))
        }

        assertContains(response.bodyAsText(), "event: done")
        runBlocking { withTimeout(1_000) { registry.coordinator.rerankCompleted.await() } }
        assertTrue(registry.coordinator.warmupRequests.isEmpty())
        assertEquals(listOf<de.aidadventure.server.model.RuntimeFragmentDefinition?>(null), registry.coordinator.rerankCandidates)
    }

    @Test
    fun `post turn fragment resolution failure preserves the completed response`() = testApplication {
        val registry = FakeRegistry()
        application {
            adventureModule(
                prefillRegistry = registry,
                runtimeFragmentResolver = resolverThatFailsAfterTurn(),
            )
        }
        configureJsonClient()

        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest(history = visiblePair(), worldState = WorldState(location = "Harbour")))
        }

        assertContains(response.bodyAsText(), "event: done")
        runBlocking { withTimeout(1_000) { registry.coordinator.rerankCompleted.await() } }
        assertTrue(registry.coordinator.warmupRequests.isEmpty())
        assertEquals(listOf<de.aidadventure.server.model.RuntimeFragmentDefinition?>(null), registry.coordinator.rerankCandidates)
    }

    @Test
    fun `completed turn emits done before background reranking resolution completes`() = testApplication {
        val registry = FakeRegistry()
        val resolver = blockingResolverAfterTurn()
        application {
            adventureModule(
                prefillRegistry = registry,
                runtimeFragmentResolver = resolver,
            )
        }
        configureJsonClient()

        val response = runBlocking {
            withTimeout(1_000) {
                client.post("/api/turn") {
                    contentType(ContentType.Application.Json)
                    setBody(turnRequest(history = visiblePair(), worldState = WorldState(location = "Harbour")))
                }
            }
        }

        try {
            assertContains(response.bodyAsText(), "event: done")
            runBlocking { withTimeout(1_000) { resolver.rerankStarted.await() } }
            assertEquals(1, registry.coordinator.rerankRequests.size)
            assertTrue(registry.coordinator.rerankCandidates.isEmpty())
        } finally {
            resolver.allowRerank.complete(Unit)
        }
    }

    @Test
    fun `turn without done does not reserve background reranking`() = testApplication {
        val registry = FakeRegistry().also {
            it.coordinator.turnEvents = flowOf(TurnStreamEvent.Token("The door opens."))
        }
        application {
            adventureModule(prefillRegistry = registry, runtimeFragmentResolver = resolver("current fragment"))
        }
        configureJsonClient()

        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(turnRequest(history = visiblePair(), worldState = WorldState(location = "Harbour")))
        }

        assertContains(response.bodyAsText(), "event: token")
        assertTrue(registry.coordinator.rerankRequests.isEmpty())
    }
    @Test
    fun `health returns ok`() = testApplication {
        application { adventureModule(prefillRegistry = FakeRegistry()) }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
    }

    @Test
    fun `documented development browser origins are allowed to call the API`() = testApplication {
        application { adventureModule(prefillRegistry = FakeRegistry()) }

        val response = client.options("/api/turn") {
            header(HttpHeaders.Origin, "http://localhost:8088")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, HttpHeaders.ContentType)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("http://localhost:8088", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `prefill accepts without waiting for evaluation`() = testApplication {
        val registry = FakeRegistry()
        application { adventureModule(prefillRegistry = registry) }
        configureJsonClient()

        val response = client.post("/api/prefill") {
            contentType(ContentType.Application.Json)
            setBody(prefillRequest())
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("{\"disposition\":\"STARTED\"}", response.bodyAsText())
        assertEquals(listOf(CoordinatorKey("live-1", "configured-llama-cpp", 8081)), registry.keys)
        assertEquals(1, registry.coordinator.prefillRequests.size)
    }

    @Test
    fun `warmup accepts and passes the request to the session coordinator`() = testApplication {
        val registry = FakeRegistry()
        application { adventureModule(prefillRegistry = registry) }
        configureJsonClient()
        val request = warmupRequest()

        val response = client.post("/api/warmup") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("{\"disposition\":\"STARTED\"}", response.bodyAsText())
        assertEquals(listOf(CoordinatorKey("live-1", "configured-llama-cpp", 8081)), registry.keys)
        assertEquals(listOf(request), registry.coordinator.warmupRequests)
    }

    @Test
    fun `invalid warmup context avoids registry and coordinator calls`() = testApplication {
        val registry = FakeRegistry()
        application { adventureModule(prefillRegistry = registry) }
        configureJsonClient()

        listOf(
            WarmupRequest("", "local-model", 8081, "# Scenario", emptyList()),
            WarmupRequest("live-1", "local-model", 8081, "", emptyList()),
            WarmupRequest(
                "live-1",
                "local-model",
                8081,
                "# Scenario",
                listOf(de.aidadventure.server.model.ChatEntry("invalid", "history")),
            ),
        ).forEach { request ->
            val response = client.post("/api/warmup") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        assertTrue(registry.idleCleanupTimes.isEmpty())
        assertTrue(registry.keys.isEmpty())
        assertTrue(registry.coordinator.warmupRequests.isEmpty())
    }

    @Test
    fun `oversized narrative focus reaches the warmup coordinator`() = testApplication {
        val registry = FakeRegistry()
        application { adventureModule(prefillRegistry = registry) }
        configureJsonClient()

        val response = client.post("/api/warmup") {
            contentType(ContentType.Application.Json)
            setBody(warmupRequest().copy(worldState = WorldState(narrativeFocus = "x".repeat(1_000))))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(listOf(warmupRequest().copy(worldState = WorldState(narrativeFocus = "x".repeat(1_000)))), registry.coordinator.warmupRequests)
    }

    @Test
    fun `prefill runs TTL cleanup with the injected clock before using a coordinator`() = testApplication {
        val registry = FakeRegistry()
        application { adventureModule(prefillRegistry = registry, clock = { 1234L }) }
        configureJsonClient()

        val response = client.post("/api/prefill") {
            contentType(ContentType.Application.Json)
            setBody(prefillRequest())
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(listOf(1234L), registry.idleCleanupTimes)
        assertEquals(listOf(CoordinatorKey("live-1", "configured-llama-cpp", 8081)), registry.keys)
    }

    @Test
    fun `prefill maps slot acquisition IO failure to model service unavailable`() = testApplication {
        val registry = FakeRegistry().apply {
            coordinator.submitFailure = IOException("slot service refused connection")
        }
        application { adventureModule(prefillRegistry = registry) }
        configureJsonClient()

        val response = client.post("/api/prefill") {
            contentType(ContentType.Application.Json)
            setBody(prefillRequest())
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(
            "{\"code\":\"llama_cpp_unavailable\",\"message\":\"The model service is unavailable.\"}",
            response.bodyAsText(),
        )
    }

    @Test
    fun `invalid session identity and revision avoid registry calls`() = testApplication {
        val registry = FakeRegistry()
        application { adventureModule(prefillRegistry = registry) }
        configureJsonClient()

        listOf(
            prefillRequest(sessionId = ""),
            prefillRequest(revision = -1),
        ).forEach { request ->
            val response = client.post("/api/prefill") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        assertTrue(registry.keys.isEmpty())
        assertTrue(registry.coordinator.prefillRequests.isEmpty())
    }

    @Test
    fun `turn validates model then streams through the session coordinator`() = testApplication {
        llamaCppServer().use { llama ->
            val registry = FakeRegistry()
            application { adventureModule(llamaCppGateway = LlamaCppGateway(), prefillRegistry = registry) }
            configureJsonClient()

            val response = client.post("/api/turn") {
                contentType(ContentType.Application.Json)
                setBody(turnRequest(port = llama.port))
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
            assertEquals(
                ": connected\n\n" +
                    "event: token\ndata: {\"text\":\"The door opens.\"}\n\n" +
                    "event: input_ready\ndata: {}\n\n" +
                    "event: done\ndata: {\"metrics\":{\"totalDurationMs\":null,\"promptTokens\":2,\"cacheTokens\":1},\"worldState\":{\"location\":\"\",\"narrativeFocus\":\"\"},\"flags\":{}}\n\n",
                response.bodyAsText().withoutDiagnostics(),
            )
            assertEquals(listOf(turnRequest()), registry.coordinator.turnRequests)
        }
    }

    @Test
    fun `turn preserves the accepted combined state in done and canonical warmup`() = testApplication {
        llamaCppServer().use { llama ->
            val registry = FakeRegistry()
            val acceptedWorldState = WorldState("Leuchtturm", "Finde den Saboteur")
            val locations = mapOf("Leuchtturm" to "Alter Turm auf der Mole")
            val resolver = RecordingResolver(null)
            registry.coordinator.acceptedWorldState = acceptedWorldState
            application {
                adventureModule(
                    llamaCppGateway = LlamaCppGateway(),
                    prefillRegistry = registry,
                    runtimeFragmentResolver = resolver,
                )
            }
            configureJsonClient()

            val response = client.post("/api/turn") {
                contentType(ContentType.Application.Json)
                setBody(turnRequest(port = llama.port).copy(locations = locations))
            }

            val donePayload = response.bodyAsText()
            assertContains(
                donePayload,
                "event: done\ndata: {\"metrics\":{\"totalDurationMs\":null,\"promptTokens\":2,\"cacheTokens\":1},\"worldState\":{\"location\":\"Leuchtturm\",\"narrativeFocus\":\"Finde den Saboteur\"},\"flags\":{}}",
            )
            assertFalse(donePayload.contains("unresolvedSituation"))
            assertFalse(donePayload.contains("activeThread"))
            runBlocking { withTimeout(1_000) { registry.coordinator.rerankCompleted.await() } }
            assertEquals(acceptedWorldState, registry.coordinator.rerankRequests.single().worldState)
            assertEquals(listOf<String?>(acceptedWorldState.narrativeFocus), resolver.narrativeFoci)
            assertEquals(listOf<String?>("Leuchtturm"), resolver.locations)
            assertEquals(listOf<String?>("Alter Turm auf der Mole"), resolver.locationDescriptions)
        }
    }

    @Test
    fun `production runtime fragment resolver delegates narrative focus and location context`() = runBlocking {
        val delegate = RecordingResolver(null)
        val resolver = ProductionRuntimeFragmentResolver { delegate }

        resolver.resolve(
            visiblePair(),
            listOf(de.aidadventure.server.model.RuntimeFragmentDefinition("lighthouse", "memory")),
            "Find the saboteur",
            "Lighthouse",
            "A beacon above the harbor",
        )

        assertEquals(listOf<String?>("Find the saboteur"), delegate.narrativeFoci)
        assertEquals(listOf<String?>("Lighthouse"), delegate.locations)
        assertEquals(listOf<String?>("A beacon above the harbor"), delegate.locationDescriptions)
    }

    @Test
    fun `turn skips runtime fragment retrieval when accepted world state is empty`() = testApplication {
        llamaCppServer().use { llama ->
            val registry = FakeRegistry()
            val resolver = CountingResolver()
            application {
                adventureModule(llamaCppGateway = LlamaCppGateway(), prefillRegistry = registry, runtimeFragmentResolver = resolver)
            }
            configureJsonClient()

            val response = client.post("/api/turn") {
                contentType(ContentType.Application.Json)
                setBody(turnRequest(port = llama.port).copy(
                    runtimeFragments = listOf(de.aidadventure.server.model.RuntimeFragmentDefinition("lighthouse", "hidden detail")),
                ))
            }

            assertEquals(HttpStatusCode.OK, response.status)
            runBlocking { withTimeout(1_000) { registry.coordinator.rerankCompleted.await() } }
            assertEquals(0, resolver.resolveCalls)
            assertEquals(1, registry.coordinator.rerankRequests.size)
            assertEquals(listOf<de.aidadventure.server.model.RuntimeFragmentDefinition?>(null), registry.coordinator.rerankCandidates)
        }
    }

    @Test
    fun `turn starts coordinator work inside the SSE writer and maps slot acquisition IO failure`() = testApplication {
        llamaCppServer().use { llama ->
            val registry = FakeRegistry().apply {
                coordinator.beginTurnFailure = IOException("slot service refused connection")
            }
            application {
                adventureModule(
                    llamaCppGateway = LlamaCppGateway(),
                    prefillRegistry = registry,
                    clock = { 5678L },
                )
            }
            configureJsonClient()

            val response = client.post("/api/turn") {
                contentType(ContentType.Application.Json)
                setBody(turnRequest(port = llama.port))
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf(5678L), registry.idleCleanupTimes)
            assertEquals(
                ": connected\n\n" +
                    "event: error\ndata: {\"code\":\"llama_cpp_unavailable\",\"message\":\"The model service is unavailable.\"}\n\n",
                response.bodyAsText().withoutDiagnostics(),
            )
        }
    }

    @Test
    fun `turn ignores a browser-selected model and uses the configured model`() = testApplication {
        llamaCppServer().use { llama ->
            val registry = FakeRegistry()
            application { adventureModule(llamaCppGateway = LlamaCppGateway(), prefillRegistry = registry) }
            configureJsonClient()

            val response = client.post("/api/turn") {
                contentType(ContentType.Application.Json)
                setBody(turnRequest(modelId = "other", port = llama.port))
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("configured-llama-cpp", registry.coordinator.turnRequests.single().modelId)
        }
    }

    private fun prefillRequest(
        sessionId: String = "live-1",
        revision: Long = 0,
    ) = PrefillRequest(sessionId, revision, "configured-llama-cpp", 8081, "# Scenario", emptyList(), "look")

    private fun turnRequest(
        modelId: String = "configured-llama-cpp",
        port: Int = 8081,
        history: List<de.aidadventure.server.model.ChatEntry> = emptyList(),
        worldState: WorldState = WorldState(),
    ) = TurnRequest("live-1", modelId, port, "# Scenario", history, "look").copy(worldState = worldState)

    private fun visiblePair() = listOf(
        de.aidadventure.server.model.ChatEntry("player", "ask Mira about the lighthouse"),
        de.aidadventure.server.model.ChatEntry("game-master", "Mira looks away."),
    )

    private fun resolver(fragment: String?) = object : RuntimeFragmentResolver {
        override suspend fun prepare(definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>) = Unit

        override suspend fun resolve(
            history: List<de.aidadventure.server.model.ChatEntry>,
            definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>,
        ): de.aidadventure.server.model.RuntimeFragmentDefinition? =
            fragment?.let { de.aidadventure.server.model.RuntimeFragmentDefinition("match", it) }
    }

    private class CountingResolver : RuntimeFragmentResolver {
        var resolveCalls = 0
        override suspend fun prepare(definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>) = Unit
        override suspend fun resolve(
            history: List<de.aidadventure.server.model.ChatEntry>,
            definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>,
        ): de.aidadventure.server.model.RuntimeFragmentDefinition? {
            resolveCalls++
            return null
        }
    }

    private fun resolving(vararg fragments: String?) = object : RuntimeFragmentResolver {
        private val results = fragments.iterator()

        override suspend fun prepare(definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>) = Unit

        override suspend fun resolve(
            history: List<de.aidadventure.server.model.ChatEntry>,
            definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>,
        ): de.aidadventure.server.model.RuntimeFragmentDefinition? =
            results.next()?.let { de.aidadventure.server.model.RuntimeFragmentDefinition("match", it) }
    }

    private fun resolverThatFailsAfterTurn() = object : RuntimeFragmentResolver {

        override suspend fun prepare(definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>) = Unit

        override suspend fun resolve(
            history: List<de.aidadventure.server.model.ChatEntry>,
            definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>,
        ): de.aidadventure.server.model.RuntimeFragmentDefinition? = error("secret fragment details")
    }

    private class BlockingResolverAfterTurn : RuntimeFragmentResolver {
        val rerankStarted = CompletableDeferred<Unit>()
        val allowRerank = CompletableDeferred<Unit>()
        override suspend fun prepare(definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>) = Unit

        override suspend fun resolve(
            history: List<de.aidadventure.server.model.ChatEntry>,
            definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>,
        ): de.aidadventure.server.model.RuntimeFragmentDefinition? {
            rerankStarted.complete(Unit)
            allowRerank.await()
            return de.aidadventure.server.model.RuntimeFragmentDefinition("next match", "next fragment")
        }
    }

    private fun blockingResolverAfterTurn() = BlockingResolverAfterTurn()

    private class RecordingResolver(private val fragment: String?) : RuntimeFragmentResolver {
        val histories = mutableListOf<List<ChatEntry>>()
        val narrativeFoci = mutableListOf<String?>()
        val locations = mutableListOf<String?>()
        val locationDescriptions = mutableListOf<String?>()

        override suspend fun prepare(definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>) = Unit

        override suspend fun resolve(
            history: List<ChatEntry>,
            definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>,
        ): de.aidadventure.server.model.RuntimeFragmentDefinition? {
            histories += history
            return fragment?.let { de.aidadventure.server.model.RuntimeFragmentDefinition("match", it) }
        }

        override suspend fun resolve(
            history: List<ChatEntry>,
            definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>,
            narrativeFocus: String?,
        ): de.aidadventure.server.model.RuntimeFragmentDefinition? {
            narrativeFoci += narrativeFocus
            return resolve(history, definitions)
        }

        override suspend fun resolve(
            history: List<ChatEntry>,
            definitions: List<de.aidadventure.server.model.RuntimeFragmentDefinition>,
            narrativeFocus: String?,
            location: String?,
            locationDescription: String?,
        ): de.aidadventure.server.model.RuntimeFragmentDefinition? {
            narrativeFoci += narrativeFocus
            locations += location
            locationDescriptions += locationDescription
            return resolve(history, definitions)
        }
    }

    private fun warmupRequest() = WarmupRequest("live-1", "configured-llama-cpp", 8081, "# Scenario", emptyList())

    private class FakeRegistry : PrefillRegistry {
        val keys = mutableListOf<CoordinatorKey>()
        val idleCleanupTimes = mutableListOf<Long>()
        val coordinator = FakeCoordinator()

        override fun coordinatorFor(key: CoordinatorKey): PrefillCoordinator {
            keys += key
            return coordinator
        }

        override fun coordinatorIfPresent(key: CoordinatorKey): PrefillCoordinator? = coordinator

        override suspend fun remove(key: CoordinatorKey) = Unit

        override suspend fun removeIdle(nowMillis: Long) {
            idleCleanupTimes += nowMillis
        }
    }

    private class FakeCoordinator : PrefillCoordinator {
        val prefillRequests = mutableListOf<PrefillRequest>()
        val warmupRequests = mutableListOf<WarmupRequest>()
        val rerankRequests = mutableListOf<WarmupRequest>()
        val rerankCandidates = mutableListOf<de.aidadventure.server.model.RuntimeFragmentDefinition?>()
        val rerankCompleted = CompletableDeferred<Unit>()
        private val backgroundOutcomes = mutableMapOf<String, StreamDiagnostic>()
        val turnRequests = mutableListOf<TurnRequest>()
        val worldStatePrimaryRequests = mutableListOf<TurnRequest>()
        var turnEvents: Flow<TurnStreamEvent>? = null
        var acceptedWorldState: WorldState? = null
        var submitFailure: Throwable? = null
        var beginTurnFailure: Throwable? = null

        override suspend fun submit(request: PrefillRequest): PrefillDisposition {
            prefillRequests += request
            submitFailure?.let { throw it }
            return PrefillDisposition.STARTED
        }

        override suspend fun warmup(request: WarmupRequest, requestId: String): PrefillDisposition {
            warmupRequests += request
            return PrefillDisposition.STARTED
        }

        override suspend fun rerankAndWarmup(
            request: WarmupRequest,
            candidate: de.aidadventure.server.model.RuntimeFragmentDefinition,
            requestId: String,
        ): PrefillDisposition {
            rerankRequests += request
            rerankCandidates += candidate
            return PrefillDisposition.STARTED
        }

        override suspend fun reserveRerank(
            request: WarmupRequest,
            requestId: String,
        ): de.aidadventure.server.prefill.RerankReservation {
            rerankRequests += request
            return de.aidadventure.server.prefill.RerankReservation(1)
        }

        override suspend fun completeReservedRerank(
            reservation: de.aidadventure.server.prefill.RerankReservation,
            candidate: de.aidadventure.server.model.RuntimeFragmentDefinition?,
        ): Boolean {
            rerankCandidates += candidate
            backgroundOutcomes[reservation.operationId] = StreamDiagnostic(
                "background_rerank", "prior_completed", fields = mapOf(
                    "source" to "prior_turn", "operationId" to reservation.operationId,
                    "result" to if (candidate == null) "no_candidate" else "accepted",
                ),
            )
            rerankCompleted.complete(Unit)
            return true
        }

        override suspend fun awaitBackgroundDiagnostic(operationId: String): StreamDiagnostic? =
            backgroundOutcomes[operationId]

        override suspend fun beginTurn(request: TurnRequest): Flow<TurnStreamEvent> {
            turnRequests += request
            beginTurnFailure?.let { throw it }
            return turnEvents ?: flowOf(
                TurnStreamEvent.Token("The door opens."),
                TurnStreamEvent.Done(TurnMetrics(null, 2, 1)),
            )
        }

        override suspend fun resolveWorldStateAndWarmup(
            primaryRequest: TurnRequest,
            gameMasterResponse: String,
            request: WarmupRequest,
            resolution: WorldStateResolutionRequest,
            requestId: String,
        ): WorldState {
            worldStatePrimaryRequests += primaryRequest
            return acceptedWorldState ?: request.worldState
        }

        override fun tryBeginIdleClose(): Boolean = false

        override suspend fun close() = Unit
    }

    private fun llamaCppServer(): FakeLlamaCppServer = FakeLlamaCppServer().also { it.start() }

    private class FakeLlamaCppServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        var modelDiscoveryCalls = 0
            private set

        val port: Int get() = server.address.port

        init {
            server.createContext("/v1/models") { exchange ->
                modelDiscoveryCalls++
                val body = "{\"data\":[{\"id\":\"local-model\"}]}".toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(HttpStatusCode.OK.value, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }

        fun start() = server.start()

        override fun close() = server.stop(0)
    }

    private fun ApplicationTestBuilder.configureJsonClient() {
        client = createClient {
            install(ContentNegotiation) { json() }
        }
    }

    private fun String.withoutDiagnostics(): String = replace(Regex("event: diagnostic\\ndata: [^\\n]*\\n\\n"), "")
}
