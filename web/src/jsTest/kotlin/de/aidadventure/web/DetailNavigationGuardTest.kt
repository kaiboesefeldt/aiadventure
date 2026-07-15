package de.aidadventure.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailNavigationGuardTest {
    @Test
    fun `pending llama cpp start blocks detail navigation and competing actions`() {
        assertTrue(canNavigateDetail(pending = false))
        assertFalse(canNavigateDetail(pending = true))
    }
}
