package de.aidadventure.server

import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnResponse
import de.aidadventure.server.ollama.OllamaGateway
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
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `turn rejects blank scenario without calling gateway`() = testApplication {
        val gateway = FakeGateway()
        application { adventureModule(gateway) }
        configureJsonClient()

        val response = client.post("/api/turn") {
            contentType(ContentType.Application.Json)
            setBody(TurnRequest("model", "", emptyList(), "look"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, gateway.playCalls)
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
        assertEquals(1, gateway.playCalls)
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
        assertEquals(0, gateway.playCalls)
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
    ) : OllamaGateway {
        var playCalls = 0
        var modelDiscoveryCalls = 0

        override fun availableModels(): List<String> {
            modelDiscoveryCalls++
            failure?.let { throw it }
            return models
        }

        override fun play(request: TurnRequest): TurnResponse {
            playCalls++
            failure?.let { throw it }
            return TurnResponse(
                message = ChatEntry("game-master", "The door opens."),
                metrics = TurnMetrics(null, null, null),
            )
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.configureJsonClient() {
        client = createClient {
            install(ContentNegotiation) { json() }
        }
    }
}
