package de.aidadventure.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlamaCppStartGuardTest {
    @Test
    fun `rejects a stale start completion after navigation`() {
        val guard = LlamaCppStartGuard()
        val start = guard.begin()

        assertTrue(guard.accepts(start))

        guard.invalidate()

        assertFalse(guard.accepts(start))
    }
}
