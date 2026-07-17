package de.aidadventure.server.llamacpp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.aidadventure.server.game.PromptFactory
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LlamaCppGatewayTest {
    @Test
    fun `prompt fingerprint is deterministic HMAC prefix without prompt text`() {
        val prompt = "The hidden vault opens only at dusk."

        val fingerprint = LlamaCppGateway.promptFingerprint(prompt)

        assertEquals(fingerprint, LlamaCppGateway.promptFingerprint(prompt))
        assertNotEquals("3150735069398b8daeb8c17f", fingerprint)
        assertEquals(24, fingerprint.length)
        assertFalse(fingerprint.contains(prompt))
    }

    @Test
    fun `prefill evaluates the templated prompt without generation`() = runBlocking {
        nativeServer().use { server ->
            val gateway = LlamaCppGateway(promptFactory = PromptFactory())

            val metrics = gateway.prefill(request(server.port), LlamaCppSlot(2))

            assertEquals(listOf("/apply-template", "/completion"), server.paths)
            assertEquals(
                listOf("system", "system", "user", "assistant", "user"),
                server.templateBodies.single().jsonObject.getValue("messages").jsonArray.map {
                    it.jsonObject.getValue("role").jsonPrimitive.content
                },
            )
            val body = server.completionBodies.single().jsonObject
            assertEquals("templated prompt", body.getValue("prompt").jsonPrimitive.content)
            assertEquals(2, body.getValue("id_slot").jsonPrimitive.int)
            assertTrue(body.getValue("cache_prompt").jsonPrimitive.boolean)
            assertEquals(0, body.getValue("n_predict").jsonPrimitive.int)
            assertEquals(false, body.getValue("stream").jsonPrimitive.boolean)
            assertEquals(CacheMetrics(17, 3), metrics)
        }
    }

    @Test
    fun `prefill waits beyond the standard request timeout`() = runBlocking {
        nativeServer(completionDelayMillis = 5_500).use { server ->
            val metrics = LlamaCppGateway().prefill(request(server.port), LlamaCppSlot(2))

            assertEquals(CacheMetrics(17, 3), metrics)
        }
    }

    @Test
    fun `turn uses the same slot and maps cache metrics`() = runBlocking {
        nativeServer().use { server ->
            val gateway = LlamaCppGateway(promptFactory = PromptFactory())

            assertEquals(
                listOf(
                    TurnStreamEvent.Token("Hello"),
                    TurnStreamEvent.Done(TurnMetrics(null, 20, 17)),
                ),
                gateway.stream(request(server.port), LlamaCppSlot(2)).toList(),
            )
            assertEquals(listOf("/apply-template", "/completion"), server.paths)
            val body = server.completionBodies.single().jsonObject
            assertEquals(2, body.getValue("id_slot").jsonPrimitive.int)
            assertTrue(body.getValue("cache_prompt").jsonPrimitive.boolean)
            assertEquals(true, body.getValue("stream").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `discovers the first local model`() {
        nativeServer().use { server ->
            assertEquals(listOf("local-model"), LlamaCppGateway().availableModels(server.port))
        }
    }

    @Test
    fun `rejects a blank templated prompt`() = runBlocking {
        nativeServer(templatePrompt = " \t ").use { server ->
            val failure = assertFailsWith<IllegalStateException> {
                LlamaCppGateway().prefill(request(server.port), LlamaCppSlot(2))
            }

            assertEquals("llama.cpp template application returned a blank prompt.", failure.message)
            assertEquals(listOf("/apply-template"), server.paths)
        }
    }

    private fun request(port: Int) = TurnRequest(
        sessionId = "live-1",
        modelId = "local-model",
        llamaCppPort = port,
        scenarioMarkdown = "# Forest",
        history = listOf(ChatEntry("player", "Look around"), ChatEntry("game-master", "Misty trees surround you.")),
        playerAction = "Follow the path",
    )

    private fun nativeServer(
        templatePrompt: String = "templated prompt",
        completionDelayMillis: Long = 0,
    ): NativeServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        return NativeServer(server, templatePrompt, completionDelayMillis).also { it.start() }
    }

    private class NativeServer(
        private val server: HttpServer,
        private val templatePrompt: String,
        private val completionDelayMillis: Long,
    ) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        val paths = mutableListOf<String>()
        val templateBodies = mutableListOf<String>()
        val completionBodies = mutableListOf<String>()
        val port: Int get() = server.address.port

        init {
            server.executor = executor
            server.createContext("/v1/models") { exchange ->
                record(exchange)
                exchange.respondJson("{\"data\":[{\"id\":\"local-model\"}]}")
            }
            server.createContext("/apply-template") { exchange ->
                record(exchange)
                templateBodies += exchange.requestBody.bufferedReader().use { it.readText() }
                exchange.respondJson(Json.encodeToString(mapOf("prompt" to templatePrompt)))
            }
            server.createContext("/completion") { exchange ->
                record(exchange)
                completionBodies += exchange.requestBody.bufferedReader().use { it.readText() }
                if (completionDelayMillis > 0) Thread.sleep(completionDelayMillis)
                val body = completionBodies.last().jsonObject
                if (body.getValue("stream").jsonPrimitive.boolean) {
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.bufferedWriter().use { writer ->
                        writer.write("data: {\"content\":\"Hello\",\"stop\":false}\n\n")
                        writer.write("data: {\"content\":\"\",\"stop\":true,\"tokens_cached\":17,\"tokens_evaluated\":20}\n\n")
                        writer.flush()
                    }
                } else {
                    exchange.respondJson("{\"tokens_cached\":17,\"tokens_evaluated\":3}")
                }
            }
        }

        fun start() = server.start()

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }

        private fun record(exchange: HttpExchange) {
            paths += exchange.requestURI.path
        }
    }

}

fun HttpExchange.respondJson(body: String) {
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(200, body.toByteArray().size.toLong())
    responseBody.bufferedWriter().use { it.write(body) }
}

private val String.jsonObject get() = Json.parseToJsonElement(this).jsonObject
