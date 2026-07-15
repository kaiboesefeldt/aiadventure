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
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.io.InputStream
import java.net.ProxySelector
import java.net.CookieHandler
import java.net.Authenticator
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlamaCppGatewayTest {
    @Test
    fun `cancelling before response publication closes the later body without reading it`() = runBlocking {
        val client = PublicationGatedHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var collection: Job? = null
        try {
            val gateway = LlamaCppGateway(httpClient = client, promptFactory = PromptFactory())

            collection = scope.launch {
                gateway.stream(request(8081)).toList()
            }
            assertTrue(client.bodyAvailable.await(2, TimeUnit.SECONDS))
            collection.cancel()
            client.releaseBody()
            withTimeout(2_000) {
                collection.join()
            }

            val body = requireNotNull(client.responseBody)
            assertEquals(1, body.closeCount.get())
            assertEquals(0, body.readCount.get())
        } finally {
            client.releaseBody()
            collection?.cancelAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun `fails on a non success provider response`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(503, 0)
            exchange.responseBody.bufferedWriter().use { it.write("{\"error\":{\"message\":\"overloaded\"}}") }
        }
        server.start()

        try {
            val failure = assertFailsWith<IllegalStateException> {
                LlamaCppGateway(promptFactory = PromptFactory()).stream(request(server.address.port)).toList()
            }
            assertEquals("llama.cpp chat completion failed: HTTP 503", failure.message)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `fails on an SSE JSON error object`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                writer.write("data: {\"error\":{\"message\":\"overloaded\"}}\n\n")
                writer.flush()
            }
        }
        server.start()

        try {
            val failure = assertFailsWith<IllegalStateException> {
                LlamaCppGateway(promptFactory = PromptFactory()).stream(request(server.address.port)).toList()
            }
            assertEquals("llama.cpp chat completion returned an error.", failure.message)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `forwards standalone whitespace and newline SSE content fragments`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            val body = """
                data: {"choices":[{"delta":{"content":"Hello"}}]}

                data: {"choices":[{"delta":{"content":" "}}]}

                data: {"choices":[{"delta":{"content":"\n"}}]}

                data: {"choices":[{"delta":{"content":"world"}}]}

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
            assertEquals(
                listOf(
                    TurnStreamEvent.Token("Hello"),
                    TurnStreamEvent.Token(" "),
                    TurnStreamEvent.Token("\n"),
                    TurnStreamEvent.Token("world"),
                    TurnStreamEvent.Done(TurnMetrics(null, null, null)),
                ),
                LlamaCppGateway(promptFactory = PromptFactory()).stream(request(server.address.port)).toList(),
            )
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `does not time out an SSE stream after its response has started`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            Thread.sleep(200)
            exchange.responseBody.bufferedWriter().use { writer ->
                writer.write("data: {\"choices\":[{\"delta\":{\"content\":\"late\"}}]}\n\n")
                writer.write("data: [DONE]\n\n")
                writer.flush()
            }
        }
        server.start()

        try {
            val gateway = LlamaCppGateway(
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                promptFactory = PromptFactory(),
                requestTimeout = Duration.ofMillis(100),
            )

            assertEquals(
                listOf(
                    TurnStreamEvent.Token("late"),
                    TurnStreamEvent.Done(TurnMetrics(null, null, null)),
                ),
                gateway.stream(request(server.address.port)).toList(),
            )
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `fails when the upstream stream ends before DONE`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                writer.write("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")
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
                history = emptyList(),
                playerAction = "Follow the path",
                provider = ModelProvider.LLAMA_CPP,
                llamaCppPort = server.address.port,
            )

            val failure = assertFailsWith<IllegalStateException> {
                gateway.stream(request).toList()
            }
            assertEquals("llama.cpp chat completion ended before [DONE].", failure.message)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancelling the collector stops an open upstream response`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        val firstEventWritten = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                writer.write("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n")
                writer.flush()
                firstEventWritten.countDown()
                releaseResponse.await()
                writer.write("data: [DONE]\n\n")
                writer.flush()
            }
        }
        server.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var collection: Job? = null
        try {
            val gateway = LlamaCppGateway(
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                promptFactory = PromptFactory(),
            )
            val request = TurnRequest(
                modelId = "local-model",
                scenarioMarkdown = "# Forest",
                history = emptyList(),
                playerAction = "Follow the path",
                provider = ModelProvider.LLAMA_CPP,
                llamaCppPort = server.address.port,
            )

            collection = scope.launch {
                gateway.stream(request).take(1).toList()
            }
            assertTrue(firstEventWritten.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) {
                collection.join()
            }
        } finally {
            releaseResponse.countDown()
            collection?.cancelAndJoin()
            scope.cancel()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancelling the collector interrupts an upstream request before it responds`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        val requestReceived = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.close()
            requestReceived.countDown()
            releaseResponse.await()
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        server.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var collection: Job? = null
        try {
            val gateway = LlamaCppGateway(
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                promptFactory = PromptFactory(),
            )
            val request = TurnRequest(
                modelId = "local-model",
                scenarioMarkdown = "# Forest",
                history = emptyList(),
                playerAction = "Follow the path",
                provider = ModelProvider.LLAMA_CPP,
                llamaCppPort = server.address.port,
            )

            collection = scope.launch {
                gateway.stream(request).toList()
            }
            assertTrue(requestReceived.await(2, TimeUnit.SECONDS))
            collection.cancel()
            withTimeout(2_000) {
                collection.join()
            }
        } finally {
            releaseResponse.countDown()
            collection?.cancelAndJoin()
            scope.cancel()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `rejects a blank first discovered model ID`() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/v1/models") { exchange ->
            exchange.respondJson("{\"data\":[{\"id\":\"  \\t\"},{\"id\":\"later-model\"}]}")
        }
        server.start()

        try {
            val failure = assertFailsWith<IllegalStateException> {
                LlamaCppGateway(promptFactory = PromptFactory()).availableModels(server.address.port)
            }

            assertEquals("llama.cpp model discovery returned a blank model ID.", failure.message)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

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

    private fun request(port: Int) = TurnRequest(
        modelId = "local-model",
        scenarioMarkdown = "# Forest",
        history = emptyList(),
        playerAction = "Follow the path",
        provider = ModelProvider.LLAMA_CPP,
        llamaCppPort = port,
    )

    private class PublicationGatedHttpClient : HttpClient() {
        val bodyAvailable = CountDownLatch(1)
        private val release = CountDownLatch(1)

        @Volatile
        var responseBody: CloseTrackingInputStream? = null

        fun releaseBody() {
            release.countDown()
        }

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<Duration> = Optional.empty()

        override fun followRedirects(): Redirect = Redirect.NEVER

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext = SSLContext.getDefault()

        override fun sslParameters(): SSLParameters = SSLParameters()

        override fun authenticator(): Optional<Authenticator> = Optional.empty()

        override fun version(): Version = Version.HTTP_1_1

        override fun executor() = Optional.empty<java.util.concurrent.Executor>()

        override fun newWebSocketBuilder(): WebSocket.Builder = throw UnsupportedOperationException()

        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            val trackedBody = CloseTrackingInputStream(InputStream.nullInputStream())
            responseBody = trackedBody
            bodyAvailable.countDown()
            awaitReleaseIgnoringInterrupts()
            @Suppress("UNCHECKED_CAST")
            return ResponseWithBody(request, trackedBody) as HttpResponse<T>
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(UnsupportedOperationException())

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(UnsupportedOperationException())

        private fun awaitReleaseIgnoringInterrupts() {
            while (true) {
                try {
                    release.await()
                    return
                } catch (_: InterruptedException) {
                    // The test deliberately models a response arriving despite cancellation.
                }
            }
        }
    }

    private class ResponseWithBody(
        private val request: HttpRequest,
        private val body: InputStream,
    ) : HttpResponse<InputStream> {
        override fun statusCode(): Int = 200

        override fun request(): HttpRequest = request

        override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()

        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun body(): InputStream = body

        override fun sslSession() = Optional.empty<javax.net.ssl.SSLSession>()

        override fun uri() = request.uri()

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    private class CloseTrackingInputStream(private val delegate: InputStream) : InputStream() {
        val closeCount = AtomicInteger()
        val readCount = AtomicInteger()

        override fun read(): Int {
            readCount.incrementAndGet()
            return delegate.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readCount.incrementAndGet()
            return delegate.read(buffer, offset, length)
        }

        override fun close() {
            closeCount.incrementAndGet()
            delegate.close()
        }
    }
}
