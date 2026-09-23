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

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `introduction keeps hidden start instruction in every subsequent request history`() = GlobalScope.promise<Unit> {
        val sessions = RecordingSessions()
        val scenario = Scenario("scenario-1", "harbor.md", "---\ninitial-situation:\n  setting: The harbor-master greets you.\n---\n# Harbor", 1)
        val initial = newGameSession(scenario, "session-1", 1)
        val api = FragmentEmittingAdventureApi()
        val completed = TurnSubmission(sessions, api, clock = { 3 })
            .submitIntroduction(initial, scenario, {}, {}).session

        assertEquals("The harbor-master greets you.", api.requests.single().initialSituation)
        assertEquals(false, completed.introductionPending)
        assertEquals(listOf("runtime-fragment", "player", "game-master"), completed.messages.map { it.role })
        assertEquals(true, completed.messages[1].hidden)
        assertEquals(false, isVisibleTranscriptMessage(completed.messages[1]))
        assertEquals(listOf("The keeper points east."), completed.messages.filter(::isVisibleTranscriptMessage).map { it.content })
        val expected = completed.messages.map { de.aidadventure.web.api.ChatEntry(it.role, it.content) }
        assertEquals(expected, de.aidadventure.web.api.TurnRequestMapper.map(completed, scenario, "look", completed.id).history)
        assertEquals(expected, de.aidadventure.web.api.PrefillRequestMapper.map(completed, scenario, completed.id, 1, "look").history)
        assertEquals(expected, de.aidadventure.web.api.WarmupRequestMapper.map(completed, scenario).history)
        assertEquals(completed, de.aidadventure.web.persistence.decodePersistedGameSession(
            kotlinx.serialization.json.Json.encodeToString(GameSession.serializer(), completed),
        ))
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `failed introduction restores original pending session without saving partial history`() = GlobalScope.promise<Unit> {
        val sessions = RecordingSessions()
        val initial = newGameSession(scenario(), "session-1", 1)
        val updates = mutableListOf<GameSession>()
        try {
            TurnSubmission(sessions, FailingFragmentAdventureApi()).submitIntroduction(initial, scenario(), {}, updates::add)
            error("Expected failure")
        } catch (failure: IllegalStateException) {
            assertEquals("model unavailable", failure.message)
        }
        assertEquals(initial, updates.last())
        assertEquals(emptyList(), sessions.saved)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `empty introduction is rejected and remains retryable`() = GlobalScope.promise<Unit> {
        val sessions = RecordingSessions()
        val initial = newGameSession(scenario(), "session-1", 1)
        val updates = mutableListOf<GameSession>()
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
                onToken("  ")
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            }
        }
        try {
            TurnSubmission(sessions, api).submitIntroduction(initial, scenario(), {}, updates::add)
            error("Expected failure")
        } catch (failure: IllegalStateException) {
            assertEquals("The introduction was empty. Please try again.", failure.message)
        }
        assertEquals(initial, updates.last())
        assertEquals(emptyList(), sessions.saved)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `failed introduction save restores retryable session`() = GlobalScope.promise<Unit> {
        val initial = newGameSession(scenario(), "session-1", 1)
        val updates = mutableListOf<GameSession>()
        val sessions = object : SessionRepository {
            override suspend fun list() = listOf(initial)
            override suspend fun save(session: GameSession): Unit = error("storage unavailable")
            override suspend fun find(id: String) = initial
            override suspend fun delete(id: String) = Unit
        }
        try {
            TurnSubmission(sessions, FragmentEmittingAdventureApi()).submitIntroduction(initial, scenario(), {}, updates::add)
            error("Expected failure")
        } catch (failure: IllegalStateException) {
            assertEquals("storage unavailable", failure.message)
        }
        assertEquals(initial, updates.last())
    }

    @Test
    fun `legacy empty sessions remain playable without automatic introduction`() {
        val legacy = de.aidadventure.web.persistence.decodePersistedGameSession(
            """{"id":"old","scenarioId":"scenario-1","title":"Old","messages":[],"createdAt":1,"updatedAt":1}""",
        )
        assertEquals(false, legacy.introductionPending)
        val legacyMessage = kotlinx.serialization.json.Json.decodeFromString(
            GameMessage.serializer(), """{"role":"player","content":"look","createdAt":1}""",
        )
        assertEquals(true, isVisibleTranscriptMessage(legacyMessage))
    }

    private fun sessionWithPreviousPair() = GameSession(
        id = "session-1", scenarioId = "scenario-1", title = "Harbor", modelId = "model",
        messages = listOf(GameMessage("player", "look", 1), GameMessage("game-master", "A bell rings", 2)),
        createdAt = 1, updatedAt = 2,
    )

    private fun scenario() = Scenario("scenario-1", "harbor.md", "# Harbor", 1)
}

private class FragmentEmittingAdventureApi : AdventureApi {
    val requests = mutableListOf<TurnRequest>()
    override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
    override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
    override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
        requests += request
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
    override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
        onFragment("Mira warns the player")
        throw IllegalStateException("model unavailable")
    }
}
