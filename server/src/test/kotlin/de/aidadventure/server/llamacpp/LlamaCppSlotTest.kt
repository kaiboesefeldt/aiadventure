package de.aidadventure.server.llamacpp

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class LlamaCppSlotTest {
    @Test
    fun `acquires an idle unleased slot and erases it before leasing`() {
        slotServer(
            """
            [
              {"id":0,"is_processing":true,"id_task":-1},
              {"id":1,"is_processing":true,"id_task":42},
              {"id":2,"is_processing":false,"id_task":-1}
            ]
            """.trimIndent(),
        ).use { server ->
            val gateway = LlamaCppGateway()

            assertEquals(LlamaCppSlot(2), gateway.acquireIdleSlot(server.port))
            assertEquals(listOf("GET /slots", "POST /slots/2?action=erase"), server.requests)
        }
    }

    @Test
    fun `acquires an idle slot when llama cpp omits task id`() {
        slotServer(
            """
            [
              {"id":0,"n_ctx":16384,"is_processing":false}
            ]
            """.trimIndent(),
        ).use { server ->
            val gateway = LlamaCppGateway()

            assertEquals(LlamaCppSlot(0), gateway.acquireIdleSlot(server.port))
            assertEquals(listOf("GET /slots", "POST /slots/0?action=erase"), server.requests)
        }
    }

    @Test
    fun `acquires a completed slot that retains its task id`() {
        slotServer(
            """
            [
              {"id":1,"is_processing":false,"id_task":42}
            ]
            """.trimIndent(),
        ).use { server ->
            val gateway = LlamaCppGateway()

            assertEquals(LlamaCppSlot(1), gateway.acquireIdleSlot(server.port))
            assertEquals(listOf("GET /slots", "POST /slots/1?action=erase"), server.requests)
        }
    }

    @Test
    fun `does not erase an idle slot excluded by process lease ownership`() {
        slotServer(
            """
            [
              {"id":1,"is_processing":false,"id_task":-1},
              {"id":2,"is_processing":false,"id_task":-1}
            ]
            """.trimIndent(),
        ).use { server ->
            val gateway = LlamaCppGateway()

            assertEquals(
                LlamaCppSlot(2),
                gateway.acquireIdleSlot(server.port, setOf(LlamaCppSlot(1))),
            )
            assertEquals(listOf("GET /slots", "POST /slots/2?action=erase"), server.requests)
        }
    }

    @Test
    fun `releasing a slot erases it once`() {
        slotServer("[]").use { server ->
            LlamaCppGateway().releaseSlot(server.port, LlamaCppSlot(2))

            assertEquals(listOf("POST /slots/2?action=erase"), server.requests)
        }
    }

    private fun slotServer(slotsResponse: String): SlotServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        return SlotServer(server, slotsResponse).also { it.start() }
    }

    private class SlotServer(
        private val server: HttpServer,
        private val slotsResponse: String,
    ) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        val requests = mutableListOf<String>()
        val port: Int get() = server.address.port

        init {
            server.executor = executor
            server.createContext("/slots") { exchange ->
                requests += "${exchange.requestMethod} ${exchange.requestURI.path}" +
                    exchange.requestURI.query?.let { "?$it" }.orEmpty()
                exchange.requestBody.close()
                if (exchange.requestMethod == "GET") {
                    exchange.respondJson(slotsResponse)
                } else {
                    exchange.respondJson("{\"id_slot\":2,\"n_erased\":0}")
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
