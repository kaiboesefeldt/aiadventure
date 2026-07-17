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
    fun `binds loopback by default`() {
        assertEquals("127.0.0.1", ServerRuntimeConfig.from(emptyMap()).host)
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
