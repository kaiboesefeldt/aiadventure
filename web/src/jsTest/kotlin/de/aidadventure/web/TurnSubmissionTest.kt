package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.PrefillAccepted
import de.aidadventure.web.api.PrefillRequest
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.api.TurnCompletion
import de.aidadventure.web.api.TurnRequest
import de.aidadventure.web.api.WarmupRequest
import de.aidadventure.web.api.TurnRequestMapper
import de.aidadventure.web.api.TurnStreamStoppedException
import de.aidadventure.web.model.DebugEvent
import de.aidadventure.web.model.DiagnosticMetrics
import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.model.StreamDiagnostic
import de.aidadventure.web.persistence.DebugEventRepository
import de.aidadventure.web.persistence.SessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class TurnSubmissionTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `scheduled background outcome is fetched after turn and linked to its output`() = GlobalScope.promise<Unit> {
        val sessions = RecordingSessions()
        val events = RecordingDebugEvents()
        val outcomeSaved = CompletableDeferred<DebugEvent>()
        events.onAppend = { if (it.stage == "prior_completed") outcomeSaved.complete(it) }
        val operationId = "operation-1"
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("Use diagnostic overload")
            override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
                onToken("SECRET_OUTPUT")
                onDiagnostic(StreamDiagnostic("background_rerank", "scheduled", mapOf("operationId" to operationId), "game-master"))
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            }
            override suspend fun awaitBackgroundDiagnostic(sessionId: String, operationId: String): StreamDiagnostic? {
                assertEquals("session-1", sessionId)
                assertEquals("operation-1", operationId)
                assertEquals(1, sessions.saved.size)
                return StreamDiagnostic("background_rerank", "prior_completed", mapOf("operationId" to operationId, "source" to "prior_turn", "result" to "accepted"), "player", DiagnosticMetrics(cacheTokens = 7))
            }
        }
        val completed = TurnSubmission(sessions, api, debugEvents = events)
            .submit(sessionWithPreviousPair(), scenario(), "SECRET_ACTION", {}, {})
        val outcome = withTimeout(1000) { outcomeSaved.await() }
        val scheduled = events.saved.single { it.stage == "scheduled" }
        assertEquals(scheduled.turnId, outcome.turnId)
        assertEquals(scheduled.turnOrder, outcome.turnOrder)
        assertEquals(scheduled.transcriptMessageId, outcome.transcriptMessageId)
        assertEquals(completed.session.messages.last().messageId, outcome.transcriptMessageId)
        assertEquals(scheduled.transcriptIndex, outcome.transcriptIndex)
        assertEquals("prior_turn", outcome.fields["source"])
        assertEquals(7, outcome.metrics?.cacheTokens)
        assertFalse(outcome.toString().contains("SECRET_ACTION"))
        assertFalse(outcome.toString().contains("SECRET_OUTPUT"))
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `later duplicate background outcome is deduped by operation id`() = GlobalScope.promise<Unit> {
        val sessions = RecordingSessions()
        val events = RecordingDebugEvents()
        val outcomeSaved = CompletableDeferred<Unit>()
        events.onAppend = { if (it.stage == "prior_completed") outcomeSaved.complete(Unit) }
        val operationId = "operation-2"
        var turns = 0
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("Use diagnostic overload")
            override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
                if (turns++ == 0) {
                    onToken("First output")
                    onDiagnostic(StreamDiagnostic("background_rerank", "scheduled", mapOf("operationId" to operationId), "game-master"))
                } else {
                    onDiagnostic(StreamDiagnostic("background_rerank", "prior_completed", mapOf("operationId" to operationId, "source" to "prior_turn"), "player"))
                    onToken("Second output")
                }
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            }
            override suspend fun awaitBackgroundDiagnostic(sessionId: String, operationId: String): StreamDiagnostic? =
                StreamDiagnostic("background_rerank", "prior_completed", mapOf("operationId" to operationId, "source" to "prior_turn"), "player")
        }
        val first = TurnSubmission(sessions, api, debugEvents = events)
            .submit(sessionWithPreviousPair(), scenario(), "first", {}, {}).session
        withTimeout(1000) { outcomeSaved.await() }
        TurnSubmission(sessions, api, debugEvents = events).submit(first, scenario(), "second", {}, {})
        yield()
        val outcomes = events.saved.filter { it.kind == "background_rerank" && it.stage == "prior_completed" && it.fields["operationId"] == operationId }
        assertEquals(1, outcomes.size)
        assertEquals(events.saved.single { it.stage == "scheduled" }.turnId, outcomes.single().turnId)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `later turn outcome wins when follow-up is still waiting`() = GlobalScope.promise<Unit> {
        val sessions = RecordingSessions()
        val events = RecordingDebugEvents()
        val followupStarted = CompletableDeferred<Unit>()
        val releaseFollowup = CompletableDeferred<StreamDiagnostic?>()
        val operationId = "operation-3"
        var turns = 0
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("Use diagnostic overload")
            override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
                if (turns++ == 0) {
                    onToken("First output")
                    onDiagnostic(StreamDiagnostic("background_rerank", "scheduled", mapOf("operationId" to operationId), "game-master"))
                } else {
                    onDiagnostic(StreamDiagnostic("background_rerank", "prior_completed", mapOf("operationId" to operationId, "source" to "prior_turn"), "player"))
                    onToken("Second output")
                }
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            }
            override suspend fun awaitBackgroundDiagnostic(sessionId: String, operationId: String): StreamDiagnostic? {
                followupStarted.complete(Unit)
                return releaseFollowup.await()
            }
        }
        val first = TurnSubmission(sessions, api, debugEvents = events)
            .submit(sessionWithPreviousPair(), scenario(), "first", {}, {}).session
        withTimeout(1000) { followupStarted.await() }
        TurnSubmission(sessions, api, debugEvents = events).submit(first, scenario(), "second", {}, {})
        releaseFollowup.complete(StreamDiagnostic("background_rerank", "prior_completed", mapOf("operationId" to operationId, "source" to "prior_turn"), "player"))
        yield()
        assertEquals(1, events.saved.count { it.kind == "background_rerank" && it.stage == "prior_completed" && it.fields["operationId"] == operationId })
        assertEquals(events.saved.single { it.stage == "scheduled" }.turnId, events.saved.single { it.stage == "prior_completed" }.turnId)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `later turn outcome retains origin while scheduled write is pending`() = GlobalScope.promise<Unit> {
        val sessions = RecordingSessions()
        val scheduledWrite = CompletableDeferred<Unit>()
        val operationId = "operation-pending"
        val saved = mutableListOf<DebugEvent>()
        val outcomeSaved = CompletableDeferred<DebugEvent>()
        val events = object : DebugEventRepository {
            override suspend fun listForSession(sessionId: String): List<DebugEvent> = saved.filter { it.sessionId == sessionId }
            override suspend fun append(event: DebugEvent) {
                if (event.stage == "scheduled") scheduledWrite.await()
                saved += event
                if (event.stage == "prior_completed") outcomeSaved.complete(event)
            }
            override suspend fun clearSession(sessionId: String) = Unit
            override suspend fun deleteForSessions(sessionIds: Set<String>) = Unit
        }
        var turns = 0
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("Use diagnostic overload")
            override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
                if (turns++ == 0) {
                    onToken("First output")
                    onDiagnostic(StreamDiagnostic("background_rerank", "scheduled", mapOf("operationId" to operationId), "game-master"))
                } else {
                    onDiagnostic(StreamDiagnostic("background_rerank", "prior_completed", mapOf("operationId" to operationId, "source" to "prior_turn"), "player"))
                    onToken("Second output")
                }
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            }
        }
        try {
            val first = TurnSubmission(sessions, api, debugEvents = events)
                .submit(sessionWithPreviousPair(), scenario(), "first", {}, {}).session
            assertTrue(saved.none { it.stage == "scheduled" })
            TurnSubmission(sessions, api, debugEvents = events).submit(first, scenario(), "second", {}, {})
            val outcome = withTimeout(1000) { outcomeSaved.await() }
            assertEquals(first.messages.last().messageId, outcome.transcriptMessageId)
            assertEquals("game-master", outcome.transcriptRole)
            assertEquals(first.messages.count(::isVisibleTranscriptMessage) - 1, outcome.transcriptIndex)
        } finally { scheduledWrite.complete(Unit) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `new submission recovers pending background result after reload`() = GlobalScope.promise<Unit> {
        val sessions = RecordingSessions()
        val events = RecordingDebugEvents()
        val outcomeSaved = CompletableDeferred<DebugEvent>()
        events.onAppend = { if (it.stage == "prior_completed") outcomeSaved.complete(it) }
        val operationId = "operation-reload"
        val scheduled = DebugEvent(
            id = "old-turn-5", sessionId = "session-1", turnId = "old-turn", sequence = 5,
            createdAt = 5, kind = "background_rerank", stage = "scheduled",
            fields = mapOf("operationId" to operationId), transcriptRole = "game-master",
            turnOrder = 4, transcriptIndex = 1, transcriptMessageId = "saved-output",
        )
        events.saved += scheduled
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("Use diagnostic overload")
            override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
                onToken("New output")
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            }
            override suspend fun awaitBackgroundDiagnostic(sessionId: String, operationId: String): StreamDiagnostic? =
                StreamDiagnostic("background_rerank", "prior_completed", mapOf("operationId" to operationId, "source" to "prior_turn"), "player")
        }
        TurnSubmission(sessions, api, debugEvents = events).submit(sessionWithPreviousPair(), scenario(), "new action", {}, {})
        val recovered = withTimeout(1000) { outcomeSaved.await() }
        assertEquals(scheduled.turnId, recovered.turnId)
        assertEquals(scheduled.transcriptMessageId, recovered.transcriptMessageId)
        assertEquals(scheduled.transcriptIndex, recovered.transcriptIndex)
        assertEquals(1, events.saved.count { it.stage == "prior_completed" && it.fields["operationId"] == operationId })
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `completed turn persists flags returned by server for the following turn`() = GlobalScope.promise<Unit> {
        val scenario = Scenario("scenario-1", "harbor.md", """
            ---
            flags:
              has-map: false
            signals:
              found-map:
                triggers:
                  - set-flag:
                      has-map: true
            ---
            # Harbor
        """.trimIndent(), 1)
        val initial = newGameSession(scenario, "session-1", 1)
        val sessions = RecordingSessions()
        var submittedFlags: Map<String, Boolean>? = null
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
                submittedFlags = request.flags
                onToken("A map lies on the table.")
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState(), flags = mapOf("has-map" to true))
            }
        }

        val completed = TurnSubmission(sessions, api).submit(initial.copy(flags = emptyMap()), scenario, "look", {}, {}).session
        assertEquals(mapOf("has-map" to false), submittedFlags)

        assertEquals(mapOf("has-map" to false), submittedFlags)
        assertEquals(mapOf("has-map" to true), completed.flags)
        assertEquals(completed.flags, TurnRequestMapper.map(completed, scenario, "continue", completed.id).flags)
        assertEquals(completed, sessions.saved.single())
    }

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

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `completed turn persists diagnostics with one turn id and ordered sequences`() = GlobalScope.promise<Unit> {
        val events = RecordingDebugEvents()
        val api = DiagnosticAdventureApi(DiagnosticOutcome.COMPLETED)
        TurnSubmission(RecordingSessions(), api, clock = { 42 }, debugEvents = events)
            .submit(sessionWithPreviousPair(), scenario(), "SECRET_PLAYER_ACTION", {}, {})
        assertEquals(listOf(0, 1), events.saved.map { it.sequence })
        assertEquals(1, events.saved.map { it.turnId }.distinct().size)
        assertEquals("session-1", events.saved.single { it.sequence == 0 }.sessionId)
        assertEquals("prior_turn", events.saved.last().fields["source"])
        assertEquals(7, events.saved.last().metrics?.cacheTokens)
        assertEquals("player", events.saved.last().transcriptRole)
        assertEquals(listOf(2, 0), events.saved.map { it.transcriptIndex })
        assertFalse(events.saved.toString().contains("SECRET_PLAYER_ACTION"))
        assertFalse(events.saved.toString().contains("SECRET_GAME_MASTER_OUTPUT"))
        TurnSubmission(RecordingSessions(), api, clock = { 42 }, debugEvents = events)
            .submit(sessionWithPreviousPair(), scenario(), "another action", {}, {})
        assertEquals(2, events.saved.map { it.turnId }.distinct().size)
        assertEquals(listOf(0, 1, 0, 1), events.saved.map { it.sequence })
        assertEquals(events.saved[0].turnOrder + 1, events.saved[2].turnOrder)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `failed stream retains received diagnostics without saving transcript`() = GlobalScope.promise<Unit> {
        val events = RecordingDebugEvents()
        val sessions = RecordingSessions()
        try {
            TurnSubmission(sessions, DiagnosticAdventureApi(DiagnosticOutcome.FAILED), debugEvents = events)
                .submit(sessionWithPreviousPair(), scenario(), "SECRET_PLAYER_ACTION", {}, {})
            error("Expected failure")
        } catch (failure: IllegalStateException) {
            assertEquals("stream failed", failure.message)
        }
        assertEquals(2, events.saved.size)
        assertEquals(emptyList(), sessions.saved)
        assertEquals(listOf(2, 0), events.saved.map { it.transcriptIndex })
        assertFalse(events.saved.toString().contains("SECRET_PLAYER_ACTION"))
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `stopped stream retains received diagnostics and partial game master turn`() = GlobalScope.promise<Unit> {
        val events = RecordingDebugEvents()
        val sessions = RecordingSessions()
        val completed = TurnSubmission(sessions, DiagnosticAdventureApi(DiagnosticOutcome.STOPPED), debugEvents = events)
            .submit(sessionWithPreviousPair(), scenario(), "SECRET_PLAYER_ACTION", {}, {})
        assertEquals(2, events.saved.size)
        assertEquals(listOf(2, 0), events.saved.map { it.transcriptIndex })
        assertEquals("SECRET_GAME_MASTER_OUTPUT", completed.session.messages.last().content)
        assertTrue(completed.session.messages.last().messageId != null)
        assertEquals(completed.session, sessions.saved.single())
        assertTrue(events.saved.none { it.toString().contains("SECRET_GAME_MASTER_OUTPUT") })
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `resumed history links prior turn diagnostics to the prior visible player`() = GlobalScope.promise<Unit> {
        val events = RecordingDebugEvents()
        val previous = sessionWithPreviousPair().copy(messages = listOf(
            GameMessage("player", "hidden introduction", 1, hidden = true),
            GameMessage("game-master", "Welcome", 2),
            GameMessage("player", "visit pier", 3, messageId = "prior-player"),
            GameMessage("game-master", "At the pier", 4),
        ))
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("Use diagnostic overload")
            override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
                onDiagnostic(StreamDiagnostic("background_rerank", "prior_completed", mapOf("source" to "prior_turn"), transcriptRole = "player"))
                onDiagnostic(StreamDiagnostic("turn_state", "initial", transcriptRole = "player"))
                onDiagnostic(StreamDiagnostic("turn_status", "started", transcriptRole = "game-master"))
                onToken("New output")
                onDiagnostic(StreamDiagnostic("turn_status", "completed", transcriptRole = "game-master"))
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            }
        }
        TurnSubmission(RecordingSessions(), api, debugEvents = events).submit(previous, scenario(), "new action", {}, {})
        assertEquals(listOf(1, 3, null, 4), events.saved.map { it.transcriptIndex })
        assertEquals("prior-player", events.saved.first().transcriptMessageId)
        assertFalse(events.saved.toString().contains("visit pier"))
        assertFalse(events.saved.toString().contains("new action"))
        assertFalse(events.saved.toString().contains("New output"))
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `introduction diagnostics never link hidden player input`() = GlobalScope.promise<Unit> {
        val events = RecordingDebugEvents()
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("Use diagnostic overload")
            override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
                onDiagnostic(StreamDiagnostic("turn_state", "initial", transcriptRole = "player"))
                onToken("Welcome")
                onDiagnostic(StreamDiagnostic("flags", "applied", transcriptRole = "player"))
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            }
        }
        val completed = TurnSubmission(RecordingSessions(), api, debugEvents = events)
            .submitIntroduction(newGameSession(scenario(), "session-1", 1), scenario(), {}, {}).session
        assertEquals(listOf(null, 0), events.saved.map { it.transcriptIndex })
        assertEquals(null, events.saved.first().transcriptRole)
        assertEquals(null, events.saved.first().transcriptMessageId)
        assertEquals("game-master", events.saved.last().transcriptRole)
        assertEquals(completed.messages.last().messageId, events.saved.last().transcriptMessageId)
        assertFalse(completed.messages.first().messageId == events.saved.last().transcriptMessageId)
        assertFalse(events.saved.toString().contains(TurnSubmission.INTRODUCTION_INSTRUCTION))
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `next turn prior diagnostic links resumed introduction output`() = GlobalScope.promise<Unit> {
        val events = RecordingDebugEvents()
        var calls = 0
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("Use diagnostic overload")
            override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
                if (calls++ == 0) onToken("Welcome") else {
                    onDiagnostic(StreamDiagnostic("background_rerank", "prior_completed", mapOf("source" to "prior_turn"), transcriptRole = "player"))
                    onToken("You enter the harbor")
                }
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            }
        }
        val submission = TurnSubmission(RecordingSessions(), api, debugEvents = events)
        val introduction = submission.submitIntroduction(newGameSession(scenario(), "session-1", 1), scenario(), {}, {}).session
        val resumed = de.aidadventure.web.persistence.decodePersistedGameSession(
            kotlinx.serialization.json.Json.encodeToString(GameSession.serializer(), introduction),
        )
        submission.submit(resumed, scenario(), "look", {}, {})
        val prior = events.saved.single { it.fields["source"] == "prior_turn" }
        assertEquals(0, prior.transcriptIndex)
        assertEquals("game-master", prior.transcriptRole)
        assertEquals(resumed.messages.last().messageId, prior.transcriptMessageId)
        assertFalse(prior.transcriptMessageId == resumed.messages.first().messageId)
        assertFalse(prior.toString().contains("Welcome"))
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `failed introduction retry leaves prior diagnostic linked only to saved output`() = GlobalScope.promise<Unit> {
        val events = RecordingDebugEvents()
        var calls = 0
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("Use diagnostic overload")
            override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
                when (calls++) {
                    0 -> {
                        onToken("Discarded welcome")
                        onDiagnostic(StreamDiagnostic("turn_status", "started", transcriptRole = "game-master"))
                        error("first introduction failed")
                    }
                    1 -> onToken("Saved welcome")
                    else -> {
                        onDiagnostic(StreamDiagnostic("background_rerank", "prior_completed", mapOf("source" to "prior_turn"), transcriptRole = "player"))
                        onToken("Next response")
                    }
                }
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            }
        }
        val submission = TurnSubmission(RecordingSessions(), api, debugEvents = events)
        val initial = newGameSession(scenario(), "session-1", 1)
        runCatching { submission.submitIntroduction(initial, scenario(), {}, {}) }
            .onSuccess { error("Expected introduction failure") }
        val saved = submission.submitIntroduction(initial, scenario(), {}, {}).session
        val resumed = de.aidadventure.web.persistence.decodePersistedGameSession(
            kotlinx.serialization.json.Json.encodeToString(GameSession.serializer(), saved),
        )
        submission.submit(resumed, scenario(), "look", {}, {})
        val stale = events.saved.first()
        val prior = events.saved.single { it.fields["source"] == "prior_turn" }
        assertFalse(stale.transcriptMessageId == resumed.messages.last().messageId)
        assertEquals(resumed.messages.last().messageId, prior.transcriptMessageId)
        assertEquals("game-master", prior.transcriptRole)
        assertEquals(0, prior.transcriptIndex)
        assertFalse(events.saved.toString().contains("Discarded welcome"))
        assertFalse(events.saved.toString().contains("Saved welcome"))
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `runtime fragment inserted after an early diagnostic does not shift its visible transcript index`() = GlobalScope.promise<Unit> {
        val events = RecordingDebugEvents()
        val streamed = mutableListOf<GameSession>()
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("Use diagnostic overload")
            override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
                onDiagnostic(StreamDiagnostic("turn_state", "initial", transcriptRole = "player"))
                onFragment("Hidden retrieved context")
                onToken("Visible ")
                onToken("answer")
                onDiagnostic(StreamDiagnostic("turn_status", "completed", transcriptRole = "game-master"))
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            }
        }
        val completed = TurnSubmission(RecordingSessions(), api, debugEvents = events)
            .submit(sessionWithPreviousPair(), scenario(), "new action", {}, streamed::add).session
        assertEquals(listOf(2, 3), events.saved.map { it.transcriptIndex })
        assertEquals(listOf("player", "game-master", "player", "game-master"), completed.messages.filter(::isVisibleTranscriptMessage).map { it.role })
        assertEquals(1, streamed.mapNotNull { it.messages.lastOrNull()?.takeIf { message -> message.role == "game-master" }?.messageId }.distinct().size)
        assertEquals(completed.messages.last().messageId, streamed.last().messages.last().messageId)
        assertFalse(events.saved.toString().contains("Hidden retrieved context"))
    }

    @Test
    fun `legacy debug event decodes without a transcript index`() {
        val event = kotlinx.serialization.json.Json.decodeFromString(
            DebugEvent.serializer(),
            """{"id":"old","sessionId":"session-1","turnId":"turn-1","sequence":0,"createdAt":1,"kind":"turn_status","stage":"completed"}""",
        )
        assertEquals(null, event.transcriptIndex)
        assertEquals(null, event.transcriptMessageId)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `failed turn retry gives its player a new durable identity`() = GlobalScope.promise<Unit> {
        verifyRetryDoesNotReuseMessageId(cancelled = false)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `cancelled turn before output retry gives its player a new durable identity`() = GlobalScope.promise<Unit> {
        verifyRetryDoesNotReuseMessageId(cancelled = true)
    }

    private suspend fun verifyRetryDoesNotReuseMessageId(cancelled: Boolean) {
        val events = RecordingDebugEvents()
        val sessions = RecordingSessions()
        val base = sessionWithPreviousPair()
        var attempts = 0
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("Use diagnostic overload")
            override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
                onDiagnostic(StreamDiagnostic("turn_state", "initial", transcriptRole = "player"))
                if (attempts++ == 0) {
                    if (cancelled) throw TurnStreamStoppedException()
                    error("model unavailable")
                }
                onToken("New response")
                onDiagnostic(StreamDiagnostic("turn_status", "completed", transcriptRole = "game-master"))
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            }
        }
        val submission = TurnSubmission(sessions, api, debugEvents = events)
        runCatching { submission.submit(base, scenario(), "first action", {}, {}) }
            .onSuccess { error("Expected first turn to stop") }
        assertEquals(emptyList(), sessions.saved)
        val completed = submission.submit(base, scenario(), "retry action", {}, {}).session
        val resumed = de.aidadventure.web.persistence.decodePersistedGameSession(
            kotlinx.serialization.json.Json.encodeToString(GameSession.serializer(), completed),
        )
        val oldId = events.saved.first().transcriptMessageId
        val player = resumed.messages.last { it.role == "player" }
        val response = resumed.messages.last()
        assertTrue(oldId != null)
        assertTrue(player.messageId != null)
        assertTrue(response.messageId != null)
        assertFalse(oldId == player.messageId)
        assertFalse(resumed.messages.any { it.messageId == oldId })
        assertEquals(player.messageId, events.saved[1].transcriptMessageId)
        assertEquals(response.messageId, events.saved[2].transcriptMessageId)
        assertFalse(events.saved.toString().contains("first action"))
        assertFalse(events.saved.toString().contains("retry action"))
        assertFalse(events.saved.toString().contains("New response"))
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `diagnostic persistence starts before stream completion`() = GlobalScope.promise<Unit> {
        val events = RecordingDebugEvents()
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
            override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("Use diagnostic overload")
            override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
                onDiagnostic(StreamDiagnostic("turn_status", "started"))
                assertEquals(1, events.saved.size)
                onToken("response")
                return TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            }
        }
        TurnSubmission(RecordingSessions(), api, debugEvents = events)
            .submit(sessionWithPreviousPair(), scenario(), "look", {}, {})
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `failed diagnostic write does not abort a successful turn`() = GlobalScope.promise<Unit> {
        val sessions = RecordingSessions()
        val writeFailures = mutableListOf<String?>()
        val events = object : DebugEventRepository {
            override suspend fun listForSession(sessionId: String): List<DebugEvent> = emptyList()
            override suspend fun append(event: DebugEvent): Unit = error("IndexedDB write failed")
            override suspend fun clearSession(sessionId: String) = Unit
            override suspend fun deleteForSessions(sessionIds: Set<String>) = Unit
        }

        val completed = TurnSubmission(sessions, DiagnosticAdventureApi(DiagnosticOutcome.COMPLETED), debugEvents = events,
            onDiagnosticPersistenceFailure = { writeFailures += it.message })
            .submit(sessionWithPreviousPair(), scenario(), "look", {}, {})

        assertEquals(completed.session, sessions.saved.single())
        assertEquals("SECRET_GAME_MASTER_OUTPUT", completed.session.messages.last().content)
        assertEquals(listOf<String?>("IndexedDB write failed", "IndexedDB write failed"), writeFailures)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `slow diagnostic write does not delay canonical turn persistence`() = GlobalScope.promise<Unit> {
        val releaseWrite = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        val sessions = RecordingSessions()
        val events = object : DebugEventRepository {
            override suspend fun listForSession(sessionId: String): List<DebugEvent> = emptyList()
            override suspend fun append(event: DebugEvent) {
                writeStarted.complete(Unit)
                releaseWrite.await()
            }
            override suspend fun clearSession(sessionId: String) = Unit
            override suspend fun deleteForSessions(sessionIds: Set<String>) = Unit
        }
        try {
            val completed = withTimeout(500) {
                TurnSubmission(sessions, DiagnosticAdventureApi(DiagnosticOutcome.COMPLETED), debugEvents = events)
                    .submit(sessionWithPreviousPair(), scenario(), "look", {}, {})
            }
            assertEquals(completed.session, sessions.saved.single())
            assertTrue(writeStarted.isCompleted)
        } finally {
            releaseWrite.complete(Unit)
        }
    }

    private fun sessionWithPreviousPair() = GameSession(
        id = "session-1", scenarioId = "scenario-1", title = "Harbor", modelId = "model",
        messages = listOf(GameMessage("player", "look", 1), GameMessage("game-master", "A bell rings", 2)),
        createdAt = 1, updatedAt = 2,
    )

    private fun scenario() = Scenario("scenario-1", "harbor.md", "# Harbor", 1)
}

private enum class DiagnosticOutcome { COMPLETED, FAILED, STOPPED }

private class DiagnosticAdventureApi(private val outcome: DiagnosticOutcome) : AdventureApi {
    override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unused")
    override suspend fun warmup(request: WarmupRequest): PrefillAccepted = error("unused")
    override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("Use diagnostic overload")
    override suspend fun play(request: TurnRequest, onGenerationReady: () -> Unit, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit, onDiagnostic: (StreamDiagnostic) -> Unit): TurnCompletion {
        onDiagnostic(StreamDiagnostic("turn_status", "started", transcriptRole = "player"))
        onToken("SECRET_GAME_MASTER_OUTPUT")
        onDiagnostic(StreamDiagnostic("background_rerank", "prior_completed", mapOf("source" to "prior_turn"), "game-master", DiagnosticMetrics(cacheTokens = 7)))
        return when (outcome) {
            DiagnosticOutcome.COMPLETED -> TurnCompletion(TurnMetrics(null, null, null), de.aidadventure.web.model.WorldState())
            DiagnosticOutcome.FAILED -> throw IllegalStateException("stream failed")
            DiagnosticOutcome.STOPPED -> throw TurnStreamStoppedException()
        }
    }
}

private class RecordingDebugEvents : DebugEventRepository {
    val saved = mutableListOf<DebugEvent>()
    var onAppend: ((DebugEvent) -> Unit)? = null
    override suspend fun listForSession(sessionId: String): List<DebugEvent> = saved.filter { it.sessionId == sessionId }
    override suspend fun append(event: DebugEvent) { saved += event; onAppend?.invoke(event) }
    override suspend fun clearSession(sessionId: String) { saved.removeAll { it.sessionId == sessionId } }
    override suspend fun deleteForSessions(sessionIds: Set<String>) { saved.removeAll { it.sessionId in sessionIds } }
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
