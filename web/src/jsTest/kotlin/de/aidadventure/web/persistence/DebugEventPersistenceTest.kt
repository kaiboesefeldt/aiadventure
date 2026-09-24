package de.aidadventure.web.persistence

import de.aidadventure.web.model.DebugEvent
import de.aidadventure.web.model.DiagnosticMetrics
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.test.Test
import kotlin.test.assertEquals

class DebugEventPersistenceTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `append lists events in turn sequence and isolates sessions after reopen`() = GlobalScope.promise<Unit> {
        val name = "debug-events-${kotlin.js.Date.now()}-${kotlin.random.Random.nextInt()}"
        val database = AdventureDatabase.open(name)
        val first = event("one", "session-a", "turn-a", 0, 10)
        val second = event("two", "session-a", "turn-a", 1, 9).copy(metrics = DiagnosticMetrics(cacheTokens = 7))
        val other = event("three", "session-b", "turn-b", 0, 11)
        try {
            database.debugEvents.append(second)
            database.debugEvents.append(other)
            database.debugEvents.append(first)
            assertEquals(listOf(first, second), database.debugEvents.listForSession("session-a"))
            assertEquals(listOf(other), database.debugEvents.listForSession("session-b"))
        } finally { database.close() }
        val reopened = AdventureDatabase.open(name)
        try {
            assertEquals(listOf(first, second), reopened.debugEvents.listForSession("session-a"))
            reopened.debugEvents.clearSession("session-a")
            assertEquals(emptyList(), reopened.debugEvents.listForSession("session-a"))
            assertEquals(listOf(other), reopened.debugEvents.listForSession("session-b"))
            reopened.debugEvents.deleteForSessions(setOf("session-b"))
            assertEquals(emptyList(), reopened.debugEvents.listForSession("session-b"))
        } finally { reopened.close(); js("globalThis.indexedDB").deleteDatabase(name) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `deleting a session removes only its events`() = GlobalScope.promise<Unit> {
        val name = "debug-delete-session-${kotlin.js.Date.now()}-${kotlin.random.Random.nextInt()}"
        val database = AdventureDatabase.open(name)
        try {
            database.sessions.save(session("session-a", "scenario-a"))
            database.sessions.save(session("session-b", "scenario-a"))
            database.debugEvents.append(event("one", "session-a", "turn-a", 0, 10))
            val other = event("two", "session-b", "turn-b", 0, 10)
            database.debugEvents.append(other)
            database.sessions.delete("session-a")
            assertEquals(emptyList(), database.debugEvents.listForSession("session-a"))
            assertEquals(listOf(other), database.debugEvents.listForSession("session-b"))
        } finally { database.close(); js("globalThis.indexedDB").deleteDatabase(name) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `deleting a scenario removes events for all its sessions`() = GlobalScope.promise<Unit> {
        val name = "debug-delete-scenario-${kotlin.js.Date.now()}-${kotlin.random.Random.nextInt()}"
        val database = AdventureDatabase.open(name)
        try {
            database.scenarios.save(Scenario("scenario-a", "A", "# A", 1))
            database.scenarios.save(Scenario("scenario-b", "B", "# B", 1))
            database.sessions.save(session("session-a1", "scenario-a"))
            database.sessions.save(session("session-a2", "scenario-a"))
            database.sessions.save(session("session-b", "scenario-b"))
            database.debugEvents.append(event("one", "session-a1", "turn-a1", 0, 10))
            database.debugEvents.append(event("two", "session-a2", "turn-a2", 0, 10))
            val surviving = event("three", "session-b", "turn-b", 0, 10)
            database.debugEvents.append(surviving)
            database.scenarios.delete("scenario-a")
            assertEquals(emptyList(), database.debugEvents.listForSession("session-a1"))
            assertEquals(emptyList(), database.debugEvents.listForSession("session-a2"))
            assertEquals(listOf(surviving), database.debugEvents.listForSession("session-b"))
        } finally { database.close(); js("globalThis.indexedDB").deleteDatabase(name) }
    }

    private fun event(id: String, sessionId: String, turnId: String, sequence: Int, createdAt: Long) = DebugEvent(
        id, sessionId, turnId, sequence, createdAt, "turn_status", "started", mapOf("source" to "prior_turn"), null, "player",
    )

    private fun session(id: String, scenarioId: String) = GameSession(id, scenarioId, id, messages = emptyList(), createdAt = 1, updatedAt = 1)
}
