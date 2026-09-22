package de.aidadventure.server.llamacpp

import com.sun.net.httpserver.HttpServer
import de.aidadventure.server.game.ContextEpochs
import de.aidadventure.server.game.ContextLimitException
import de.aidadventure.server.model.*
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Fake tokenizer measures the complete applied template, never claims live model cache reuse. */
class ContextBudgetTest {
    @Test fun `recap retains slot pending epoch then activation warms exact compressed prefix`() = runBlocking {
        BudgetServer().use { server ->
            val gateway = LlamaCppGateway()
            val source = server.request()
            val pending = gateway.maintain(source, LlamaCppSlot(7))
            assertNotNull(pending.pending)
            assertNull(pending.active)
            val tail = List(3) { listOf(ChatEntry("player", "walk"), ChatEntry("game-master", "Onward.")) }.flatten()
            val request = source.copy(history = source.history + tail, contextCompression = pending).turn("go")
            val selected = gateway.prepare(request, LlamaCppSlot(7))
            assertEquals(1L, selected.contextCompression.epoch)
            assertEquals(pending.pending, selected.contextCompression.active)
            gateway.prefill(selected, LlamaCppSlot(7))
            assertEquals(setOf(7), server.completions.map { it["id_slot"]!!.jsonPrimitive.int }.toSet())
            val warmPrompt = server.completions[1]["prompt"]!!.jsonPrimitive.content
            val turnPrompt = server.completions[2]["prompt"]!!.jsonPrimitive.content
            assertTrue(turnPrompt.startsWith(warmPrompt))
            assertEquals(0, server.erases)
        }
    }
    @Test fun `action pressure activates younger pending epoch and oversized action fails before completion`() = runBlocking {
        BudgetServer().use { server ->
            val gateway = LlamaCppGateway()
            val source = server.request()
            val pending = gateway.maintain(source, LlamaCppSlot(7))
            val selected = gateway.prepare(source.copy(contextCompression = pending).turn("go".repeat(1000)), LlamaCppSlot(7))
            assertEquals(1L, selected.contextCompression.epoch)
            val before = server.completions.size
            assertFailsWith<ContextLimitException> { gateway.prepare(selected.copy(playerAction = "x".repeat(20000)), LlamaCppSlot(7)) }
            assertEquals(before, server.completions.size)
        }
    }
    @Test fun `empty oversized truncated and nonreducing recaps preserve old epoch`() = runBlocking {
        BudgetServer().use { server ->
            val gateway = LlamaCppGateway()
            for (recap in listOf(" ", "x".repeat(16001), "x".repeat(13000))) {
                server.recap = recap
                assertEquals(ContextCompression(), gateway.maintain(server.request(), LlamaCppSlot(7)))
            }
            server.recap = "Short recap."
            server.truncated = true
            assertEquals(ContextCompression(), gateway.maintain(server.request(), LlamaCppSlot(7)))
        }
    }
    @Test fun `oversized source never runs unsafe recap and static scenario overflow fails explicitly`() = runBlocking {
        BudgetServer().use { server ->
            val gateway = LlamaCppGateway()
            val large = server.request().copy(scenarioMarkdown = "x".repeat(17000))
            assertEquals(ContextCompression(), gateway.maintain(large, LlamaCppSlot(7)))
            assertFailsWith<ContextLimitException> { gateway.prepare(large.turn("go"), LlamaCppSlot(7)) }
            assertTrue(server.completions.isEmpty())
        }
    }
    @Test fun `repeated recap binds expanded raw source and preserves active until next epoch`() = runBlocking {
        BudgetServer().use { server ->
            val gateway = LlamaCppGateway()
            val original = server.request()
            val pending = gateway.maintain(original, LlamaCppSlot(7))
            val active = ContextEpochs.select(original.copy(contextCompression = pending), pressure = true)
            val expanded = original.copy(history = original.history + original.history, contextCompression = active)
            val second = gateway.maintain(expanded, LlamaCppSlot(7))
            assertEquals(active.active, second.active)
            assertEquals(4, second.pending?.coversThroughEntry)
            assertEquals(2L, ContextEpochs.select(expanded.copy(contextCompression = second), pressure = true).epoch)
        }
    }
    @Test fun `reducing recap without another turn and maintenance reserve is rejected`() = runBlocking {
        BudgetServer().use { server ->
            val source = server.request().copy(scenarioMarkdown = "x".repeat(10000),
                history = listOf(ChatEntry("player", "look"), ChatEntry("game-master", "x".repeat(3000))))
            assertEquals(ContextCompression(), LlamaCppGateway().maintain(source, LlamaCppSlot(7)))
            assertEquals(1, server.completions.size)
        }
    }
    private fun WarmupRequest.turn(action: String) = TurnRequest(sessionId, modelId, llamaCppPort, scenarioMarkdown, history, action, runtimeFragments, worldState, contextCompression)
    private class BudgetServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val completions = mutableListOf<JsonObject>()
        var recap = "The traveller arrived at the harbour and met a sailor."
        var truncated = false
        var erases = 0
        init {
            server.createContext("/slots") { e ->
                if (e.requestURI.query != null) erases++
                e.respond("""[{"id":7,"is_processing":false,"n_ctx":4096}]""")
            }
            server.createContext("/apply-template") { e ->
                val body = Json.parseToJsonElement(e.requestBody.bufferedReader().readText()).jsonObject
                val prompt = body["messages"]!!.jsonArray.joinToString("") { value ->
                    val m = value.jsonObject
                    "<${m["role"]!!.jsonPrimitive.content}>${m["content"]!!.jsonPrimitive.content}</message>"
                }
                e.respond(buildJsonObject { put("prompt", prompt) }.toString())
            }
            server.createContext("/tokenize") { e ->
                val body = Json.parseToJsonElement(e.requestBody.bufferedReader().readText()).jsonObject
                val size = body["content"]!!.jsonPrimitive.content.length / 4 + 1
                e.respond(buildJsonObject { put("tokens", JsonArray(List(size) { JsonPrimitive(1) })) }.toString())
            }
            server.createContext("/completion") { e ->
                completions += Json.parseToJsonElement(e.requestBody.bufferedReader().readText()).jsonObject
                e.respond(buildJsonObject { put("content", recap); put("truncated", truncated); put("tokens_cached", 1); put("tokens_evaluated", 2) }.toString())
            }
            server.start()
        }
        fun request() = WarmupRequest("s", "m", server.address.port, "Scenario", listOf(ChatEntry("player", "look"), ChatEntry("game-master", "Adventure. ".repeat(1000))))
        override fun close() { server.stop(0) }
        private fun com.sun.net.httpserver.HttpExchange.respond(body: String) { val bytes = body.toByteArray(); sendResponseHeaders(200, bytes.size.toLong()); responseBody.use { it.write(bytes) } }
    }
}
