package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.PrefillAccepted
import de.aidadventure.web.api.PrefillRequest
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.api.TurnCompletion
import de.aidadventure.web.api.TurnRequest
import de.aidadventure.web.api.WarmupRequest
import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.persistence.SessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

class TurnSubmissionTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `completed turn stores fragment immediately before its player action`() = GlobalScope.promise<Unit> {
        val api = FragmentEmittingAdventureApi()
        val sessions = RecordingSessions()
        val submission = TurnSubmission(sessions, api, clock = { 3 })

        val completed = submission.submit(sessionWithPreviousPair(), scenario(), "ask about lighthouse", {}, {})

        assertEquals(
            listOf("player", "game-master", "runtime-fragment", "player", "game-master"),
            completed.session.messages.map(GameMessage::role),
        )
        assertEquals("Mira warns the player", completed.session.messages[2].content)
        assertEquals(completed.session, sessions.saved.last())
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `failed turn leaves only the visible pending player in streamed state`() = GlobalScope.promise<Unit> {
        val sessions = RecordingSessions()
        val updates = mutableListOf<GameSession>()
        val submission = TurnSubmission(sessions, FailingFragmentAdventureApi(), clock = { 3 })

        try {
            submission.submit(sessionWithPreviousPair(), scenario(), "ask about lighthouse", {}, updates::add)
            error("Expected turn failure")
        } catch (failure: IllegalStateException) {
            assertEquals("model unavailable", failure.message)
        }

        assertEquals(listOf("player", "game-master", "player"), updates.last().messages.map(GameMessage::role))
        assertEquals(emptyList(), sessions.saved)
    }

    private fun sessionWithPreviousPair() = GameSession(
        id = "session-1", scenarioId = "scenario-1", title = "Harbor", modelId = "model",
        messages = listOf(GameMessage("player", "look", 1), GameMessage("game-master", "A bell rings", 2)),
        createdAt = 1, updatedAt = 2,
    )

    private fun scenario() = Scenario("scenario-1", "harbor.md", "# Harbor", 1)
}

private class FragmentEmittingAdventureApi : AdventureApi {
    override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
    override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
    override suspend fun play(request: TurnRequest, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
        onFragment("Mira warns the player")
        onToken("The keeper points east.")
        return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
    }
}

private class RecordingSessions : SessionRepository {
    val saved = mutableListOf<GameSession>()
    override suspend fun list(): List<GameSession> = saved
    override suspend fun save(session: GameSession) { saved += session }
    override suspend fun find(id: String): GameSession? = saved.lastOrNull { it.id == id }
    override suspend fun delete(id: String) = Unit
}

private class FailingFragmentAdventureApi : AdventureApi {
    override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
    override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
    override suspend fun play(request: TurnRequest, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
        onFragment("Mira warns the player")
        throw IllegalStateException("model unavailable")
    }
}
