package de.aidadventure.server.prefill

import de.aidadventure.server.llamacpp.CacheMetrics
import de.aidadventure.server.llamacpp.LlamaCppSlot
import de.aidadventure.server.model.PrefillDisposition
import de.aidadventure.server.model.PrefillRequest
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PrefillCoordinatorTest {
    @Test
    fun `newer pending prefill replaces older pending work`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake)

        coordinator.submit(prefill(1, "open"))
        fake.awaitPrefill(1)
        coordinator.submit(prefill(2, "open the"))
        coordinator.submit(prefill(3, "open the door"))
        fake.completePrefill(1)
        fake.awaitPrefill(3)

        assertEquals(listOf("open", "open the door"), fake.prefilledActions)
        coordinator.close()
    }

    @Test
    fun `turn drops pending prefill and follows active prefill on its slot`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake)

        coordinator.submit(prefill(1, "open"))
        fake.awaitPrefill(1)
        coordinator.submit(prefill(2, "open door"))
        val turn = async(start = CoroutineStart.UNDISPATCHED) { coordinator.beginTurn(turn("open door")).toList() }
        fake.completePrefill(1)
        turn.await()

        assertEquals(listOf("open"), fake.prefilledActions)
        assertEquals(listOf("open door"), fake.turnedActions)
        assertEquals(fake.prefillSlots.single(), fake.turnSlots.single())
        coordinator.close()
    }

    @Test
    fun `stale revision is ignored`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake)

        assertEquals(PrefillDisposition.STARTED, coordinator.submit(prefill(3, "new")))
        assertEquals(PrefillDisposition.STALE, coordinator.submit(prefill(2, "old")))
        coordinator.close()
    }

    @Test
    fun `closing while prefill is active releases its slot once`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake)

        coordinator.submit(prefill(1, "open"))
        fake.awaitPrefill(1)
        coordinator.close()
        coordinator.close()

        assertEquals(listOf(LlamaCppSlot(1)), fake.releasedSlots)
    }

    private fun prefill(revision: Long, action: String) = PrefillRequest(
        sessionId = "session",
        revision = revision,
        modelId = "model",
        llamaCppPort = 8081,
        scenarioMarkdown = "# Scenario",
        history = emptyList(),
        playerAction = action,
    )

    private fun turn(action: String) = TurnRequest(
        modelId = "model",
        scenarioMarkdown = "# Scenario",
        history = emptyList(),
        playerAction = action,
        llamaCppPort = 8081,
        sessionId = "session",
    )

    private class ControllableGateway : PrefillGateway {
        private val prefillCompletion = mutableMapOf<Long, CompletableDeferred<Unit>>()
        private val prefillStarted = mutableMapOf<Long, CompletableDeferred<Unit>>()
        val prefilledActions = mutableListOf<String>()
        val turnedActions = mutableListOf<String>()
        val prefillSlots = mutableListOf<LlamaCppSlot>()
        val turnSlots = mutableListOf<LlamaCppSlot>()
        val releasedSlots = mutableListOf<LlamaCppSlot>()

        override fun acquireIdleSlot(port: Int): LlamaCppSlot = LlamaCppSlot(1)

        override fun releaseSlot(port: Int, slot: LlamaCppSlot) {
            releasedSlots += slot
        }

        override suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot): CacheMetrics {
            prefilledActions += request.playerAction
            prefillSlots += slot
            prefillStarted.getOrPut(requestRevision(request)) { CompletableDeferred() }.complete(Unit)
            prefillCompletion.getOrPut(requestRevision(request)) { CompletableDeferred() }.await()
            return CacheMetrics(null, null)
        }

        override fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent> {
            turnedActions += request.playerAction
            turnSlots += slot
            return flowOf()
        }

        suspend fun awaitPrefill(revision: Long) {
            prefillStarted.getOrPut(revision) { CompletableDeferred() }.await()
        }

        fun completePrefill(revision: Long) {
            prefillCompletion.getOrPut(revision) { CompletableDeferred() }.complete(Unit)
        }

        private fun requestRevision(request: TurnRequest): Long = when (request.playerAction) {
            "open" -> 1
            "open the" -> 2
            "open the door" -> 3
            else -> error("Unexpected action")
        }
    }
}
