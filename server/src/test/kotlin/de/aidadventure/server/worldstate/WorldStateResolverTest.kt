package de.aidadventure.server.worldstate

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorldStateResolverTest {
    @Test fun `asks llama cpp for schema constrained JSON and reads a proposal`() = runBlocking {
        var requestBody = ""
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/completion") { exchange ->
                requestBody = exchange.requestBody.bufferedReader().readText()
                val body = "{\"content\":\"{\\\"baseRevision\\\":0,\\\"entities\\\":[],\\\"facts\\\":[]}\"}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val proposal = LlamaCppWorldStateResolver(server.address.port).resolve(
                WorldStateResolutionRequest("session", "# Harbor", WorldState(), "wait", "Nothing changes."),
            )
            assertEquals(0, assertNotNull(proposal).baseRevision)
            assertTrue(requestBody.contains("\"json_schema\""))
            assertTrue(requestBody.contains("\"enum\":[0]"))
            assertTrue(requestBody.contains("\"entityName\""))
            assertFalse(requestBody.contains("\"operations\""))
            assertFalse(requestBody.contains("\"grammar\""))
        } finally { server.stop(0) }
    }

    @Test fun `reads a named entity proposal`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/completion") { exchange ->
                exchange.requestBody.close()
                val content = """{"baseRevision":0,"entities":[{"kind":"npc","name":"Harbourmaster","summary":"worried"}],"facts":[]}"""
                val body = """{"content":${Json.encodeToString(content)}}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val proposal = LlamaCppWorldStateResolver(server.address.port).resolve(
                WorldStateResolutionRequest("session", "# Harbor", WorldState(), "talk", "The Harbourmaster is worried."),
            )
            assertEquals("Harbourmaster", assertNotNull(proposal).entities.single().name)
        } finally { server.stop(0) }
    }
}
