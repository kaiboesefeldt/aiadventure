package de.aidadventure.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerRuntimeConfigTest {
    @Test
    fun `uses llama cpp port 8081 by default`() {
        assertEquals(8081, ServerRuntimeConfig.from(emptyMap(), emptyArray()).llamaCppPort)
    }

    @Test
    fun `uses the llama cpp port command line argument`() {
        assertEquals(8082, ServerRuntimeConfig.from(emptyMap(), arrayOf("--llama-port=8082")).llamaCppPort)
    }

    @Test
    fun `uses INFO as the default log level`() {
        assertEquals("info", ServerRuntimeConfig.from(emptyMap()).logLevel)
    }

    @Test
    fun `uses the requested log level case insensitively`() {
        assertEquals("trace", ServerRuntimeConfig.from(emptyMap(), arrayOf("--log-level=TRACE")).logLevel)
    }

    @Test
    fun `parses log level with llama cpp port in either order`() {
        val portThenLogLevel = ServerRuntimeConfig.from(
            emptyMap(),
            arrayOf("--llama-port=8082", "--log-level=DEBUG"),
        )
        assertEquals(8082, portThenLogLevel.llamaCppPort)
        assertEquals("debug", portThenLogLevel.logLevel)

        val logLevelThenPort = ServerRuntimeConfig.from(
            emptyMap(),
            arrayOf("--log-level=DEBUG", "--llama-port=8082"),
        )
        assertEquals(8082, logLevelThenPort.llamaCppPort)
        assertEquals("debug", logLevelThenPort.logLevel)
    }

    @Test
    fun `rejects invalid log level`() {
        assertFailsWith<IllegalArgumentException> {
            ServerRuntimeConfig.from(emptyMap(), arrayOf("--log-level=VERBOSE"))
        }
    }

    @Test
    fun `rejects unknown command line options`() {
        assertFailsWith<IllegalArgumentException> {
            ServerRuntimeConfig.from(emptyMap(), arrayOf("--unknown=value"))
        }
    }

    @Test
    fun `rejects duplicate log level option`() {
        assertFailsWith<IllegalArgumentException> {
            ServerRuntimeConfig.from(emptyMap(), arrayOf("--log-level=INFO", "--log-level=DEBUG"))
        }
    }

    @Test
    fun `rejects duplicate llama cpp port option`() {
        assertFailsWith<IllegalArgumentException> {
            ServerRuntimeConfig.from(emptyMap(), arrayOf("--llama-port=8081", "--llama-port=8082"))
        }
    }

    @Test
    fun `binds loopback by default`() {
        assertEquals("127.0.0.1", ServerRuntimeConfig.from(emptyMap()).host)
    }

    @Test
    fun `reads context compaction threshold from environment`() {
        assertEquals(72, ServerRuntimeConfig.from(mapOf("CONTEXT_COMPACTION_THRESHOLD" to "72")).contextCompactionThresholdPercent)
    }

    @Test
    fun `rejects invalid context compaction threshold`() {
        assertFailsWith<IllegalArgumentException> {
            ServerRuntimeConfig.from(mapOf("CONTEXT_COMPACTION_THRESHOLD" to "100"))
        }
    }

    @Test
    fun `rejects nonnumeric context compaction threshold`() {
        for (value in listOf("abc", "")) {
            assertFailsWith<IllegalArgumentException> {
                ServerRuntimeConfig.from(mapOf("CONTEXT_COMPACTION_THRESHOLD" to value))
            }
        }
    }

    @Test
    fun `binds all interfaces only with explicit LAN opt in`() {
        assertEquals("0.0.0.0", ServerRuntimeConfig.from(mapOf("ADVENTURE_EXPOSE_LAN" to "true")).host)
    }

    @Test
    fun `rejects invalid LAN opt in values`() {
        assertFailsWith<IllegalArgumentException> {
            ServerRuntimeConfig.from(mapOf("ADVENTURE_EXPOSE_LAN" to "yes"))
        }
    }
}
