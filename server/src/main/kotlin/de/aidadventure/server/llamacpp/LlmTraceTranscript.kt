package de.aidadventure.server.llamacpp

import java.util.concurrent.ConcurrentHashMap

internal class LlmTraceTranscript {
    private val snapshots = ConcurrentHashMap<String, List<LlmTraceMessage>>()

    fun nextPrompt(sessionId: String, messages: List<LlmTraceMessage>): List<LlmTraceMessage> {
        val current = java.util.List.copyOf(messages)
        var prompt: List<LlmTraceMessage> = emptyList()

        snapshots.compute(sessionId) { _, previous ->
            val prefixLength = previous?.commonPrefixLength(current) ?: 0
            prompt = java.util.List.copyOf(current.drop(prefixLength))
            current
        }

        return prompt
    }

    fun recordFinalAssistantMessage(sessionId: String, content: String) {
        if (content.isBlank()) return

        val finalMessage = LlmTraceMessage("assistant", content)
        snapshots.compute(sessionId) { _, previous ->
            val snapshot = previous.orEmpty()
            if (snapshot.lastOrNull() == finalMessage) {
                snapshot
            } else {
                java.util.List.copyOf(snapshot + finalMessage)
            }
        }
    }

    private fun List<LlmTraceMessage>.commonPrefixLength(other: List<LlmTraceMessage>): Int {
        val length = minOf(size, other.size)
        return (0 until length).firstOrNull { this[it] != other[it] } ?: length
    }
}

internal data class LlmTraceMessage(val role: String, val content: String)
