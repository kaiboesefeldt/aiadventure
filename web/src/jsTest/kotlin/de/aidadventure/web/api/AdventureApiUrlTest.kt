package de.aidadventure.web.api

import kotlin.test.Test
import kotlin.test.assertEquals

class AdventureApiUrlTest {
    @Test
    fun `development server routes API calls to the matching loopback API`() {
        assertEquals("http://localhost:8080", resolveAdventureApiBaseUrl("localhost", "8088"))
        assertEquals("http://127.0.0.1:8080", resolveAdventureApiBaseUrl("127.0.0.1", "8088"))
    }

    @Test
    fun `server-hosted UI keeps same-origin API paths`() {
        assertEquals("", resolveAdventureApiBaseUrl("localhost", "8080"))
        assertEquals("", resolveAdventureApiBaseUrl("127.0.0.1", "8080"))
    }
}
