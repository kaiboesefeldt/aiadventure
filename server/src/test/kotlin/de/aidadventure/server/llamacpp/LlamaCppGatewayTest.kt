package de.aidadventure.server.llamacpp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.aidadventure.server.game.PromptFactory
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.ModelProvider
import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class LlamaCppGatewayTest {
    @Test
    fun `discovers local models and streams OpenAI completion tokens in prompt order`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        var requestedModelsPath: String? = null
        var requestedCompletionsPath: String? = null
        var completionRequestBody: String? = null
        server.executor = executor
        server.createContext("/v1/models") { exchange ->
            requestedModelsPath = exchange.requestURI.path
            exchange.respondJson("{\"data\":[{\"id\":\"local-model\"}]}")
        }
        server.createContext("/v1/chat/completions") { exchange ->
            requestedCompletionsPath = exchange.requestURI.path
            completionRequestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            val body = """
                data: {"choices":[{"delta":{"content":"Hello"}}]}

                data: {"choices":[{"delta":{"content":" world"}}]}

                data: [DONE]

            """.trimIndent()
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                writer.write(body)
                writer.flush()
            }
        }
        server.start()

        try {
            val gateway = LlamaCppGateway(
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                promptFactory = PromptFactory(),
            )
            val request = TurnRequest(
                modelId = "local-model",
                scenarioMarkdown = "# Forest",
                history = listOf(ChatEntry("player", "Look around"), ChatEntry("game-master", "Misty trees surround you.")),
                playerAction = "Follow the path",
                provider = ModelProvider.LLAMA_CPP,
                llamaCppPort = server.address.port,
            )

            assertEquals(listOf("local-model"), gateway.availableModels(server.address.port))
            assertEquals(
                listOf(
                    TurnStreamEvent.Token("Hello"),
                    TurnStreamEvent.Token(" world"),
                    TurnStreamEvent.Done(TurnMetrics(null, null, null)),
                ),
                gateway.stream(request).toList(),
            )
            assertEquals("/v1/models", requestedModelsPath)
            assertEquals("/v1/chat/completions", requestedCompletionsPath)

            val payload = Json.parseToJsonElement(requireNotNull(completionRequestBody)).jsonObject
            assertEquals("local-model", payload.getValue("model").jsonPrimitive.content)
            assertEquals(true, payload.getValue("stream").jsonPrimitive.boolean)
            assertEquals(
                listOf(
                    "You are a concise, responsive game master. Continue the adventure from the scenario and conversation.",
                    "# Forest",
                    "Look around",
                    "Misty trees surround you.",
                    "Follow the path",
                ),
                payload.getValue("messages").jsonArray.map { it.jsonObject.getValue("content").jsonPrimitive.content },
            )
            assertEquals(
                listOf("system", "system", "user", "assistant", "user"),
                payload.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content },
            )
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpExchange.respondJson(body: String) {
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, body.toByteArray().size.toLong())
        responseBody.bufferedWriter().use { it.write(body) }
    }
}
