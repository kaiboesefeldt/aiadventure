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
    suspend fun submit(
        session: GameSession,
        scenario: Scenario,
        playerAction: String,
        onStarted: (GameSession) -> Unit,
        onStreamed: (GameSession) -> Unit,
    ): CompletedTurn {
        val started = session.copy(
            messages = session.messages + GameMessage("player", playerAction, clock()),
            updatedAt = clock(),
        )
        onStarted(started)
        sessions.save(started)

        val streamedText = StringBuilder()
        val metrics = try {
            api.play(TurnRequestMapper.map(session, scenario, playerAction)) { token ->
                streamedText.append(token)
                onStreamed(
                    started.copy(
                        messages = started.messages + GameMessage("game-master", streamedText.toString(), clock()),
                        updatedAt = clock(),
                    ),
                )
            }
        } catch (cause: Throwable) {
            onStreamed(started)
            throw cause
        }
        val completed = started.copy(
            messages = started.messages + GameMessage("game-master", streamedText.toString(), clock()),
            updatedAt = clock(),
        )
        sessions.save(completed)
        return CompletedTurn(completed, metrics)
    }
}
