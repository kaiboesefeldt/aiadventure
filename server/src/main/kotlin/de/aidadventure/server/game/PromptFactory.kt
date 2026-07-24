package de.aidadventure.server.game

import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.FragmentRelevanceRequest
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.WarmupRequest
import de.aidadventure.server.worldstate.WorldState
import de.aidadventure.server.worldstate.WorldStateReducer
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage

class PromptFactory {
    fun build(request: TurnRequest): List<ChatMessage> = buildList {
        add(SystemMessage.from(GAME_MASTER_INSTRUCTION))
        add(SystemMessage.from(request.scenarioMarkdown))
        request.history.forEach { add(historyMessage(it)) }
        WorldStateReducer.snapshot(request.worldState).takeIf { it.isNotBlank() }?.let { add(SystemMessage.from(it)) }
        add(UserMessage.from(request.playerAction))
    }

    fun buildWarmup(request: WarmupRequest): List<ChatMessage> = buildList {
        add(SystemMessage.from(GAME_MASTER_INSTRUCTION))
        add(SystemMessage.from(request.scenarioMarkdown))
        request.history.forEach { add(historyMessage(it)) }
        WorldStateReducer.snapshot(request.worldState).takeIf { it.isNotBlank() }?.let { add(SystemMessage.from(it)) }
    }

    fun buildRelevance(request: FragmentRelevanceRequest): List<ChatMessage> = buildList {
        add(SystemMessage.from(GAME_MASTER_INSTRUCTION))
        add(SystemMessage.from(request.scenarioMarkdown))
        request.history.forEach { add(historyMessage(it)) }
        add(SystemMessage.from(request.candidateKey))
        add(UserMessage.from(FRAGMENT_RELEVANCE_PROMPT))
    }

    private fun historyMessage(entry: ChatEntry): ChatMessage = when (entry.role) {
        "player", "user" -> UserMessage.from(entry.content)
        "game-master", "assistant", "ai" -> AiMessage.from(entry.content)
        "runtime-fragment" -> SystemMessage.from(entry.content)
        else -> throw IllegalArgumentException("Unknown chat role: ${entry.role}")
    }

    companion object {
        const val GAME_MASTER_INSTRUCTION =
            "You are a concise, responsive game master. Continue the adventure from the scenario and conversation."
        const val FRAGMENT_RELEVANCE_PROMPT =
            "Rate how well the preceding text fits the current game situation. Return a single ASCII digit between 0 (=no fit) and 9 (=perfect fit)."
    }
}
