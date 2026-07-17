package de.aidadventure.server

import com.sun.net.httpserver.HttpServer
import de.aidadventure.server.llamacpp.LlamaCppGateway
import de.aidadventure.server.model.PrefillDisposition
import de.aidadventure.server.model.PrefillRequest
import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import de.aidadventure.server.model.WarmupRequest
import de.aidadventure.server.prefill.CoordinatorKey
import de.aidadventure.server.prefill.PrefillCoordinator
import de.aidadventure.server.prefill.PrefillRegistry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.IOException
import io.ktor.serialization.kotlinx.json.json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
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
                    "event: done\ndata: {\"metrics\":{\"totalDurationMs\":null,\"promptTokens\":2,\"cacheTokens\":1}}\n\n",
                response.bodyAsText(),
            )
            assertEquals(listOf(turnRequest()), registry.coordinator.turnRequests)
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
                response.bodyAsText(),
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
    ) = TurnRequest("live-1", modelId, port, "# Scenario", emptyList(), "look")

    private fun warmupRequest() = WarmupRequest("live-1", "configured-llama-cpp", 8081, "# Scenario", emptyList())

    private class FakeRegistry : PrefillRegistry {
        val keys = mutableListOf<CoordinatorKey>()
        val idleCleanupTimes = mutableListOf<Long>()
        val coordinator = FakeCoordinator()

        override fun coordinatorFor(key: CoordinatorKey): PrefillCoordinator {
            keys += key
            return coordinator
        }

        override suspend fun remove(key: CoordinatorKey) = Unit

        override suspend fun removeIdle(nowMillis: Long) {
            idleCleanupTimes += nowMillis
        }
    }

    private class FakeCoordinator : PrefillCoordinator {
        val prefillRequests = mutableListOf<PrefillRequest>()
        val warmupRequests = mutableListOf<WarmupRequest>()
        val turnRequests = mutableListOf<TurnRequest>()
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

        override suspend fun beginTurn(request: TurnRequest): Flow<TurnStreamEvent> {
            turnRequests += request
            beginTurnFailure?.let { throw it }
            return flowOf(
                TurnStreamEvent.Token("The door opens."),
                TurnStreamEvent.Done(TurnMetrics(null, 2, 1)),
            )
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
}
