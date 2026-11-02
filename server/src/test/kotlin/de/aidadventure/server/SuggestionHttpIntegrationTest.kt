package de.aidadventure.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.aidadventure.server.runtimefragments.RuntimeFragmentResolver
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.mockk.mockk
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuggestionHttpIntegrationTest {
    @Test fun `disconnect stops the model request and permits prefill on retained slot`() {
        ModelServer(holdStream = true).use { model ->
            AppServer(model.port).use { app ->
                val response = app.postStreaming("/api/suggestion", actionRequest("look"))
                val reader = response.body().bufferedReader()
                while (!(reader.readLine() ?: error("stream ended before token")).startsWith("event: token")) { }
                assertTrue(model.streaming.await(5, TimeUnit.SECONDS), "model stream did not start")
                reader.close()
                assertTrue(model.disconnected.await(10, TimeUnit.SECONDS), "model stream was not closed after client disconnected")
                assertEquals(202, app.post("/api/prefill", prefillRequest("look again")))
                assertTrue(model.prefilled.await(10, TimeUnit.SECONDS), "retained slot was not available for prefill")
                assertEquals(listOf(1, 1), model.completions.take(2).map { it.getValue("id_slot").jsonPrimitive.int })
                assertEquals(1, model.erases.get(), "session slot should remain warm")
            }
        }
    }

    @Test fun `suggestion prefill and turn use canonical history and one slot`() {
        ModelServer().use { model ->
            AppServer(model.port).use { app ->
                assertEquals(200, app.postAndRead("/api/suggestion", actionRequest("look")).first)
                assertEquals(202, app.post("/api/prefill", prefillRequest("look closer")))
                assertTrue(model.prefilled.await(10, TimeUnit.SECONDS))
                val turn = app.postStreaming("/api/turn", actionRequest("look closer"))
                turn.body().bufferedReader().use { reader ->
                    while (!(reader.readLine() ?: error("stream ended before token")).startsWith("event: token")) { }
                }
                val calls = model.completions.take(3)
                assertEquals(3, calls.size)
                assertEquals(listOf(1, 1, 1), calls.map { it.getValue("id_slot").jsonPrimitive.int })
                assertTrue(calls.all { it.getValue("cache_prompt").jsonPrimitive.boolean })
                val suggestion = calls[0].getValue("prompt").jsonPrimitive.content
                val prefill = calls[1].getValue("prompt").jsonPrimitive.content
                val play = calls[2].getValue("prompt").jsonPrimitive.content
                assertTrue(suggestion.startsWith("[system]# Scene[/][user]old[/][assistant]reply[/][user]look"))
                assertTrue(prefill.startsWith("[system]# Scene[/][user]old[/][assistant]reply[/][user]look"))
                assertEquals(prefill, play)
                assertTrue("PLAYER ACTION SO FAR" in suggestion)
                assertTrue("PLAYER ACTION SO FAR" !in prefill)
                assertEquals(1, model.erases.get())
            }
        }
    }

    private class AppServer(modelPort: Int) : AutoCloseable {
        private val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            adventureModule(llamaCppPort = modelPort, runtimeFragmentResolver = mockk<RuntimeFragmentResolver>(relaxed = true))
        }.start(wait = false)
        private val port = runBlocking { server.engine.resolvedConnectors().first().port }
        private val client = HttpClient.newHttpClient()
        private fun request(path: String, body: String) = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        fun postStreaming(path: String, body: String): HttpResponse<java.io.InputStream> = client.send(request(path, body), HttpResponse.BodyHandlers.ofInputStream())
        fun post(path: String, body: String): Int = client.send(request(path, body), HttpResponse.BodyHandlers.discarding()).statusCode()
        fun postAndRead(path: String, body: String): Pair<Int, String> = client.send(request(path, body), HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
        override fun close() { server.stop(500, 2000) }
    }

    private class ModelServer(private val holdStream: Boolean = false) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newCachedThreadPool()
        private val running = AtomicBoolean(true)
        val streaming = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val prefilled = CountDownLatch(1)
        val erases = java.util.concurrent.atomic.AtomicInteger()
        val completions = CopyOnWriteArrayList<JsonObject>()
        val port get() = server.address.port
        init {
            server.executor = executor
            server.createContext("/slots") { e ->
                if (e.requestMethod == "POST") { erases.incrementAndGet(); e.respond("{}") }
                else e.respond("[{\"id\":1,\"is_processing\":false,\"n_ctx\":8192}]")
            }
            server.createContext("/tokenize") { e -> e.respond("{\"tokens\":[1]}") }
            server.createContext("/apply-template") { e ->
                val messages = Json.parseToJsonElement(e.requestBody.bufferedReader().readText()).jsonObject.getValue("messages").jsonArray
                val prompt = messages.joinToString("") { m ->
                    val obj = m.jsonObject
                    "[${obj.getValue("role").jsonPrimitive.content}]${obj.getValue("content").jsonPrimitive.content}[/]"
                }
                e.respond(Json.encodeToString(mapOf("prompt" to prompt)))
            }
            server.createContext("/completion") { e ->
                val body = Json.parseToJsonElement(e.requestBody.bufferedReader().readText()).jsonObject
                completions += body
                if (!body.getValue("stream").jsonPrimitive.boolean) {
                    e.respond("{\"tokens_cached\":17,\"tokens_evaluated\":3}")
                    prefilled.countDown()
                } else {
                    e.responseHeaders.add("Content-Type", "text/event-stream")
                    e.sendResponseHeaders(200, 0)
                    try {
                        e.responseBody.bufferedWriter().use { writer ->
                            writer.write("data: {\"content\":\"more\",\"stop\":false}\n\n"); writer.flush()
                            streaming.countDown()
                            if (holdStream) {
                                while (running.get()) {
                                    writer.write("data: {\"content\":\"${"x".repeat(1024)}\",\"stop\":false}\n\n")
                                    writer.flush(); Thread.sleep(10)
                                }
                            } else {
                                writer.write("data: {\"content\":\"\",\"stop\":true,\"tokens_cached\":17,\"tokens_evaluated\":3}\n\n"); writer.flush()
                            }
                        }
                    } catch (_: Exception) { disconnected.countDown() }
                }
            }
            server.start()
        }
        private fun HttpExchange.respond(body: String) {
            val bytes = body.toByteArray()
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        override fun close() { running.set(false); server.stop(0); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS) }
    }

    companion object {
        private val history = "\"history\":[{\"role\":\"player\",\"content\":\"old\"},{\"role\":\"game-master\",\"content\":\"reply\"}]"
        private fun actionRequest(action: String) = "{\"sessionId\":\"live-1\",\"scenarioMarkdown\":\"# Scene\",$history,\"playerAction\":\"$action\"}"
        private fun prefillRequest(action: String) = "{\"sessionId\":\"live-1\",\"revision\":1,\"scenarioMarkdown\":\"# Scene\",$history,\"playerAction\":\"$action\"}"
    }
}
