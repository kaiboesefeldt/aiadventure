package de.aidadventure.server.llamacpp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.aidadventure.server.game.PromptFactory
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.FragmentRelevanceRequest
import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import de.aidadventure.server.model.WarmupRequest
import de.aidadventure.server.worldstate.WorldState
import de.aidadventure.server.worldstate.WorldStateResolutionRequest
import de.aidadventure.server.worldstate.WorldStateAnalysis
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
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
    fun `native turn rejects actual templated token overflow before generation`() = runBlocking {
        nativeServer(contextCapacity = 1030, tokenCount = 100).use { server ->
            assertFailsWith<de.aidadventure.server.game.ContextLimitException> {
                LlamaCppGateway().stream(request(server.port), LlamaCppSlot(2)).toList()
            }
            assertTrue(server.completionBodies.isEmpty())
        }
    }

    @Test
    fun `budget counts template with native BOS and special token semantics and bounds output`() = runBlocking {
        nativeServer().use { server ->
            LlamaCppGateway().stream(request(server.port), LlamaCppSlot(2)).toList()
            val tokenization = server.tokenizeBodies.single().jsonObject
            assertEquals("templated prompt", tokenization.getValue("content").jsonPrimitive.content)
            assertTrue(tokenization.getValue("add_special").jsonPrimitive.boolean)
            assertTrue(tokenization.getValue("parse_special").jsonPrimitive.boolean)
            assertEquals(1024, server.completionBodies.single().jsonObject.getValue("n_predict").jsonPrimitive.int)
        }
    }

    @Test
    fun `uses a 300 second default llama cpp timeout`() {
        assertEquals(Duration.ofSeconds(300), LlamaCppGateway.LLAMA_CPP_TIMEOUT)
    }

    @Test
    fun `traces incremental prompts and one final response for a streamed turn`() = runBlocking {
        nativeServer(streamContents = listOf("Hello ", "world")).use { server ->
            val trace = RecordingTraceLogger()
            val gateway = LlamaCppGateway(promptFactory = PromptFactory(), traceLogger = trace)
            val firstRequest = request(server.port)

            gateway.prefill(firstRequest, LlamaCppSlot(2))
            gateway.stream(firstRequest, LlamaCppSlot(2)).toList()
            gateway.prefill(
                firstRequest.copy(
                    history = firstRequest.history + listOf(
                        ChatEntry("player", firstRequest.playerAction),
                        ChatEntry("game-master", "Hello world"),
                    ),
                    playerAction = "Continue",
                ),
                LlamaCppSlot(2),
            )

            assertEquals(
                listOf(
                    LlmTraceMessage("system", "# Forest"),
                    LlmTraceMessage("user", "Look around"),
                    LlmTraceMessage("assistant", "Misty trees surround you."),
                    LlmTraceMessage("user", "Follow the path"),
                ),
                trace.prompts.first().historyDelta,
            )
            assertTrue(trace.prompts[1].historyDelta.isEmpty())
            assertEquals("Hello world", trace.finalResponses.single().content)
            assertTrue(trace.entries.none { it.kind == "token" })
            assertEquals(
                listOf(LlmTraceMessage("user", "Continue")),
                trace.prompts.last().historyDelta,
            )
            assertEquals("live-1", trace.prompts.first().sessionId)
            assertEquals("turn", trace.prompts[1].operation)
            assertTrue(trace.prompts[1].historyDelta.isEmpty())
            assertEquals(CacheMetrics(17, 3), trace.completions.first().metrics)
        }
    }

    @Test
    fun `chat stream aggregates chunks into one final response at DONE`() = runBlocking {
        chatServer(streamContents = listOf("Hello ", "world")).use { server ->
            val trace = RecordingTraceLogger()
            val events = LlamaCppGateway(traceLogger = trace).stream(request(server.port)).toList()

            assertEquals(
                listOf(TurnStreamEvent.Token("Hello "), TurnStreamEvent.Token("world"), TurnStreamEvent.Done(TurnMetrics(null, null, null))),
                events,
            )
            assertEquals(listOf(TraceEntry.FinalResponse("chat_completion", "live-1", "Hello world")), trace.finalResponses)
            assertTrue(trace.entries.none { it.kind == "token" })
        }
    }

    @Test
    fun `uncollected chat stream does not advance trace cursor for a later request`() = runBlocking {
        chatServer(streamContents = listOf("Hello")).use { server ->
            val trace = RecordingTraceLogger()
            val gateway = LlamaCppGateway(traceLogger = trace)

            gateway.stream(request(server.port))
            gateway.stream(request(server.port)).toList()

            assertEquals(
                listOf(
                    LlmTraceMessage("system", "# Forest"),
                    LlmTraceMessage("user", "Look around"),
                    LlmTraceMessage("assistant", "Misty trees surround you."),
                    LlmTraceMessage("user", "Follow the path"),
                ),
                trace.prompts.last().historyDelta,
            )
            assertEquals(1, trace.prompts.size)
        }
    }

    @Test
    fun `streams complete without a final response trace when output is blank`() = runBlocking {
        nativeServer(streamContents = emptyList()).use { native ->
            chatServer(streamContents = emptyList()).use { chat ->
                val nativeTrace = RecordingTraceLogger()
                val chatTrace = RecordingTraceLogger()

                assertEquals(
                    listOf(TurnStreamEvent.Done(TurnMetrics(null, 20, 17))),
                    LlamaCppGateway(traceLogger = nativeTrace).stream(request(native.port), LlamaCppSlot(2)).toList(),
                )
                assertEquals(
                    listOf(TurnStreamEvent.Done(TurnMetrics(null, null, null))),
                    LlamaCppGateway(traceLogger = chatTrace).stream(request(chat.port)).toList(),
                )
                assertTrue(nativeTrace.finalResponses.isEmpty())
                assertTrue(chatTrace.finalResponses.isEmpty())
            }
        }
    }

    @Test
    fun `disabled trace does not retain native stream content or advance the transcript`() = runBlocking {
        nativeServer(streamContents = listOf("Hello")).use { server ->
            val trace = RecordingTraceLogger(enabled = false)
            val gateway = LlamaCppGateway(traceLogger = trace)

            gateway.stream(request(server.port), LlamaCppSlot(2)).toList()
            assertTrue(trace.entries.isEmpty())

            trace.enabled = true
            gateway.prefill(
                request(server.port).copy(
                    history = listOf(ChatEntry("player", "Look around"), ChatEntry("game-master", "Hello")),
                    playerAction = "Continue",
                ),
                LlamaCppSlot(2),
            )

            assertEquals(
                listOf(
                    LlmTraceMessage("system", "# Forest"),
                    LlmTraceMessage("user", "Look around"),
                    LlmTraceMessage("assistant", "Hello"),
                    LlmTraceMessage("user", "Continue"),
                ),
                trace.prompts.single().historyDelta,
            )
        }
    }

    @Test
    fun `trace cursors are isolated by session id`() = runBlocking {
        nativeServer().use { server ->
            val trace = RecordingTraceLogger()
            val gateway = LlamaCppGateway(traceLogger = trace)

            gateway.prefill(request(server.port), LlamaCppSlot(2))
            gateway.prefill(request(server.port).copy(sessionId = "live-2"), LlamaCppSlot(2))

            assertEquals("live-1", trace.prompts[0].sessionId)
            assertEquals("live-2", trace.prompts[1].sessionId)
            assertEquals(trace.prompts[0].historyDelta, trace.prompts[1].historyDelta)
        }
    }

    @Test
    fun `trace escaping preserves backslash line feed carriage return and tab`() {
        assertEquals("""\\\n\r\t""", "\\\n\r\t".escapeForLlmTrace())
    }

    @Test
    fun `trace messages use indented readable content instead of escaped line feeds`() {
        assertEquals(
            """
            [
              {
                "role": "system",
                "content":
                  First line
                  Second \"quoted\" line
              }
            ]
            """.trimIndent(),
            formatLlmTraceMessages(listOf(LlmTraceMessage("system", "First line\nSecond \"quoted\" line"))),
        )
    }

    @Test
    fun `fragment classification generates exactly one token on the retained slot`() = runBlocking {
        nativeServer(completionContent = "Y").use { server ->
            val gateway = LlamaCppGateway(promptFactory = PromptFactory())

            assertEquals("Y", gateway.classifyFragment(relevanceRequest(server.port), LlamaCppSlot(2)))

            assertEquals(listOf("/apply-template", "/tokenize", "/slots", "/completion"), server.paths)
            val completionBody = server.completionBodies.single().jsonObject
            assertEquals(2, completionBody.getValue("id_slot").jsonPrimitive.int)
            assertEquals(1, completionBody.getValue("n_predict").jsonPrimitive.int)
            assertEquals(false, completionBody.getValue("stream").jsonPrimitive.boolean)
            assertEquals("root ::= \"Y\" | \"N\"", completionBody.getValue("grammar").jsonPrimitive.content)
        }
    }

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
    fun `warmup evaluates action-free template without generation`() = runBlocking {
        nativeServer().use { server ->
            val metrics = LlamaCppGateway(promptFactory = PromptFactory()).warmup(warmup(server.port), LlamaCppSlot(2))

            assertEquals(listOf("/apply-template", "/tokenize", "/slots", "/completion"), server.paths)
            assertEquals(
                listOf("system"),
                server.templateBodies.single().jsonObject.getValue("messages").jsonArray.map {
                    it.jsonObject.getValue("role").jsonPrimitive.content
                },
            )
            val body = server.completionBodies.single().jsonObject
            assertEquals(2, body.getValue("id_slot").jsonPrimitive.int)
            assertTrue(body.getValue("cache_prompt").jsonPrimitive.boolean)
            assertEquals(0, body.getValue("n_predict").jsonPrimitive.int)
            assertEquals(false, body.getValue("stream").jsonPrimitive.boolean)
            assertEquals(CacheMetrics(17, 3), metrics)
        }
    }

    @Test
    fun `prefill evaluates the templated prompt without generation`() = runBlocking {
        nativeServer().use { server ->
            val gateway = LlamaCppGateway(promptFactory = PromptFactory())

            val metrics = gateway.prefill(request(server.port), LlamaCppSlot(2))

            assertEquals(listOf("/apply-template", "/tokenize", "/slots", "/completion"), server.paths)
            assertEquals(
                listOf("system", "user", "assistant", "user"),
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
    fun `prefill waits beyond the standard request timeout for template application`() = runBlocking {
        nativeServer(templateDelayMillis = 5_500).use { server ->
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
            assertEquals(listOf("/apply-template", "/tokenize", "/slots", "/completion"), server.paths)
            val body = server.completionBodies.single().jsonObject
            assertEquals(2, body.getValue("id_slot").jsonPrimitive.int)
            assertTrue(body.getValue("cache_prompt").jsonPrimitive.boolean)
            assertEquals(true, body.getValue("stream").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `world state analysis uses one combined completion on the primary slot with cache prompt`() = runBlocking {
        nativeServer(completionContent = """{"location":"Harbour","narrativeFocus":"Keep searching"}""").use { server ->
            val gateway = LlamaCppGateway(promptFactory = PromptFactory())
            val primary = request(server.port).copy(locations = mapOf("Harbour" to "A quay", "Bell Tower" to ""))
            val resolution = WorldStateResolutionRequest(
                primary.sessionId,
                primary.scenarioMarkdown,
                WorldState(location = "Harbour"),
                primary.playerAction,
                "The bell rings at the harbour.",
                locations = mapOf("Harbour" to "A quay", "Bell Tower" to ""),
            )

            val resolved = gateway.resolveWorldState(primary, resolution.gameMasterResponse, resolution, LlamaCppSlot(2))
            assertEquals(WorldState("Harbour", "Keep searching"), resolved)

            assertEquals(
                listOf("/apply-template", "/tokenize", "/slots", "/completion"),
                server.paths,
            )
            assertEquals(1, server.completionBodies.size)
            assertEquals(
                listOf(
                    "system" to "# Forest\n\nCANONICAL LOCATION CATALOG (use each key as the location name; descriptions give spatial context):\n- Bell Tower\n- Harbour — A quay",
                    "user" to "Look around",
                    "assistant" to "Misty trees surround you.",
                    "user" to "Follow the path",
                    "assistant" to "The bell rings at the harbour.",
                    "user" to WorldStateAnalysis.PROMPT,
                ),
                server.templateBodies.single().jsonObject.getValue("messages").jsonArray.map {
                    it.jsonObject.getValue("role").jsonPrimitive.content to it.jsonObject.getValue("content").jsonPrimitive.content
                },
            )
            val body = server.completionBodies.single().jsonObject
            assertEquals(2, body.getValue("id_slot").jsonPrimitive.int)
            assertTrue(body.getValue("cache_prompt").jsonPrimitive.boolean)
            assertEquals(false, body.getValue("stream").jsonPrimitive.boolean)
            assertEquals(256, body.getValue("n_predict").jsonPrimitive.int)
            val schema = body.getValue("json_schema").jsonObject
            assertEquals(setOf("location", "narrativeFocus"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals(setOf("location", "narrativeFocus"), schema.getValue("properties").jsonObject.keys)
            assertEquals(listOf("Bell Tower", "Harbour"), schema.getValue("properties").jsonObject
                .getValue("location").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(400, schema.getValue("properties").jsonObject.getValue("narrativeFocus").jsonObject
                .getValue("anyOf").jsonArray[1].jsonObject.getValue("maxLength").jsonPrimitive.int)
        }
    }

    @Test
    fun `world state analysis rejects a structured prompt beyond the retained slot capacity`() = runBlocking {
        nativeServer(contextCapacity = 150, tokenCount = 100).use { server ->
            val primary = request(server.port).copy(locations = mapOf("Harbour" to "A quay", "Bell Tower" to ""))
            val resolution = WorldStateResolutionRequest(
                primary.sessionId,
                primary.scenarioMarkdown,
                WorldState(location = "Harbour"),
                primary.playerAction,
                "The bell rings at the harbour.",
                locations = mapOf("Harbour" to "A quay", "Bell Tower" to ""),
            )

            assertFailsWith<de.aidadventure.server.game.ContextLimitException> {
                LlamaCppGateway(promptFactory = PromptFactory()).resolveWorldState(
                    primary, resolution.gameMasterResponse, resolution, LlamaCppSlot(2),
                )
            }
            assertTrue(server.completionBodies.isEmpty())
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

    private fun warmup(port: Int) = WarmupRequest(
        sessionId = "live-1",
        modelId = "local-model",
        llamaCppPort = port,
        scenarioMarkdown = "# Forest",
        history = emptyList(),
    )

    private fun relevanceRequest(port: Int) = FragmentRelevanceRequest(
        sessionId = "live-1",
        modelId = "local-model",
        llamaCppPort = port,
        scenarioMarkdown = "# Forest",
        history = listOf(ChatEntry("player", "Look around")),
        candidateKey = "candidate key",
    )

    private fun nativeServer(
        templatePrompt: String = "templated prompt",
        templateDelayMillis: Long = 0,
        completionDelayMillis: Long = 0,
        completionContent: String? = null,
        streamContents: List<String> = listOf("Hello"),
        contextCapacity: Int = 8192,
        tokenCount: Int = 10,
    ): NativeServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        return NativeServer(server, templatePrompt, templateDelayMillis, completionDelayMillis, completionContent, streamContents, contextCapacity, tokenCount).also { it.start() }
    }

    private fun chatServer(streamContents: List<String>): ChatServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        return ChatServer(server, streamContents).also { it.start() }
    }

    private class NativeServer(
        private val server: HttpServer,
        private val templatePrompt: String,
        private val templateDelayMillis: Long,
        private val completionDelayMillis: Long,
        private val completionContent: String?,
        private val streamContents: List<String>,
        private val contextCapacity: Int,
        private val tokenCount: Int,
    ) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        val tokenizeBodies = mutableListOf<String>()
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
            server.createContext("/slots") { exchange ->
                record(exchange)
                exchange.respondJson("[{\"id\":2,\"is_processing\":false,\"n_ctx\":$contextCapacity}]")
            }
            server.createContext("/tokenize") { exchange ->
                record(exchange)
                tokenizeBodies += exchange.requestBody.bufferedReader().use { it.readText() }
                exchange.respondJson("{\"tokens\":[${List(tokenCount) { "1" }.joinToString(",")}]}")
            }
            server.createContext("/apply-template") { exchange ->
                record(exchange)
                templateBodies += exchange.requestBody.bufferedReader().use { it.readText() }
                if (templateDelayMillis > 0) Thread.sleep(templateDelayMillis)
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
                        streamContents.forEach { content ->
                            writer.write("data: {\"content\":\"$content\",\"stop\":false}\n\n")
                        }
                        writer.write("data: {\"content\":\"\",\"stop\":true,\"tokens_cached\":17,\"tokens_evaluated\":20}\n\n")
                        writer.flush()
                    }
                } else {
                    val content = completionContent?.let { ",\"content\":${Json.encodeToString(it)}" }.orEmpty()
                    exchange.respondJson("{\"tokens_cached\":17,\"tokens_evaluated\":3$content}")
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

    private class ChatServer(
        private val server: HttpServer,
        private val streamContents: List<String>,
    ) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        val port: Int get() = server.address.port

        init {
            server.executor = executor
            server.createContext("/v1/chat/completions") { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.bufferedWriter().use { writer ->
                    streamContents.forEach { content ->
                        writer.write("""data: {"choices":[{"delta":{"content":"$content"}}]}

""")
                    }
                    writer.write("data: [DONE]\n\n")
                }
            }
        }

        fun start() = server.start()

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

}

private class RecordingTraceLogger(enabled: Boolean = true) : LlmTraceLogger {
    var enabled = enabled
    override val isTraceEnabled get() = enabled
    val entries = mutableListOf<TraceEntry>()
    val prompts get() = entries.filterIsInstance<TraceEntry.Prompt>()
    val finalResponses get() = entries.filterIsInstance<TraceEntry.FinalResponse>()
    val completions get() = entries.filterIsInstance<TraceEntry.Completion>()

    override fun prompt(operation: String, sessionId: String, historyDelta: List<LlmTraceMessage>) {
        entries += TraceEntry.Prompt(operation, sessionId, historyDelta)
    }

    override fun completion(operation: String, sessionId: String, metrics: CacheMetrics) {
        entries += TraceEntry.Completion(operation, sessionId, metrics)
    }

    override fun finalResponse(operation: String, sessionId: String, content: String) {
        entries += TraceEntry.FinalResponse(operation, sessionId, content)
    }
}

private sealed interface TraceEntry {
    val kind: String

    data class Prompt(val operation: String, val sessionId: String, val historyDelta: List<LlmTraceMessage>) : TraceEntry {
        override val kind = "prompt"
    }

    data class FinalResponse(val operation: String, val sessionId: String, val content: String) : TraceEntry {
        override val kind = "finalResponse"
    }

    data class Completion(val operation: String, val sessionId: String, val metrics: CacheMetrics) : TraceEntry {
        override val kind = "completion"
    }
}

fun HttpExchange.respondJson(body: String) {
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(200, body.toByteArray().size.toLong())
    responseBody.bufferedWriter().use { it.write(body) }
}

private val String.jsonObject get() = Json.parseToJsonElement(this).jsonObject
