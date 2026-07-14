package de.aidadventure.server

import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import de.aidadventure.server.ollama.OllamaGateway
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
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ApplicationTest {
    @Test
    fun `health returns ok`() = testApplication {
        application { adventureModule(FakeGateway()) }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
    }

    @Test
    fun `models returns fake gateway model names`() = testApplication {
        application { adventureModule(FakeGateway(models = listOf("small"))) }

        val response = client.get("/api/models")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"models\":[\"small\"]}", response.bodyAsText())
    }

    @Test
    fun `cors accepts the fixed browser development origin`() = testApplication {
        application { adventureModule(FakeGateway()) }

        val response = client.get("/api/models") {
            header("Origin", "http://localhost:8088")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("http://localhost:8088", response.headers["Access-Control-Allow-Origin"])
    }

    @Test
    fun `cors permits a JSON turn request from the browser development origin`() = testApplication {
        application { adventureModule(FakeGateway()) }

        val response = client.options("/api/turn") {
            header("Origin", "http://localhost:8088")
            header("Access-Control-Request-Method", "POST")
            header("Access-Control-Request-Headers", "content-type")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("http://localhost:8088", response.headers["Access-Control-Allow-Origin"])
        assertEquals(HttpHeaders.ContentType, response.headers["Access-Control-Allow-Headers"])
    }

    @Test
    fun `gateway failures return a request identifier`() = testApplication {
        application { adventureModule(FakeGateway(failure = IllegalStateException("offline"))) }

        val response = client.get("/api/models")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertNotNull(response.headers["X-Request-ID"])
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
            "event: token\\ndata: {\"text\":\"The \"}\\n\\n" +
                "event: token\\ndata: {\"text\":\"door opens.\"}\\n\\n" +
                "event: done\\ndata: {\"metrics\":{\"totalDurationMs\":null,\"promptTokens\":12,\"cacheTokens\":null}}\\n\\n",
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
            "event: token\\ndata: {\"text\":\"The \"}\\n\\n" +
                "event: error\\ndata: {\"code\":\"ollama_unavailable\",\"message\":\"The model service is unavailable.\"}\\n\\n",
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

    private fun io.ktor.server.testing.ApplicationTestBuilder.configureJsonClient() {
        client = createClient {
            install(ContentNegotiation) { json() }
        }
    }
}
