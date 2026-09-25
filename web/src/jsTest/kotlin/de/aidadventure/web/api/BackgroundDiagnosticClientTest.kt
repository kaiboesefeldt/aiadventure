package de.aidadventure.web.api

import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(DelicateCoroutinesApi::class)
class BackgroundDiagnosticClientTest {
    @Test
    fun `follow-up GET decodes one diagnostic across stream chunks`() = GlobalScope.promise<Unit> {
        val originalFetch = window.asDynamic().fetch
        val encoder = js("new TextEncoder()")
        val chunks = listOf(
            "event: diagnostic\ndata: {\"kind\":\"background_rerank\",\"stage\":\"prior_completed\",\"fields\":{\"operationId\":\"op-1\",",
            "\"source\":\"prior_turn\"},\"metrics\":{\"cacheTokens\":9}}\n\n",
        )
        var index = 0
        val reader = js("({})")
        reader.read = {
            val result = js("({})")
            result.done = index == chunks.size
            if (index < chunks.size) result.value = encoder.encode(chunks[index++])
            Promise.resolve<dynamic>(result)
        }
        val response = js("({status: 200, ok: true})")
        val body = js("({})")
        body.getReader = { reader }
        response.body = body
        window.asDynamic().fetch = { _: String -> Promise.resolve<dynamic>(response) }
        try {
            val diagnostic = AdventureApiClient().awaitBackgroundDiagnostic("session-1", "op-1")
            assertEquals("prior_completed", diagnostic?.stage)
            assertEquals("op-1", diagnostic?.fields?.get("operationId"))
            assertEquals("prior_turn", diagnostic?.fields?.get("source"))
            assertEquals(9, diagnostic?.metrics?.cacheTokens)
        } finally {
            window.asDynamic().fetch = originalFetch
        }
    }
}
