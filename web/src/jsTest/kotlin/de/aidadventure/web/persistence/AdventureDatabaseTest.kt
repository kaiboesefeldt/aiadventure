package de.aidadventure.web.persistence

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

class AdventureDatabaseTest {
    @Test
    fun `request failure waits for transaction abort`() = GlobalScope.promise<Unit> {
        val transaction = js("({})")
        val request = js("({ error: new Error('request failed') })")
        var returned = false
        var returnedBeforeAbort = false

        window.setTimeout({
            request.onerror(js("({})"))
            window.setTimeout({
                returnedBeforeAbort = returned
                transaction.onabort(js("({})"))
            }, 0)
        }, 0)

        try {
            awaitRequestAndTransaction(transaction, "reading scenarios", { request }) { Unit }
            error("Expected request failure")
        } catch (failure: Throwable) {
            assertIs<PersistenceError.DatabaseFailure>(failure)
        }
        returned = true
        assertFalse(returnedBeforeAbort, "Repository operation returned before the transaction aborted.")
    }
}
