package de.aidadventure.web.api

import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(DelicateCoroutinesApi::class)
class AdventureApiUrlTest {
    @Test
    fun `all API requests use same-origin paths including on port 8088`() = GlobalScope.promise<Unit> {
        assertRequestUrls(AdventureApiClient(), "")
    }

    @Test
    fun `explicit API base URL is respected`() = GlobalScope.promise<Unit> {
        assertRequestUrls(AdventureApiClient("http://localhost:9090/"), "http://localhost:9090")
    }

    private suspend fun assertRequestUrls(client: AdventureApiClient, expectedBaseUrl: String) {
        val originalFetch = window.asDynamic().fetch
        val urls = mutableListOf<String>()
        window.asDynamic().fetch = { url: String, _: dynamic ->
            urls += url
            val response = js("({status: 503, ok: false})")
            if (url.contains("/api/turn/background?")) response.status = 404
            response.text = { Promise.resolve("{\"message\":\"Test service unavailable\"}") }
            Promise.resolve<dynamic>(response)
        }
        try {
            assertFailsWith<AdventureApiException> {
                client.prefill(PrefillRequest("session", 1, "scenario", emptyList(), "action"))
            }
            assertFailsWith<AdventureApiException> {
                client.warmup(WarmupRequest("session", "scenario", emptyList()))
            }
            assertFailsWith<AdventureApiException> {
                client.play(TurnRequest("scenario", emptyList(), "action", "session"), {}, {}, {})
            }
            assertEquals(null, client.awaitBackgroundDiagnostic("session /", "operation+?"))
            assertEquals(
                listOf("/api/prefill", "/api/warmup", "/api/turn", "/api/turn/background?sessionId=session%20%2F&operationId=operation%2B%3F").map { "$expectedBaseUrl$it" },
                urls,
            )
            if (expectedBaseUrl.isEmpty()) {
                for (origin in listOf("http://localhost:8088", "http://127.0.0.1:8088", "https://example.org:9443")) {
                    for (url in urls) {
                        val resolved = js("new URL(url, origin)")
                        assertEquals(origin, resolved.origin as String)
                    }
                }
            }
        } finally {
            window.asDynamic().fetch = originalFetch
        }
    }
}
