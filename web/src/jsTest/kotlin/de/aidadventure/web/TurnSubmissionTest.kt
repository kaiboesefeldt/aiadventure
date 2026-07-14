package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.TurnRequest
import de.aidadventure.web.api.TurnResponse
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.persistence.SessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

class TurnSubmissionTest {
    @Test
    fun `start persistence failure retains player message and does not call api`() = GlobalScope.promise<Unit> {
        var started: GameSession? = null
        var playCalls = 0
        val failure = IllegalStateException("IndexedDB unavailable")

        val submission = TurnSubmission(
            sessions = failingSessions(failure),
            api = object : AdventureApi {
                override suspend fun models(): List<String> = emptyList()
                override suspend fun play(request: TurnRequest): TurnResponse {
                    playCalls += 1
                    error("API must not be called when starting state cannot be persisted")
                }
            },
            clock = { 42L },
        )

        try {
            submission.submit(session(), scenario(), "look") { started = it }
            error("Expected persistence failure")
        } catch (actual: Throwable) {
            assertEquals(failure, actual)
        }

        assertEquals(listOf("look"), started?.messages?.map { it.content })
        assertEquals(0, playCalls)
    }

    private fun session() = GameSession("session", "scenario", "Run", "model", emptyList(), 1, 1)

    private fun scenario() = Scenario("scenario", "Run.md", "# Run", 1)

    private fun failingSessions(failure: Throwable) = object : SessionRepository {
        override suspend fun list(): List<GameSession> = emptyList()
        override suspend fun save(session: GameSession) = throw failure
        override suspend fun find(id: String): GameSession? = null
    }
}
