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
        val checked = LlamaCppConnectionState.initial().discovered("local-model")

        assertEquals("local-model", checked.modelId)
        assertFalse(checked.portEditable)
        assertTrue(checked.startOnClick)
    }

    @Test
    fun `pending session start rejects a duplicate start`() {
        val checked = LlamaCppConnectionState.initial().discovered("local-model")

        assertTrue(canStartLlamaCppSession(checked, pending = false))
        assertFalse(canStartLlamaCppSession(checked, pending = true))
    }

    @Test
    fun `failed discovery makes the port editable again`() {
        val failed = LlamaCppConnectionState.initial()
            .discovered("local-model")
            .failed("service unavailable")

        assertTrue(failed.portEditable)
        assertEquals("service unavailable", failed.error)
        assertFalse(failed.startOnClick)
        assertFalse(canStartLlamaCppSession(failed, pending = false))
    }

    @Test
    fun `reopened session rejects a different discovered model without replacing its model`() {
        val rechecked = LlamaCppConnectionState.initial(port = 8081, modelId = "saved-model")
            .withPortText("8082")
            .discovered("different-model")

        assertEquals("8082", rechecked.portText)
        assertEquals("saved-model", rechecked.modelId)
        assertTrue(rechecked.portEditable)
        assertFalse(rechecked.startOnClick)
        assertEquals(
            "The discovered llama.cpp model \"different-model\" does not match this saved session's model \"saved-model\".",
            rechecked.error,
        )
    }

    @Test
    fun `reopened session accepts a matching discovered model`() {
        val rechecked = LlamaCppConnectionState.initial(port = 8081, modelId = "saved-model")
            .withPortText("8082")
            .discovered("saved-model")

        assertEquals("saved-model", rechecked.modelId)
        assertFalse(rechecked.portEditable)
        assertTrue(rechecked.startOnClick)
    }

    @Test
    fun `new session adopts its first discovered model`() {
        val checked = LlamaCppConnectionState.initial().discovered("first-model")

        assertEquals("first-model", checked.modelId)
        assertFalse(checked.portEditable)
        assertTrue(checked.startOnClick)
    }

    @Test
    fun `rejects a discovery result after navigating away from its scenario`() {
        val guard = LlamaCppDiscoveryGuard()
        guard.activate("scenario-a")
        val requestForScenarioA = guard.begin("scenario-a")

        guard.activate("scenario-b")
        assertFalse(guard.accepts(requestForScenarioA))

        guard.activate(null)
        assertFalse(guard.accepts(requestForScenarioA))
    }
}
