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
        add(stableSystemContext(request.scenarioMarkdown, request.locations))
        ContextEpochs.history(request.sessionId, request.scenarioMarkdown, request.history, request.modelId, request.contextCompression, request.locations).forEach { add(historyMessage(it)) }
        WorldStateSnapshot.render(request.worldState).takeIf { it.isNotBlank() }?.let { add(UserMessage.from(authoritativeWorldState(it))) }
        request.initialSituation.takeIf { it.isNotBlank() }?.let { add(UserMessage.from(initialSituation(it))) }
        add(UserMessage.from(request.playerAction))
    }

    fun buildWarmup(request: WarmupRequest): List<ChatMessage> = buildList {
        add(stableSystemContext(request.scenarioMarkdown, request.locations))
        ContextEpochs.history(request.sessionId, request.scenarioMarkdown, request.history, request.modelId, request.contextCompression, request.locations).forEach { add(historyMessage(it)) }
        WorldStateSnapshot.render(request.worldState).takeIf { it.isNotBlank() }?.let { add(UserMessage.from(authoritativeWorldState(it))) }
    }

    fun buildRelevance(request: FragmentRelevanceRequest): List<ChatMessage> = buildList {
        add(stableSystemContext(request.scenarioMarkdown, request.locations))
        ContextEpochs.history(request.sessionId, request.scenarioMarkdown, request.history, request.modelId, request.contextCompression, request.locations).forEach { add(historyMessage(it)) }
        WorldStateSnapshot.render(request.worldState).takeIf { it.isNotBlank() }?.let { add(UserMessage.from(authoritativeWorldState(it))) }
        add(UserMessage.from("ANALYSIS CONTEXT (candidate key; not a player statement):\n${request.candidateKey}"))
        add(UserMessage.from(FRAGMENT_RELEVANCE_PROMPT))
    }

    private fun historyMessage(entry: ChatEntry): ChatMessage = when (entry.role) {
        "player", "user" -> UserMessage.from(entry.content)
        "game-master", "assistant", "ai" -> AiMessage.from(entry.content)
        "runtime-fragment" -> UserMessage.from("GM GUIDANCE / RUNTIME CONTEXT (generated context; not a player statement):\n${entry.content}")
        else -> throw IllegalArgumentException("Unknown chat role: ${entry.role}")
    }

    private fun stableSystemContext(scenarioMarkdown: String, locations: Map<String, String>): SystemMessage {
        val sections = mutableListOf(scenarioForPrompt(scenarioMarkdown))
        if (locations.isNotEmpty()) sections += locationCatalog(locations)
        return SystemMessage.from(sections.joinToString("\n\n"))
    }

    private fun locationCatalog(locations: Map<String, String>): String {
        val entries = locations.entries
            .sortedWith(compareBy<Map.Entry<String, String>> { it.key.lowercase() }.thenBy { it.key })
            .joinToString("\n") { (key, description) ->
                val compactDescription = description.replace(Regex("\\s+"), " ").trim()
                if (compactDescription.isEmpty()) "- $key" else "- $key — $compactDescription"
            }
        return "CANONICAL LOCATION CATALOG (use each key as the location name; descriptions give spatial context):\n$entries"
    }

    private fun authoritativeWorldState(snapshot: String) =
        "AUTHORITATIVE WORLD STATE (engine supplied; use as current facts, not player dialogue):\n$snapshot"

    private fun initialSituation(setting: String) =
        "INITIAL SITUATION (one-time opening context; not player dialogue):\n$setting"

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
        const val FRAGMENT_RELEVANCE_PROMPT =
            "Rate how well the preceding text fits the current game situation. Return a single ASCII digit between 0 (=no fit) and 9 (=perfect fit)."
    }
}
