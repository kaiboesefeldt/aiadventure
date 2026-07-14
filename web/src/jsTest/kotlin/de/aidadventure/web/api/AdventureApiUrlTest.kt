package de.aidadventure.web.api

import kotlin.test.Test
import kotlin.test.assertEquals

class AdventureApiUrlTest {
    @Test
    fun `default API base URL is same-origin`() {
        assertEquals("", AdventureApiClient.DEFAULT_BASE_URL)
    }
}
