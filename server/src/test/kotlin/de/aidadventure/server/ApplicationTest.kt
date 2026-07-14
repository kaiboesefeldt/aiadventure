package de.aidadventure.server

import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.ModelProvider
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import de.aidadventure.server.llamacpp.LlamaCppGateway
import de.aidadventure.server.ollama.OllamaGateway
import com.sun.net.httpserver.HttpServer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun `health returns ok`() = testApplication {
        application { adventureModule(FakeGateway()) }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
    }

    @Test
    fun `root serves the packaged browser entry document`() = testApplication {
        application { adventureModule(FakeGateway()) }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("<div id=\"root\"></div>"))
    }

    @Test
    fun `packaged browser bundle is served as a static asset`() = testApplication {
        application { adventureModule(FakeGateway()) }

        val response = client.get("/web.js")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `missing static assets return not found`() = testApplication {
        application { adventureModule(FakeGateway()) }

        val response = client.get("/missing-asset.js")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `models returns fake gateway model names`() = testApplication {
        application { adventureModule(FakeGateway(models = listOf("small"))) }

        val response = client.get("/api/models")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"models\":[\"small\"]}", response.bodyAsText())
    }

    @Test
    fun `gateway failures return a request identifier`() = testApplication {
        application { adventureModule(FakeGateway(failure = IllegalStateException("offline"))) }

        val response = client.get("/api/models")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertNotNull(response.headers["X-Request-ID"])
    }

    @Test
    fun `llama models returns model names from the selected local endpoint`() = testApplication {
        llamaCppServer().use { llama ->
            application { adventureModule(FakeGateway(), llamaCppGateway = LlamaCppGateway()) }

            val response = client.get("/api/llama/models?port=${llama.port}")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("{\"models\":[\"local-model\"]}", response.bodyAsText())
            assertEquals(1, llama.modelDiscoveryCalls)
        }
    }

    @Test
    fun `llama models rejects blank and out of range ports without discovery`() = testApplication {
        llamaCppServer().use { llama ->
            application { adventureModule(FakeGateway(), llamaCppGateway = LlamaCppGateway()) }

            listOf("", "0", "65536").forEach { port ->
                val response = client.get("/api/llama/models?port=$port")
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

            assertEquals(0, llama.modelDiscoveryCalls)
        }
    }

    @Test
    fun `llama models maps unavailable discovery to service unavailable`() = testApplication {
        llamaCppServer(modelsStatus = HttpStatusCode.ServiceUnavailable.value).use { llama ->
            application { adventureModule(FakeGateway(), llamaCppGateway = LlamaCppGateway()) }

            val response = client.get("/api/llama/models?port=${llama.port}")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("{\"code\":\"llama_cpp_unavailable\",\"message\":\"The model service is unavailable.\"}", response.bodyAsText())
            assertEquals(1, llama.modelDiscoveryCalls)
        }
    }

    @Test
    fun `llama models maps a refused connection to service unavailable`() = testApplication {
        application { adventureModule(FakeGateway(), llamaCppGateway = LlamaCppGateway()) }

        val response = client.get("/api/llama/models?port=${refusedLoopbackPort()}")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("{\"code\":\"llama_cpp_unavailable\",\"message\":\"The model service is unavailable.\"}", response.bodyAsText())
    }

    @Test
    fun `turn rejects blank scenario without calling gateway`() = testApplication {
        val gateway = FakeGateway()
        application { adventureModule(gateway) }
        configureJsonClient()

        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(TurnRequest("model", "", emptyList(), "look"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, gateway.streamCalls)
    }

    @Test
    fun `turn accepts a scenario longer than message limit but within scenario limit`() = testApplication {
        val gateway = FakeGateway()
        application { adventureModule(gateway) }
        configureJsonClient()

        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(TurnRequest("model", "#${"x".repeat(20_000)}", emptyList(), "look"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, gateway.streamCalls)
    }

    @Test
    fun `turn rejects missing model without calling play`() = testApplication {
        val gateway = FakeGateway(models = listOf("small"))
        application { adventureModule(gateway) }
        configureJsonClient()

        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(TurnRequest("missing", "# Scenario", emptyList(), "look"))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(0, gateway.streamCalls)
    }

    @Test
    fun `turn maps unavailable gateway to service unavailable`() = testApplication {
        application { adventureModule(FakeGateway(failure = IllegalStateException("offline"))) }
        configureJsonClient()

        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(TurnRequest("model", "# Scenario", emptyList(), "look"))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `llama turn rejects missing and out of range ports before model discovery`() = testApplication {
        application { adventureModule(FakeGateway()) }
        configureJsonClient()

        listOf<Int?>(null, 0, 65536).forEach { port ->
            val response = client.post("/api/turn") {
                contentType(ContentType.Application.Json)
                setBody(TurnRequest("local-model", "# Scenario", emptyList(), "look", ModelProvider.LLAMA_CPP, port))
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `llama turn maps a refused model discovery connection to service unavailable`() = testApplication {
        application { adventureModule(FakeGateway(), llamaCppGateway = LlamaCppGateway()) }
        configureJsonClient()

        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(TurnRequest("local-model", "# Scenario", emptyList(), "look", ModelProvider.LLAMA_CPP, refusedLoopbackPort()))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("{\"code\":\"llama_cpp_unavailable\",\"message\":\"The model service is unavailable.\"}", response.bodyAsText())
    }

    @Test
    fun `llama turn rejects an unknown model before streaming`() = testApplication {
        llamaCppServer().use { llama ->
            application { adventureModule(FakeGateway(), llamaCppGateway = LlamaCppGateway()) }
            configureJsonClient()

            val response = client.post("/api/turn") {
                contentType(ContentType.Application.Json)
                setBody(TurnRequest("unknown-model", "# Scenario", emptyList(), "look", ModelProvider.LLAMA_CPP, llama.port))
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(1, llama.modelDiscoveryCalls)
            assertEquals(0, llama.streamCalls)
        }
    }

    @Test
    fun `turn validates and streams through llama cpp gateway`() = testApplication {
        llamaCppServer().use { llama ->
            application { adventureModule(FakeGateway(), llamaCppGateway = LlamaCppGateway()) }
            configureJsonClient()

            val response = client.post("/api/turn") {
                contentType(ContentType.Application.Json)
                setBody(TurnRequest("local-model", "# Scenario", emptyList(), "look", ModelProvider.LLAMA_CPP, llama.port))
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                ": connected\n\n" +
                    "event: done\ndata: {\"metrics\":{\"totalDurationMs\":null,\"promptTokens\":null,\"cacheTokens\":null}}\n\n",
                response.bodyAsText(),
            )
            assertEquals(1, llama.modelDiscoveryCalls)
            assertEquals(1, llama.streamCalls)
        }
    }

    @Test
    fun `llama turn streams a provider-safe error after a gateway failure`() = testApplication {
        llamaCppServer(streamStatus = HttpStatusCode.ServiceUnavailable.value).use { llama ->
            application { adventureModule(FakeGateway(), llamaCppGateway = LlamaCppGateway()) }
            configureJsonClient()

            val response = client.post("/api/turn") {
                contentType(ContentType.Application.Json)
                setBody(TurnRequest("local-model", "# Scenario", emptyList(), "look", ModelProvider.LLAMA_CPP, llama.port))
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                ": connected\n\n" +
                    "event: error\ndata: {\"code\":\"llama_cpp_unavailable\",\"message\":\"The model service is unavailable.\"}\n\n",
                response.bodyAsText(),
            )
            assertEquals(1, llama.streamCalls)
        }
    }

    @Test
    fun `turn streams token frames and terminal metrics`() = testApplication {
        application { adventureModule(FakeGateway()) }
        configureJsonClient()

        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(TurnRequest("model", "# Scenario", emptyList(), "look"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
        assertEquals(
            ": connected\n\n" +
                "event: token\ndata: {\"text\":\"The \"}\n\n" +
                "event: token\ndata: {\"text\":\"door opens.\"}\n\n" +
                "event: done\ndata: {\"metrics\":{\"totalDurationMs\":null,\"promptTokens\":12,\"cacheTokens\":null}}\n\n",
            response.bodyAsText(),
        )
    }

    @Test
    fun `turn streams a safe error frame after a gateway failure`() = testApplication {
        application { adventureModule(FakeGateway(streamFailure = IllegalStateException("offline"))) }
        configureJsonClient()

        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(TurnRequest("model", "# Scenario", emptyList(), "look"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            ": connected\n\n" +
                "event: token\ndata: {\"text\":\"The \"}\n\n" +
                "event: error\ndata: {\"code\":\"ollama_unavailable\",\"message\":\"The model service is unavailable.\"}\n\n",
            response.bodyAsText(),
        )
    }

    @Test
    fun `turn reuses discovered models within the catalog ttl`() = testApplication {
        val gateway = FakeGateway()
        var now = 0L
        application { adventureModule(gateway, clock = { now }) }
        configureJsonClient()

        repeat(2) {
            val response = client.post("/api/turn") {
                contentType(ContentType.Application.Json)
                setBody(TurnRequest("model", "# Scenario", emptyList(), "look"))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        assertEquals(1, gateway.modelDiscoveryCalls)
    }

    @Test
    fun `models and turn share the catalog within the ttl`() = testApplication {
        val gateway = FakeGateway()
        application { adventureModule(gateway) }
        configureJsonClient()

        assertEquals(HttpStatusCode.OK, client.get("/api/models").status)
        val turnResponse = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(TurnRequest("model", "# Scenario", emptyList(), "look"))
        }

        assertEquals(HttpStatusCode.OK, turnResponse.status)
        assertEquals(1, gateway.modelDiscoveryCalls)
    }

    @Test
    fun `turn refreshes discovered models after the catalog ttl`() = testApplication {
        val gateway = FakeGateway()
        var now = 0L
        application { adventureModule(gateway, clock = { now }) }
        configureJsonClient()

        client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(TurnRequest("model", "# Scenario", emptyList(), "look"))
        }
        now += 30_000
        client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(TurnRequest("model", "# Scenario", emptyList(), "look"))
        }

        assertEquals(2, gateway.modelDiscoveryCalls)
    }

    private class FakeGateway(
        private val models: List<String> = listOf("model"),
        private val failure: RuntimeException? = null,
        private val streamFailure: RuntimeException? = null,
    ) : OllamaGateway {
        var streamCalls = 0
        var modelDiscoveryCalls = 0

        override fun availableModels(): List<String> {
            modelDiscoveryCalls++
            failure?.let { throw it }
            return models
        }

        override fun stream(request: TurnRequest): Flow<TurnStreamEvent> {
            streamCalls++
            failure?.let { throw it }
            return streamFailure?.let { failure ->
                flow {
                    emit(TurnStreamEvent.Token("The "))
                    throw failure
                }
            } ?: flowOf(
                TurnStreamEvent.Token("The "),
                TurnStreamEvent.Token("door opens."),
                TurnStreamEvent.Done(TurnMetrics(null, 12, null)),
            )
        }
    }

    private fun llamaCppServer(
        modelsStatus: Int = HttpStatusCode.OK.value,
        streamStatus: Int = HttpStatusCode.OK.value,
    ): FakeLlamaCppServer = FakeLlamaCppServer(modelsStatus, streamStatus).also { it.start() }

    private fun refusedLoopbackPort(): Int = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

    private class FakeLlamaCppServer(
        private val modelsStatus: Int,
        private val streamStatus: Int,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

        var modelDiscoveryCalls = 0
            private set
        var streamCalls = 0
            private set

        val port: Int get() = server.address.port

        init {
            server.createContext("/v1/models") { exchange ->
                modelDiscoveryCalls++
                exchange.respond(modelsStatus, if (modelsStatus in 200..299) "{\"data\":[{\"id\":\"local-model\"}]}" else "{}")
            }
            server.createContext("/v1/chat/completions") { exchange ->
                streamCalls++
                if (streamStatus !in 200..299) {
                    exchange.respond(streamStatus, "{}")
                } else {
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(HttpStatusCode.OK.value, 0)
                    exchange.responseBody.use { body -> body.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8)) }
                }
            }
        }

        fun start() = server.start()

        override fun close() = server.stop(0)

        private fun com.sun.net.httpserver.HttpExchange.respond(status: Int, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.configureJsonClient() {
        client = createClient {
            install(ContentNegotiation) { json() }
        }
    }
}
