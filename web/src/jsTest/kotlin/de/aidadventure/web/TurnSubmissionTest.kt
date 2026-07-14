package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.AdventureApiException
import de.aidadventure.web.api.TurnRequest
import de.aidadventure.web.api.TurnMetrics
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
                override suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics {
                    playCalls += 1
                    error("API must not be called when starting state cannot be persisted")
                }
            },
            clock = { 42L },
        )

        try {
            submission.submit(session(), scenario(), "look", { started = it }, {})
            error("Expected persistence failure")
        } catch (actual: Throwable) {
            assertEquals(failure, actual)
        }

        assertEquals(listOf("look"), started?.messages?.map { it.content })
        assertEquals(0, playCalls)
    }

    @Test
    fun `streams assistant text live but persists only the completed turn`() = GlobalScope.promise<Unit> {
        val streamed = mutableListOf<GameSession>()
        val sessions = recordingSessions()
        val submission = TurnSubmission(
            sessions = sessions,
            api = object : AdventureApi {
                override suspend fun models(): List<String> = emptyList()

                override suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics {
                    onToken("The ")
                    onToken("door opens.")
                    return TurnMetrics(null, 12, null)
                }
            },
            clock = { 42L },
        )

        submission.submit(session(), scenario(), "look", {}, streamed::add)

        assertEquals("The ", streamed[0].messages.last().content)
        assertEquals("The door opens.", streamed[1].messages.last().content)
        assertEquals(listOf(1, 2), sessions.saved.map { it.messages.size })
        assertEquals("game-master", sessions.saved.single { it.messages.size == 2 }.messages.last().role)
    }

    @Test
    fun `removes transient assistant text when streaming fails`() = GlobalScope.promise<Unit> {
        var started: GameSession? = null
        val streamed = mutableListOf<GameSession>()
        val sessions = recordingSessions()
        val submission = TurnSubmission(
            sessions = sessions,
            api = object : AdventureApi {
                override suspend fun models(): List<String> = emptyList()

                override suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics {
                    onToken("The ")
                    throw AdventureApiException("The model service is unavailable.")
                }
            },
            clock = { 42L },
        )

        try {
            submission.submit(session(), scenario(), "look", { started = it }, streamed::add)
            error("Expected streaming failure")
        } catch (actual: Throwable) {
            assertEquals("The model service is unavailable.", actual.message)
        }

        assertEquals(started, streamed.last())
        assertEquals(emptyList(), sessions.saved.flatMap { it.messages }.filter { it.role == "game-master" })
    }

    private fun session() = GameSession("session", "scenario", "Run", "model", emptyList(), 1, 1)

    private fun scenario() = Scenario("scenario", "Run.md", "# Run", 1)

    private fun failingSessions(failure: Throwable) = object : SessionRepository {
        override suspend fun list(): List<GameSession> = emptyList()
        override suspend fun save(session: GameSession) = throw failure
        override suspend fun find(id: String): GameSession? = null
    }

    private fun recordingSessions() = object : SessionRepository {
        val saved = mutableListOf<GameSession>()

        override suspend fun list(): List<GameSession> = emptyList()
        override suspend fun save(session: GameSession) {
            saved += session
        }
        override suspend fun find(id: String): GameSession? = null
    }
}
