package de.aidadventure.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlamaCppConnectionStateTest {
    @Test
    fun `initial connection uses the default editable port`() {
        val initial = LlamaCppConnectionState.initial()

        assertEquals("8081", initial.portText)
        assertTrue(initial.portEditable)
    }

    @Test
    fun `successful discovery locks the port and enables session start`() {
        val checked = LlamaCppConnectionState.initial().connected("local-model")

        assertEquals("local-model", checked.modelId)
        assertFalse(checked.portEditable)
        assertTrue(checked.startOnClick)
    }

    @Test
    fun `failed discovery makes the port editable again`() {
        val failed = LlamaCppConnectionState.initial()
            .connected("local-model")
            .failed("service unavailable")

        assertTrue(failed.portEditable)
        assertEquals("service unavailable", failed.error)
    }
}
