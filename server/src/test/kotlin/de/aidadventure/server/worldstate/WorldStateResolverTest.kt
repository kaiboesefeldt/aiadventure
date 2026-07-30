package de.aidadventure.server.worldstate

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
    @Test fun `keeps state for stay malformed final or absent location`() = runBlocking {
        listOf("FINAL: stay", "FINAL: move\nFINAL: stay", "FINAL: move").forEach { movement ->
            val server = server(ArrayDeque(listOf(movement, "FINAL: Elsewhere")))
            try {
                assertNull(LlamaCppWorldStateResolver(server.address.port).resolve(request("She remains at the harbour.")))
            } finally { server.stop(0) }
        }
    }
    private fun request(response: String) = WorldStateResolutionRequest("id", "# S", WorldState("Harbour"), "walk", response)
    private fun server(replies: ArrayDeque<String>): HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/completion") { exchange ->
            exchange.requestBody.close()
            val body = "{\"content\":${Json.encodeToString(replies.removeFirst())}}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }; start()
    }
}
