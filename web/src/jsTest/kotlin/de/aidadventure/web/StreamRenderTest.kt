package de.aidadventure.web

import kotlin.test.Test
import kotlin.test.assertTrue

class StreamRenderTest {
    @Test
    fun `stream update runs before the callback returns`() {
        var updated = false

        flushStreamUpdate { updated = true }

        assertTrue(updated)
    }
}
