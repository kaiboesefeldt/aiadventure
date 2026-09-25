package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.api.TurnRequestMapper
import de.aidadventure.web.api.TurnStreamStoppedException
import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.DebugEvent
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.model.StreamDiagnostic
import de.aidadventure.web.persistence.SessionRepository
import de.aidadventure.web.persistence.DebugEventRepository
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class CompletedTurn(val session: GameSession, val metrics: TurnMetrics)

internal class TurnSubmission(
    private val sessions: SessionRepository,
    private val api: AdventureApi,
    private val clock: () -> Long = ::now,
    private val debugEvents: DebugEventRepository? = null,
    private val onDiagnosticPersistenceFailure: (Throwable) -> Unit = {
        window.asDynamic().console.warn("Could not save diagnostic event")
    },
    private val onBackgroundDiagnosticFailure: (Throwable) -> Unit = {
        window.asDynamic().console.warn("Could not retrieve background diagnostic")
    },
) {
    private val diagnosticWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun recoverPendingBackground(sessionId: String) = recoverScheduledOutcomes(sessionId)

    suspend fun submitIntroduction(
        session: GameSession,
        scenario: Scenario,
        onStarted: (GameSession) -> Unit,
        onStreamed: (GameSession) -> Unit,
    ): CompletedTurn {
        require(session.introductionPending && session.messages.isEmpty())
        return try {
            submit(session, scenario, INTRODUCTION_INSTRUCTION, onStarted, onStreamed, introduction = true)
        } catch (cause: Throwable) {
            onStreamed(session)
            throw cause
        }
    }

    suspend fun submit(
        session: GameSession,
        scenario: Scenario,
        playerAction: String,
        onStarted: (GameSession) -> Unit,
        onStreamed: (GameSession) -> Unit,
        onInputReady: () -> Unit = {},
        onTokenStarted: () -> Unit = {},
        liveSessionId: String = session.id,
        introduction: Boolean = false,
        onGenerationReady: () -> Unit = {},
    ): CompletedTurn {
        val turnOrder = nextTurnId++
        val turnId = "turn-${clock()}-$turnOrder"
        recoverScheduledOutcomes(session.id)
        val messageBaseId = "$turnId-${kotlin.random.Random.nextLong()}"
        val playerMessageId = "$messageBaseId-player"
        val gameMasterMessageId = "$messageBaseId-game-master"
        var nextSequence = 0
        val priorPlayerRawIndex = session.messages.indexOfLast { it.role == "player" && isVisibleTranscriptMessage(it) }
        val hiddenIntroductionRawIndex = session.messages.indexOfFirst { it.role == "player" && it.hidden }
        val priorIntroductionOutputRawIndex = if (priorPlayerRawIndex >= 0 || hiddenIntroductionRawIndex < 0) -1
        else session.messages.indices.firstOrNull { index ->
            index > hiddenIntroductionRawIndex && session.messages[index].role == "game-master" && isVisibleTranscriptMessage(session.messages[index])
        } ?: -1
        val priorMessageRawIndex = if (priorPlayerRawIndex >= 0) priorPlayerRawIndex else priorIntroductionOutputRawIndex
        val priorMessage = session.messages.getOrNull(priorMessageRawIndex)
        val priorMessageIndex = priorMessageRawIndex
            .takeIf { it >= 0 }
            ?.let { rawIndex -> session.messages.take(rawIndex).count(::isVisibleTranscriptMessage) }
        val currentPlayerIndex = session.messages.count(::isVisibleTranscriptMessage)
        val started = session.copy(
            messages = session.messages + GameMessage("player", playerAction, clock(), hidden = introduction, messageId = playerMessageId),
            updatedAt = clock(),
        )
        onStarted(started)

        val streamedText = StringBuilder()
        var messages = started.messages
        val scheduledBackgroundEvents = mutableListOf<DebugEvent>()
        val completion = try {
            api.play(TurnRequestMapper.map(session, scenario, playerAction, liveSessionId, includeInitialSituation = introduction), onGenerationReady, onInputReady, { fragment ->
                messages = messages.dropLast(1) + GameMessage("runtime-fragment", fragment, clock()) + messages.takeLast(1)
                onStreamed(started.copy(messages = messages, updatedAt = clock()))
            }, { token ->
                onTokenStarted()
                streamedText.append(token)
                onStreamed(
                    started.copy(
                        messages = messages + GameMessage("game-master", streamedText.toString(), clock(), messageId = gameMasterMessageId),
                        updatedAt = clock(),
                    ),
                )
            }, { diagnostic ->
                val repository = debugEvents
                if (repository != null) {
                    val sequence = nextSequence++
                    val priorTurn = diagnostic.fields["source"] == "prior_turn"
                    val outputExists = streamedText.isNotEmpty()
                    val transcriptIndex = when {
                        priorTurn -> priorMessageIndex
                        introduction -> if (outputExists) currentPlayerIndex else null
                        diagnostic.transcriptRole == "player" -> currentPlayerIndex
                        diagnostic.transcriptRole == "game-master" && outputExists -> currentPlayerIndex + 1
                        else -> null
                    }
                    val transcriptRole = when {
                        priorTurn -> priorMessage?.role
                        introduction && transcriptIndex != null -> "game-master"
                        introduction -> null
                        else -> diagnostic.transcriptRole
                    }
                    val transcriptMessageId = when {
                        priorTurn -> priorMessage?.messageId
                        introduction -> if (outputExists) gameMasterMessageId else null
                        diagnostic.transcriptRole == "player" -> playerMessageId
                        diagnostic.transcriptRole == "game-master" && outputExists -> gameMasterMessageId
                        else -> null
                    }
                    val event = DebugEvent(
                        id = "$turnId-$sequence", sessionId = session.id, turnId = turnId,
                        sequence = sequence, createdAt = clock(), kind = diagnostic.kind,
                        stage = diagnostic.stage, fields = diagnostic.fields,
                        metrics = diagnostic.metrics, transcriptRole = transcriptRole,
                        turnOrder = turnOrder,
                        transcriptIndex = transcriptIndex,
                        transcriptMessageId = transcriptMessageId,
                    )
                    val operationId = diagnostic.fields["operationId"]?.takeIf(String::isNotBlank)
                    when {
                        diagnostic.kind == "background_rerank" && diagnostic.stage == "scheduled" && operationId != null -> {
                            scheduledBackgroundEvents += event
                            val key = session.id to operationId
                            scheduledBackgroundEventsPending[key] = event
                            appendDiagnostic(repository, event) { scheduledBackgroundEventsPending.remove(key) }
                        }
                        diagnostic.kind == "background_rerank" && diagnostic.stage.startsWith("prior_") && operationId != null -> {
                            enqueueBackgroundOutcome(repository, session.id, operationId, diagnostic, event)
                        }
                        else -> appendDiagnostic(repository, event)
                    }
                }
            })
        } catch (stopped: TurnStreamStoppedException) {
            if (streamedText.isEmpty()) throw stopped
            val partial = started.copy(
                messages = messages + GameMessage("game-master", streamedText.toString(), clock(), messageId = gameMasterMessageId),
                updatedAt = clock(),
            )
            sessions.save(partial)
            startBackgroundFollowups(session.id, scheduledBackgroundEvents)
            return CompletedTurn(partial, TurnMetrics(null, null, null))
        } catch (cause: Throwable) {
            onStreamed(started)
            throw cause
        }
        val completed = started.copy(
            messages = messages + GameMessage("game-master", streamedText.toString(), clock(), messageId = gameMasterMessageId),
            updatedAt = clock(),
            worldState = completion.worldState,
            contextCompression = completion.contextCompression,
            flags = completion.flags,
            introductionPending = if (introduction) false else session.introductionPending,
        )
        if (introduction) check(streamedText.isNotBlank()) { "The introduction was empty. Please try again." }
        sessions.save(completed)
        startBackgroundFollowups(session.id, scheduledBackgroundEvents)
        return CompletedTurn(completed, completion.metrics)
    }

    private fun appendDiagnostic(repository: DebugEventRepository, event: DebugEvent, onSaved: () -> Unit = {}) {
        diagnosticWriteScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                repository.append(event)
                onSaved()
            } catch (failure: Throwable) {
                if (!currentCoroutineContext().isActive) throw failure
                runCatching { onDiagnosticPersistenceFailure(failure) }
            }
        }
    }

    private fun startBackgroundFollowups(sessionId: String, scheduledEvents: List<DebugEvent>) {
        val repository = debugEvents ?: return
        scheduledEvents.forEach { scheduled ->
            val operationId = scheduled.fields["operationId"] ?: return@forEach
            val key = sessionId to operationId
            if (!backgroundFollowupsInFlight.add(key)) return@forEach
            diagnosticWriteScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val outcome = api.awaitBackgroundDiagnostic(sessionId, operationId)
                    if (outcome != null && outcome.kind == "background_rerank" &&
                        outcome.stage.startsWith("prior_") && outcome.fields["operationId"] == operationId
                    ) {
                        persistBackgroundOutcome(repository, sessionId, operationId, outcome, scheduled)
                    }
                } catch (failure: Throwable) {
                    if (!currentCoroutineContext().isActive) throw failure
                    runCatching { onBackgroundDiagnosticFailure(failure) }
                } finally {
                    backgroundFollowupsInFlight.remove(key)
                }
            }
        }
    }

    private fun recoverScheduledOutcomes(sessionId: String) {
        val repository = debugEvents ?: return
        diagnosticWriteScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val events = repository.listForSession(sessionId)
                val completed = events.filter { it.kind == "background_rerank" && it.stage.startsWith("prior_") }
                    .mapNotNull { it.fields["operationId"] }.toSet()
                startBackgroundFollowups(sessionId, events.filter {
                    it.kind == "background_rerank" && it.stage == "scheduled" &&
                        it.fields["operationId"]?.let { operationId -> operationId !in completed } == true
                })
            } catch (failure: Throwable) {
                if (!currentCoroutineContext().isActive) throw failure
                runCatching { onBackgroundDiagnosticFailure(failure) }
            }
        }
    }

    private fun enqueueBackgroundOutcome(
        repository: DebugEventRepository,
        sessionId: String,
        operationId: String,
        diagnostic: StreamDiagnostic,
        fallback: DebugEvent,
    ) {
        diagnosticWriteScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                persistBackgroundOutcome(repository, sessionId, operationId, diagnostic, fallback)
            } catch (failure: Throwable) {
                if (!currentCoroutineContext().isActive) throw failure
                runCatching { onDiagnosticPersistenceFailure(failure) }
            }
        }
    }

    private suspend fun persistBackgroundOutcome(
        repository: DebugEventRepository,
        sessionId: String,
        operationId: String,
        diagnostic: StreamDiagnostic,
        fallback: DebugEvent,
    ) {
        backgroundOutcomeMutex.withLock {
            val existing = repository.listForSession(sessionId)
            if (existing.any { it.kind == "background_rerank" && it.stage.startsWith("prior_") && it.fields["operationId"] == operationId }) return@withLock
            val scheduled = existing.firstOrNull {
                it.kind == "background_rerank" && it.stage == "scheduled" && it.fields["operationId"] == operationId
            } ?: scheduledBackgroundEventsPending[sessionId to operationId]
            val origin = scheduled ?: fallback
            val sequence = if (scheduled != null) {
                (existing.filter { it.turnId == origin.turnId }.maxOfOrNull { it.sequence } ?: origin.sequence) + 1
            } else fallback.sequence
            repository.append(origin.copy(
                id = "background-outcome-$sessionId-$operationId",
                sequence = sequence,
                createdAt = clock(),
                kind = diagnostic.kind,
                stage = diagnostic.stage,
                fields = diagnostic.fields,
                metrics = diagnostic.metrics,
            ))
            scheduledBackgroundEventsPending.remove(sessionId to operationId)
        }
    }

    companion object {
        private var nextTurnId = 0L
        private val backgroundOutcomeMutex = Mutex()
        private val backgroundFollowupsInFlight = mutableSetOf<Pair<String, String>>()
        private val scheduledBackgroundEventsPending = mutableMapOf<Pair<String, String>, DebugEvent>()
        const val INTRODUCTION_INSTRUCTION =
            "Begin this new adventure with a short, immersive introduction in the scenario's language. " +
                "Establish the player's starting situation and surroundings using the scenario and initial world state. " +
                "Do not reveal hidden scenario information or choose actions, thoughts, or decisions for the player. " +
                "End by inviting the player's first action."
    }
}
