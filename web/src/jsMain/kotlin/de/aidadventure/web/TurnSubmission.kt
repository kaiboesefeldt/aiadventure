package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.api.TurnRequestMapper
import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.persistence.SessionRepository

internal data class CompletedTurn(val session: GameSession, val metrics: TurnMetrics)

internal class TurnSubmission(
    private val sessions: SessionRepository,
    private val api: AdventureApi,
    private val clock: () -> Long = ::now,
) {
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
        liveSessionId: String = session.id,
        introduction: Boolean = false,
    ): CompletedTurn {
        val started = session.copy(
            messages = session.messages + GameMessage("player", playerAction, clock(), hidden = introduction),
            updatedAt = clock(),
        )
        onStarted(started)

        val streamedText = StringBuilder()
        var messages = started.messages
        val completion = try {
            api.play(TurnRequestMapper.map(session, scenario, playerAction, liveSessionId), onInputReady, { fragment ->
                messages = messages.dropLast(1) + GameMessage("runtime-fragment", fragment, clock()) + messages.takeLast(1)
                onStreamed(started.copy(messages = messages, updatedAt = clock()))
            }) { token ->
                streamedText.append(token)
                onStreamed(
                    started.copy(
                        messages = messages + GameMessage("game-master", streamedText.toString(), clock()),
                        updatedAt = clock(),
                    ),
                )
            }
        } catch (cause: Throwable) {
            onStreamed(started)
            throw cause
        }
        val completed = started.copy(
            messages = messages + GameMessage("game-master", streamedText.toString(), clock()),
            updatedAt = clock(),
            worldState = completion.worldState,
            introductionPending = if (introduction) false else session.introductionPending,
        )
        if (introduction) check(streamedText.isNotBlank()) { "The introduction was empty. Please try again." }
        sessions.save(completed)
        return CompletedTurn(completed, completion.metrics)
    }

    companion object {
        const val INTRODUCTION_INSTRUCTION =
            "Begin this new adventure with a short, immersive introduction in the scenario's language. " +
                "Establish the player's starting situation and surroundings using the scenario and initial world state. " +
                "Do not reveal hidden scenario information or choose actions, thoughts, or decisions for the player. " +
                "End by inviting the player's first action."
    }
}
