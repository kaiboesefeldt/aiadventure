package de.aidadventure.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlamaCppStartGuardTest {
    @Test
    fun `current completion clears pending and invalidates its request`() {
        val guard = LlamaCppStartGuard()
        val start = guard.begin()

        assertTrue(guard.pending)
        assertTrue(guard.complete(start))
        assertFalse(guard.pending)
        assertFalse(guard.accepts(start))
    }

    @Test
    fun `stale completion does not clear a newer pending start`() {
        val guard = LlamaCppStartGuard()
        val staleStart = guard.begin()
        val currentStart = guard.begin()

        assertFalse(guard.complete(staleStart))
        assertTrue(guard.pending)
        assertTrue(guard.accepts(currentStart))
    }

    @Test
    fun `current failure clears pending without accepting a stale request`() {
        val guard = LlamaCppStartGuard()
        val staleStart = guard.begin()
        val currentStart = guard.begin()

        assertFalse(guard.fail(staleStart))
        assertTrue(guard.pending)
        assertTrue(guard.fail(currentStart))
        assertFalse(guard.pending)
    }

    @Test
    fun `rejects a stale start completion after navigation`() {
        val guard = LlamaCppStartGuard()
        val start = guard.begin()

        assertTrue(guard.accepts(start))

        guard.invalidate()

        assertFalse(guard.accepts(start))
    }
}
