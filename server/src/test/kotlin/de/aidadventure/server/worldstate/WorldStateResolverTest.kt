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
    @Test fun `accepts a schema-backed moved location occurring in the response`() = runBlocking {
        val server = server(ArrayDeque(listOf("{\"movement\":\"move\"}", "{\"location\":\"Market Square\"}")))
        try { assertEquals(WorldState("Market Square"), LlamaCppWorldStateResolver(server.address.port).resolve(request("She arrives at the Market Square."))) }
        finally { server.stop(0) }
    }
    @Test fun `initializes an empty location only when the schema location occurs in the response`() = runBlocking {
        server(ArrayDeque(listOf("{\"location\":\"Nebelhafen\"}"))).let { server ->
            try { assertEquals(WorldState("Nebelhafen"), LlamaCppWorldStateResolver(server.address.port).resolve(request("Du stehst am Rand des Hafen von Nebelhafen.", ""))) }
            finally { server.stop(0) }
        }
        server(ArrayDeque(listOf("{\"location\":\"Marktplatz\"}"))).let { server ->
            try { assertNull(LlamaCppWorldStateResolver(server.address.port).resolve(request("Nebel zieht über das Wasser.", ""))) }
            finally { server.stop(0) }
        }
    }
    @Test fun `sends movement schema to llama cpp`() = runBlocking {
        var requestBody = ""
        val server = server(ArrayDeque(listOf("{\"movement\":\"stay\"}"))) { requestBody = it }
        try {
            assertNull(LlamaCppWorldStateResolver(server.address.port).resolve(request("She remains at the harbour.")))
            val schema = Json.parseToJsonElement(requestBody).jsonObject["json_schema"]!!.jsonObject
            assertEquals("[\"movement\"]", schema["required"]!!.toString())
            assertEquals("[\"stay\",\"move\"]", schema["properties"]!!.jsonObject["movement"]!!.jsonObject["enum"]!!.toString())
        } finally { server.stop(0) }
    }
    @Test fun `limits the location schema to one short name`() = runBlocking {
        var requestBody = ""
        val server = server(ArrayDeque(listOf("{\"location\":\"Nebelhafen\"}"))) { requestBody = it }
        try {
            LlamaCppWorldStateResolver(server.address.port).resolve(request("Du stehst in Nebelhafen.", ""))
            val location = Json.parseToJsonElement(requestBody).jsonObject["json_schema"]!!.jsonObject["properties"]!!.jsonObject["location"]!!.jsonObject
            assertEquals("48", location["maxLength"]?.jsonPrimitive?.content)
        } finally { server.stop(0) }
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
