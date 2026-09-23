package de.aidadventure.server.game

import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.FragmentRelevanceRequest
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.WarmupRequest
import de.aidadventure.server.worldstate.WorldState
import de.aidadventure.server.worldstate.WorldStateSnapshot
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage

class PromptFactory {
    fun build(request: TurnRequest): List<ChatMessage> = buildList {
        add(SystemMessage.from(GAME_MASTER_INSTRUCTION))
        add(SystemMessage.from(scenarioForPrompt(request.scenarioMarkdown)))
        addLocationCatalog(request.locations)
        ContextEpochs.history(request.sessionId, request.scenarioMarkdown, request.history, request.modelId, request.contextCompression, request.locations).forEach { add(historyMessage(it)) }
        WorldStateSnapshot.render(request.worldState).takeIf { it.isNotBlank() }?.let { add(SystemMessage.from(it)) }
        add(UserMessage.from(request.playerAction))
    }

    fun buildWarmup(request: WarmupRequest): List<ChatMessage> = buildList {
        add(SystemMessage.from(GAME_MASTER_INSTRUCTION))
        add(SystemMessage.from(scenarioForPrompt(request.scenarioMarkdown)))
        addLocationCatalog(request.locations)
        ContextEpochs.history(request.sessionId, request.scenarioMarkdown, request.history, request.modelId, request.contextCompression, request.locations).forEach { add(historyMessage(it)) }
        WorldStateSnapshot.render(request.worldState).takeIf { it.isNotBlank() }?.let { add(SystemMessage.from(it)) }
    }

    fun buildRelevance(request: FragmentRelevanceRequest): List<ChatMessage> = buildList {
        add(SystemMessage.from(GAME_MASTER_INSTRUCTION))
        add(SystemMessage.from(scenarioForPrompt(request.scenarioMarkdown)))
        addLocationCatalog(request.locations)
        ContextEpochs.history(request.sessionId, request.scenarioMarkdown, request.history, request.modelId, request.contextCompression, request.locations).forEach { add(historyMessage(it)) }
        WorldStateSnapshot.render(request.worldState).takeIf { it.isNotBlank() }?.let { add(SystemMessage.from(it)) }
        add(SystemMessage.from(request.candidateKey))
        add(UserMessage.from(FRAGMENT_RELEVANCE_PROMPT))
    }

    private fun historyMessage(entry: ChatEntry): ChatMessage = when (entry.role) {
        "player", "user" -> UserMessage.from(entry.content)
        "game-master", "assistant", "ai" -> AiMessage.from(entry.content)
        "runtime-fragment" -> SystemMessage.from(entry.content)
        else -> throw IllegalArgumentException("Unknown chat role: ${entry.role}")
    }

    private fun MutableList<ChatMessage>.addLocationCatalog(locations: Map<String, String>) {
        if (locations.isEmpty()) return
        val entries = locations.entries
            .sortedWith(compareBy<Map.Entry<String, String>> { it.key.lowercase() }.thenBy { it.key })
            .joinToString("\n") { (key, description) ->
                val compactDescription = description.replace(Regex("\\s+"), " ").trim()
                if (compactDescription.isEmpty()) "- $key" else "- $key — $compactDescription"
            }
        add(SystemMessage.from("CANONICAL LOCATION CATALOG (use each key as the location name; descriptions give spatial context):\n$entries"))
    }

    private fun scenarioForPrompt(markdown: String): String {
        if (!markdown.startsWith("---\n") && !markdown.startsWith("---\r\n")) return markdown

        var lineStart = markdown.indexOf('\n') + 1
        while (lineStart <= markdown.length) {
            val lineEnd = markdown.indexOf('\n', lineStart).let { if (it == -1) markdown.length else it }
            if (markdown.substring(lineStart, lineEnd).removeSuffix("\r") == "---") {
                return markdown.substring(if (lineEnd == markdown.length) lineEnd else lineEnd + 1)
            }
            if (lineEnd == markdown.length) return markdown
            lineStart = lineEnd + 1
        }
        return markdown
    }

    companion object {
        const val GAME_MASTER_INSTRUCTION =
            "You are a concise, responsive game master. Continue the adventure from the scenario and conversation."
        const val FRAGMENT_RELEVANCE_PROMPT =
            "Rate how well the preceding text fits the current game situation. Return a single ASCII digit between 0 (=no fit) and 9 (=perfect fit)."
    }
}
