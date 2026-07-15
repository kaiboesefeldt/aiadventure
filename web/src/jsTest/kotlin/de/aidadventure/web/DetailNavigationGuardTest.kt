package de.aidadventure.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailNavigationGuardTest {
    @Test
    fun `pending guarded Ollama start blocks detail route until current failure`() {
        val guard = LlamaCppStartGuard()
        val start = guard.tryBegin()!!

        assertFalse(canNavigateDetail(guard.pending))

        assertTrue(guard.fail(start))
        assertTrue(canNavigateDetail(guard.pending))
    }
}
