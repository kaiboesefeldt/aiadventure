package de.aidadventure.server.worldstate

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorldStateResolverTest {
    @Test fun `accepts a moved location occurring in the response`() = runBlocking {
        val replies = ArrayDeque(listOf("FINAL: move", "FINAL: Market Square"))
        val server = server(replies)
        try {
            assertEquals(WorldState("Market Square"), LlamaCppWorldStateResolver(server.address.port).resolve(request("She arrives at the Market Square.")))
        } finally { server.stop(0) }
    }
    @Test fun `initializes an empty location only from a response-backed location`() = runBlocking {
        server(ArrayDeque(listOf("FINAL: Hafen von Nebelhafen"))).let { server ->
            try {
                assertEquals(WorldState("Hafen von Nebelhafen"), LlamaCppWorldStateResolver(server.address.port).resolve(request("Du stehst am Rand des Hafen von Nebelhafen.", "")))
            } finally { server.stop(0) }
        }
        server(ArrayDeque(listOf("FINAL: Marktplatz"))).let { server ->
            try {
                assertNull(LlamaCppWorldStateResolver(server.address.port).resolve(request("Nebel zieht über das Wasser.", "")))
            } finally { server.stop(0) }
        }
    }
    @Test fun `bounds each completion request`() = runBlocking {
        var requestBody = ""
        val server = server(ArrayDeque(listOf("FINAL: stay"))) { requestBody = it }
        try {
            assertNull(LlamaCppWorldStateResolver(server.address.port).resolve(request("She remains at the harbour.")))
            val payload = Json.parseToJsonElement(requestBody).jsonObject
            assertEquals("32", payload["n_predict"]?.jsonPrimitive?.content)
            assertEquals("false", payload["stream"]?.jsonPrimitive?.content)
            assertEquals("root ::= \"FINAL: \" (\"move\" | \"stay\")", payload["grammar"]?.jsonPrimitive?.content)
        } finally { server.stop(0) }
    }
    @Test fun `keeps state for stay malformed final or absent location`() = runBlocking {
        listOf("FINAL: stay", "FINAL: move\nFINAL: stay", "FINAL: move").forEach { movement ->
            val server = server(ArrayDeque(listOf(movement, "FINAL: Elsewhere")))
            try {
                assertNull(LlamaCppWorldStateResolver(server.address.port).resolve(request("She remains at the harbour.")))
            } finally { server.stop(0) }
        }
    }
    private fun request(response: String, location: String = "Harbour") = WorldStateResolutionRequest("id", "# S", WorldState(location), "walk", response)
    private fun server(replies: ArrayDeque<String>, onRequest: (String) -> Unit = {}): HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/completion") { exchange ->
            onRequest(exchange.requestBody.bufferedReader().readText())
            val body = "{\"content\":${Json.encodeToString(replies.removeFirst())}}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }; start()
    }
}
