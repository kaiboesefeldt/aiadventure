package de.aidadventure.server.worldstate

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorldStateResolverTest {
    @Test fun `asks llama cpp for schema constrained JSON and reads a patch`() = runBlocking {
        var requestBody = ""
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/completion") { exchange ->
                requestBody = exchange.requestBody.bufferedReader().readText()
                val body = "{\"content\":\"{\\\"baseRevision\\\":0,\\\"operations\\\":[]}\"}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val patch = LlamaCppWorldStateResolver(server.address.port).resolve(
                WorldStateResolutionRequest("session", "# Harbor", WorldState(), "wait", "Nothing changes."),
            )
            assertEquals(0, assertNotNull(patch).baseRevision)
            assertTrue(requestBody.contains("\"json_schema\""))
            assertFalse(requestBody.contains("\"grammar\""))
        } finally { server.stop(0) }
    }
}
