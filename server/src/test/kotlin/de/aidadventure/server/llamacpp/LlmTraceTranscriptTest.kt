package de.aidadventure.server.llamacpp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmTraceTranscriptTest {
    @Test
    fun `returns full initial history`() {
        val transcript = LlmTraceTranscript()
        val initial = listOf(message("system", "rules"), message("user", "look"))

        assertEquals(initial, transcript.nextPrompt("session-1", initial))
    }

    @Test
    fun `returns only appended history after the first prompt`() {
        val transcript = LlmTraceTranscript()
        val first = listOf(message("system", "rules"), message("user", "look"))

        assertEquals(first, transcript.nextPrompt("session-1", first))
        assertEquals(
            listOf(message("assistant", "trees")),
            transcript.nextPrompt("session-1", first + message("assistant", "trees")),
        )
    }

    @Test
    fun `returns the rewritten suffix when a historic entry changes`() {
        val transcript = LlmTraceTranscript()
        val first = listOf(message("system", "rules"), message("user", "look"), message("assistant", "trees"))
        val rewritten = listOf(message("system", "rules"), message("user", "listen"), message("assistant", "wind"))

        transcript.nextPrompt("session-1", first)

        assertEquals(rewritten.drop(1), transcript.nextPrompt("session-1", rewritten))
    }

    @Test
    fun `returns no history when the current history is a shortened prefix`() {
        val transcript = LlmTraceTranscript()
        val full = listOf(message("system", "rules"), message("user", "look"), message("assistant", "trees"))
        val shortened = full.dropLast(1)

        transcript.nextPrompt("session-1", full)

        assertEquals(emptyList(), transcript.nextPrompt("session-1", shortened))
        assertEquals(
            listOf(message("user", "walk onward")),
            transcript.nextPrompt("session-1", shortened + message("user", "walk onward")),
        )
    }

    @Test
    fun `does not return a recorded final assistant message again`() {
        val transcript = LlmTraceTranscript()
        val prompt = listOf(message("system", "rules"), message("user", "look"))
        val final = message("assistant", "trees")

        transcript.nextPrompt("session-1", prompt)
        transcript.recordFinalAssistantMessage("session-1", final.content)

        assertEquals(emptyList(), transcript.nextPrompt("session-1", prompt + final))
        assertEquals(
            listOf(message("user", "walk onward")),
            transcript.nextPrompt("session-1", prompt + final + message("user", "walk onward")),
        )
    }

    @Test
    fun `does not append blank or duplicate final assistant messages`() {
        val transcript = LlmTraceTranscript()
        val prompt = listOf(message("system", "rules"), message("user", "look"))
        val final = message("assistant", "trees")

        transcript.nextPrompt("session-1", prompt)
        transcript.recordFinalAssistantMessage("session-1", "  ")
        transcript.recordFinalAssistantMessage("session-1", final.content)
        transcript.recordFinalAssistantMessage("session-1", final.content)

        assertEquals(
            listOf(final),
            transcript.nextPrompt("session-1", prompt + final + final),
        )
    }

    @Test
    fun `interleaved prompt and final output keep one atomic same-session cursor`() {
        val transcript = LlmTraceTranscript()
        val prompt = listOf(message("system", "rules"), message("user", "look"))
        val final = message("assistant", "trees")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        transcript.nextPrompt("session-1", prompt)
        val nextPrompt = executor.submit<List<LlmTraceMessage>> {
            assertTrue(start.await(5, TimeUnit.SECONDS))
            transcript.nextPrompt("session-1", prompt + final)
        }
        val recordFinal = executor.submit {
            assertTrue(start.await(5, TimeUnit.SECONDS))
            transcript.recordFinalAssistantMessage("session-1", final.content)
        }

        start.countDown()
        val returnedDuringInterleave = nextPrompt.get(5, TimeUnit.SECONDS)
        recordFinal.get(5, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertTrue(returnedDuringInterleave == emptyList<LlmTraceMessage>() || returnedDuringInterleave == listOf(final))
        assertEquals(
            listOf(message("user", "walk onward")),
            transcript.nextPrompt("session-1", prompt + final + message("user", "walk onward")),
        )
    }

    private fun message(role: String, content: String) = LlmTraceMessage(role, content)
}
