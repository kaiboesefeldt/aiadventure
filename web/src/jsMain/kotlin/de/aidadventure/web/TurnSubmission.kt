package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.api.TurnRequestMapper
import de.aidadventure.web.api.TurnStreamStoppedException
import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.DebugEvent
import de.aidadventure.web.model.Scenario
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

internal data class CompletedTurn(val session: GameSession, val metrics: TurnMetrics)

internal class TurnSubmission(
    private val sessions: SessionRepository,
    private val api: AdventureApi,
    private val clock: () -> Long = ::now,
    private val debugEvents: DebugEventRepository? = null,
    private val onDiagnosticPersistenceFailure: (Throwable) -> Unit = {
        window.asDynamic().console.warn("Could not save diagnostic event")
    },
) {
    private val diagnosticWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        var nextSequence = 0
        val priorPlayerIndex = session.messages.indexOfLast { it.role == "player" && isVisibleTranscriptMessage(it) }
            .takeIf { it >= 0 }
            ?.let { rawIndex -> session.messages.take(rawIndex).count(::isVisibleTranscriptMessage) }
        val currentPlayerIndex = session.messages.count(::isVisibleTranscriptMessage)
        val started = session.copy(
            messages = session.messages + GameMessage("player", playerAction, clock(), hidden = introduction),
            updatedAt = clock(),
        )
        onStarted(started)

        val streamedText = StringBuilder()
        var messages = started.messages
        val completion = try {
            api.play(TurnRequestMapper.map(session, scenario, playerAction, liveSessionId, includeInitialSituation = introduction), onGenerationReady, onInputReady, { fragment ->
                messages = messages.dropLast(1) + GameMessage("runtime-fragment", fragment, clock()) + messages.takeLast(1)
                onStreamed(started.copy(messages = messages, updatedAt = clock()))
            }, { token ->
                onTokenStarted()
                streamedText.append(token)
                onStreamed(
                    started.copy(
                        messages = messages + GameMessage("game-master", streamedText.toString(), clock()),
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
                        priorTurn -> priorPlayerIndex
                        introduction -> if (outputExists) currentPlayerIndex else null
                        diagnostic.transcriptRole == "player" -> currentPlayerIndex
                        diagnostic.transcriptRole == "game-master" && outputExists -> currentPlayerIndex + 1
                        else -> null
                    }
                    val transcriptRole = when {
                        priorTurn && priorPlayerIndex != null -> "player"
                        introduction && transcriptIndex != null -> "game-master"
                        introduction -> null
                        else -> diagnostic.transcriptRole
                    }
                    val event = DebugEvent(
                        id = "$turnId-$sequence", sessionId = session.id, turnId = turnId,
                        sequence = sequence, createdAt = clock(), kind = diagnostic.kind,
                        stage = diagnostic.stage, fields = diagnostic.fields,
                        metrics = diagnostic.metrics, transcriptRole = transcriptRole,
                        turnOrder = turnOrder,
                        transcriptIndex = transcriptIndex,
                    )
                    diagnosticWriteScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            repository.append(event)
                        } catch (failure: Throwable) {
                            if (!currentCoroutineContext().isActive) throw failure
                            runCatching { onDiagnosticPersistenceFailure(failure) }
                        }
                    }
                }
            })
        } catch (stopped: TurnStreamStoppedException) {
            if (streamedText.isEmpty()) throw stopped
            val partial = started.copy(
                messages = messages + GameMessage("game-master", streamedText.toString(), clock()),
                updatedAt = clock(),
            )
            sessions.save(partial)
            return CompletedTurn(partial, TurnMetrics(null, null, null))
        } catch (cause: Throwable) {
            onStreamed(started)
            throw cause
        }
        val completed = started.copy(
            messages = messages + GameMessage("game-master", streamedText.toString(), clock()),
            updatedAt = clock(),
            worldState = completion.worldState,
            contextCompression = completion.contextCompression,
            flags = completion.flags,
            introductionPending = if (introduction) false else session.introductionPending,
        )
        if (introduction) check(streamedText.isNotBlank()) { "The introduction was empty. Please try again." }
        sessions.save(completed)
        return CompletedTurn(completed, completion.metrics)
    }

    companion object {
        private var nextTurnId = 0L
        const val INTRODUCTION_INSTRUCTION =
            "Begin this new adventure with a short, immersive introduction in the scenario's language. " +
                "Establish the player's starting situation and surroundings using the scenario and initial world state. " +
                "Do not reveal hidden scenario information or choose actions, thoughts, or decisions for the player. " +
                "End by inviting the player's first action."
    }
}
