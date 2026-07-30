package de.aidadventure.server.worldstate

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CancellationException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldStateResolverTest {
    @Test fun `accepts a schema-backed moved location occurring in the response`() = runBlocking {
        val server = server(ArrayDeque(listOf("{\"movement\":\"move\"}", "{\"location\":\"Market Square\"}", "{\"activeThread\":\"Find the lighthouse saboteur\"}")))
        try { assertEquals(WorldState("Market Square", "Find the lighthouse saboteur"), LlamaCppWorldStateResolver(server.address.port).resolve(request("She arrives at the Market Square."))) }
        finally { server.stop(0) }
    }
    @Test fun `resolves a thread after an initial location even when the previous location is blank`() = runBlocking {
        val server = server(ArrayDeque(listOf("{\"location\":\"Nebelhafen\"}", "{\"activeThread\":\"Find the lighthouse saboteur\"}")))
        try { assertEquals(WorldState("Nebelhafen", "Find the lighthouse saboteur"), LlamaCppWorldStateResolver(server.address.port).resolve(request("Du stehst am Rand des Hafen von Nebelhafen.", ""))) }
        finally { server.stop(0) }
    }
    @Test fun `retains the previous thread when the resolved thread exceeds 96 characters`() = runBlocking {
        val tooLong = "x".repeat(97)
        val server = server(ArrayDeque(listOf("{\"movement\":\"move\"}", "{\"location\":\"Market Square\"}", "{\"activeThread\":\"$tooLong\"}")))
        try { assertEquals(WorldState("Market Square", "Existing thread"), LlamaCppWorldStateResolver(server.address.port).resolve(request("She arrives at the Market Square.", activeThread = "Existing thread"))) }
        finally { server.stop(0) }
    }
    @Test fun `retains the previous thread when the thread response is malformed`() = runBlocking {
        val server = server(ArrayDeque(listOf("{\"movement\":\"move\"}", "{\"location\":\"Market Square\"}", "{\"thread\":\"wrong field\"}")))
        try { assertEquals(WorldState("Market Square", "Existing thread"), LlamaCppWorldStateResolver(server.address.port).resolve(request("She arrives at the Market Square.", activeThread = "Existing thread"))) }
        finally { server.stop(0) }
    }
    @Test fun `keeps the previous state when both independent resolutions are invalid`() = runBlocking {
        val server = server(ArrayDeque(listOf("{\"movement\":\"stay\"}", "{\"thread\":\"wrong field\"}")))
        try { assertNull(LlamaCppWorldStateResolver(server.address.port).resolve(request("Nothing changes.", activeThread = "Existing thread"))) }
        finally { server.stop(0) }
    }
    @Test fun `rejects a numeric choice as a story thread`() = runBlocking {
        val server = server(ArrayDeque(listOf("{\"movement\":\"stay\"}", "{\"activeThread\":\"2\"}")))
        try { assertNull(LlamaCppWorldStateResolver(server.address.port).resolve(request("The game master offers options 1 to 4.", activeThread = "Existing thread"))) }
        finally { server.stop(0) }
    }
    @Test fun `rejects a thread result with an extra property`() = runBlocking {
        val server = server(ArrayDeque(listOf("{\"movement\":\"move\"}", "{\"location\":\"Market Square\"}", "{\"activeThread\":\"New duty\",\"extra\":\"reject\"}")))
        try { assertEquals(WorldState("Market Square", "Existing thread"), LlamaCppWorldStateResolver(server.address.port).resolve(request("She arrives at the Market Square.", activeThread = "Existing thread"))) }
        finally { server.stop(0) }
    }
    @Test fun `accepts unknown outer completion fields`() = runBlocking {
        val server = server(ArrayDeque(listOf("{\"movement\":\"stay\"}", "{\"activeThread\":\"\"}")), outerExtra = ",\"id_slot\":3")
        try { assertNull(LlamaCppWorldStateResolver(server.address.port).resolve(request("She remains at the harbour."))) }
        finally { server.stop(0) }
    }
    @Test fun `propagates cancellation from an interrupted completion request`() {
        val client = mockk<HttpClient>()
        every { client.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } throws InterruptedException("cancelled")

        val failure = assertFailsWith<CancellationException> {
            runBlocking { LlamaCppWorldStateResolver(8081, client).resolve(request("She remains at the harbour.")) }
        }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is InterruptedException })
    }
    @Test fun `retains a resolved location when the thread request fails`() = runBlocking {
        val server = server(ArrayDeque(listOf("{\"movement\":\"move\"}", "{\"location\":\"Market Square\"}")))
        try { assertEquals(WorldState("Market Square", "Existing thread"), LlamaCppWorldStateResolver(server.address.port).resolve(request("She arrives at the Market Square.", activeThread = "Existing thread"))) }
        finally { server.stop(0) }
    }
    @Test fun `a valid empty thread clears the previous thread`() = runBlocking {
        val server = server(ArrayDeque(listOf("{\"movement\":\"stay\"}", "{\"activeThread\":\"\"}")))
        try { assertEquals(WorldState("Harbour"), LlamaCppWorldStateResolver(server.address.port).resolve(request("She remains at the harbour.", activeThread = "Existing thread"))) }
        finally { server.stop(0) }
    }
    @Test fun `initializes an empty location only when the schema location occurs in the response`() = runBlocking {
        server(ArrayDeque(listOf("{\"location\":\"Nebelhafen\"}", "{\"activeThread\":\"\"}"))).let { server ->
            try { assertEquals(WorldState("Nebelhafen"), LlamaCppWorldStateResolver(server.address.port).resolve(request("Du stehst am Rand des Hafen von Nebelhafen.", ""))) }
            finally { server.stop(0) }
        }
        server(ArrayDeque(listOf("{\"location\":\"Marktplatz\"}", "{\"activeThread\":\"Open duty\"}"))).let { server ->
            try { assertEquals(WorldState(activeThread = "Open duty"), LlamaCppWorldStateResolver(server.address.port).resolve(request("Nebel zieht über das Wasser.", ""))) }
            finally { server.stop(0) }
        }
    }
    @Test fun `normalizes trailing punctuation before accepting an initial location`() = runBlocking {
        val server = server(ArrayDeque(listOf("{\"location\":\"Nebelhafen,\"}", "{\"activeThread\":\"\"}")))
        try { assertEquals(WorldState("Nebelhafen"), LlamaCppWorldStateResolver(server.address.port).resolve(request("Du stehst in Nebelhafen, am Kai.", ""))) }
        finally { server.stop(0) }
    }
    @Test fun `sends movement schema to llama cpp`() = runBlocking {
        val requests = mutableListOf<String>()
        val server = server(ArrayDeque(listOf("{\"movement\":\"stay\"}", "{\"activeThread\":\"\"}"))) { requests += it }
        try {
            assertNull(LlamaCppWorldStateResolver(server.address.port).resolve(request("She remains at the harbour.")))
            val schema = Json.parseToJsonElement(requests.first()).jsonObject["json_schema"]!!.jsonObject
            assertEquals("[\"movement\"]", schema["required"]!!.toString())
            assertEquals("[\"stay\",\"move\"]", schema["properties"]!!.jsonObject["movement"]!!.jsonObject["enum"]!!.toString())
        } finally { server.stop(0) }
    }
    @Test fun `limits the location schema to one short name`() = runBlocking {
        val requests = mutableListOf<String>()
        val server = server(ArrayDeque(listOf("{\"location\":\"Nebelhafen\"}", "{\"activeThread\":\"\"}"))) { requests += it }
        try {
            LlamaCppWorldStateResolver(server.address.port).resolve(request("Du stehst in Nebelhafen.", ""))
            val location = Json.parseToJsonElement(requests.first()).jsonObject["json_schema"]!!.jsonObject["properties"]!!.jsonObject["location"]!!.jsonObject
            assertEquals("48", location["maxLength"]?.jsonPrimitive?.content)
        } finally { server.stop(0) }
    }
    @Test fun `thread schema permits clearing or a phrase of three to 96 characters`() = runBlocking {
        val requests = mutableListOf<String>()
        val server = server(ArrayDeque(listOf("{\"movement\":\"stay\"}", "{\"activeThread\":\"\"}"))) { requests += it }
        try {
            LlamaCppWorldStateResolver(server.address.port).resolve(request("She remains at the harbour."))
            val thread = Json.parseToJsonElement(requests.last()).jsonObject["json_schema"]!!.jsonObject["properties"]!!.jsonObject["activeThread"]!!.jsonObject
            val alternatives = thread["anyOf"]!!.jsonArray
            assertEquals("\"\"", alternatives[0].jsonObject["const"].toString())
            assertEquals("3", alternatives[1].jsonObject["minLength"]?.jsonPrimitive?.content)
            assertEquals("96", alternatives[1].jsonObject["maxLength"]?.jsonPrimitive?.content)
            assertFalse(Json.parseToJsonElement(requests.last()).jsonObject.containsKey("n_predict"))
        } finally { server.stop(0) }
    }
    private fun request(response: String, location: String = "Harbour", activeThread: String = "") = WorldStateResolutionRequest("id", "# S", WorldState(location, activeThread), "walk", response)
    private fun server(replies: ArrayDeque<String>, outerExtra: String = "", onRequest: (String) -> Unit = {}): HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/completion") { exchange ->
            onRequest(exchange.requestBody.bufferedReader().readText())
            if (replies.isEmpty()) {
                exchange.close()
                return@createContext
            }
            val body = "{\"content\":${Json.encodeToString(replies.removeFirst())}$outerExtra}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }; start()
    }
}
