package de.aidadventure.web.persistence

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

class AdventureDatabaseTest {
    @Test
    fun `reopens a legacy database missing its object stores`() = GlobalScope.promise<Unit> {
        val databaseName = "ai-adventure-legacy-${js("Date.now()") as Double}"
        val indexedDb = js("globalThis.indexedDB")
        val legacyRequest = indexedDb.open(databaseName, 1)
        suspendCoroutine<Unit> { continuation ->
            legacyRequest.onsuccess = { _: dynamic ->
                legacyRequest.result.close()
                continuation.resume(Unit)
                null
            }
        }

        AdventureDatabase.open(databaseName).also { database ->
            assertEquals(emptyList(), database.scenarios.list())
            database.close()
        }
    }

    @Test
    fun `browser IndexedDB persists scenarios and sessions after database reopen`() = GlobalScope.promise<Unit> {
        val databaseName = "ai-adventure-test-${js("Date.now()") as Double}"
        val scenario = de.aidadventure.web.model.Scenario("scenario-1", "harbor.md", "# Harbor", 1)
        val session = de.aidadventure.web.model.GameSession(
            id = "session-1",
            scenarioId = scenario.id,
            title = "First voyage",
            modelId = "local-model",
            messages = emptyList(),
            createdAt = 1,
            updatedAt = 1,
        )

        AdventureDatabase.open(databaseName).also { database ->
            database.scenarios.save(scenario)
            database.sessions.save(session)
            assertEquals(listOf(scenario), database.scenarios.list())
            assertEquals(session, database.sessions.find(session.id))
            database.close()
        }

        AdventureDatabase.open(databaseName).also { reopened ->
            assertEquals(scenario, reopened.scenarios.find(scenario.id))
            assertEquals(listOf(session), reopened.sessions.list())
            reopened.close()
        }
    }

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
