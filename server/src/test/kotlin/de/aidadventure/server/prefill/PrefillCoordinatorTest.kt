package de.aidadventure.server.prefill

import de.aidadventure.server.llamacpp.CacheMetrics
import de.aidadventure.server.llamacpp.LlamaCppSlot
import de.aidadventure.server.model.PrefillDisposition
import de.aidadventure.server.model.PrefillRequest
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `turn flow is single use`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake)
        val turn = coordinator.beginTurn(turn("look"))

        turn.toList()
        val failure = assertFailsWith<IllegalStateException> { turn.toList() }

        assertEquals("Turn flow can only be collected once.", failure.message)
        assertEquals(listOf("look"), fake.turnedActions)
        coordinator.close()
    }

    @Test
    fun `closing cancels and joins a running turn before releasing its slot`() = runBlocking {
        val events = mutableListOf<String>()
        val streamStarted = CompletableDeferred<Unit>()
        val fake = ControllableGateway(
            stream = {
                flow {
                    events += "turn-started"
                    streamStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        events += "turn-stopped"
                    }
                }
            },
            onRelease = { events += "released" },
        )
        val coordinator = SessionPrefillCoordinator(fake)
        val collector = launch { coordinator.beginTurn(turn("look")).toList() }
        streamStarted.await()

        coordinator.close()
        collector.join()

        assertEquals(listOf("turn-started", "turn-stopped", "released"), events)
    }

    @Test
    fun `closing cancels and joins an active prefill before releasing its slot`() = runBlocking {
        val events = mutableListOf<String>()
        val prefillStarted = CompletableDeferred<Unit>()
        val fake = ControllableGateway(
            prefill = {
                events += "prefill-started"
                prefillStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    events += "prefill-stopped"
                }
                CacheMetrics(null, null)
            },
            onRelease = { events += "released" },
        )
        val coordinator = SessionPrefillCoordinator(fake)
        coordinator.submit(prefill(1, "open"))
        prefillStarted.await()

        coordinator.close()

        assertEquals(listOf("prefill-started", "prefill-stopped", "released"), events)
    }

    @Test
    fun `closed coordinator cannot hand a released slot to an uncollected turn`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake)
        coordinator.submit(prefill(1, "open"))
        fake.awaitPrefill(1)
        val turn = coordinator.beginTurn(turn("open"))
        fake.completePrefill(1)
        fake.awaitPrefillCompletion(1)

        coordinator.close()
        assertFailsWith<CancellationException> { turn.toList() }

        assertTrue(fake.turnedActions.isEmpty())
        assertEquals(listOf(LlamaCppSlot(1)), fake.releasedSlots)
    }

    @Test
    fun `failed slot release is retried by a later close`() = runBlocking {
        val fake = ControllableGateway(releaseFailures = 1)
        val leases = PrefillSlotLeaseManager(fake)
        val coordinator = SessionPrefillCoordinator(fake, leases)
        coordinator.submit(prefill(1, "open"))
        fake.awaitPrefill(1)
        fake.completePrefill(1)
        fake.awaitPrefillCompletion(1)

        assertFailsWith<IllegalStateException> { coordinator.close() }
        coordinator.close()

        assertEquals(2, fake.releaseAttempts)
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
        sessionId = "session",
        modelId = "model",
        llamaCppPort = 8081,
        scenarioMarkdown = "# Scenario",
        history = emptyList(),
        playerAction = action,
    )

    private class ControllableGateway(
        private val stream: (TurnRequest) -> Flow<TurnStreamEvent> = { flowOf() },
        private val prefill: (suspend (TurnRequest) -> CacheMetrics)? = null,
        private val onRelease: () -> Unit = {},
        private var releaseFailures: Int = 0,
    ) : PrefillGateway {
        private val prefillCompletion = mutableMapOf<Long, CompletableDeferred<Unit>>()
        private val prefillStarted = mutableMapOf<Long, CompletableDeferred<Unit>>()
        private val prefillFinished = mutableMapOf<Long, CompletableDeferred<Unit>>()
        val prefilledActions = mutableListOf<String>()
        val turnedActions = mutableListOf<String>()
        val prefillSlots = mutableListOf<LlamaCppSlot>()
        val turnSlots = mutableListOf<LlamaCppSlot>()
        val releasedSlots = mutableListOf<LlamaCppSlot>()
        var releaseAttempts = 0
            private set

        override fun acquireIdleSlot(port: Int, excludedSlots: Set<LlamaCppSlot>): LlamaCppSlot =
            LlamaCppSlot(1).also { check(it !in excludedSlots) { "No idle slot" } }

        override fun releaseSlot(port: Int, slot: LlamaCppSlot) {
            releaseAttempts++
            if (releaseFailures > 0) {
                releaseFailures--
                error("release failed")
            }
            releasedSlots += slot
            onRelease()
        }

        override suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot): CacheMetrics {
            prefilledActions += request.playerAction
            prefillSlots += slot
            val revision = requestRevision(request)
            prefillStarted.getOrPut(revision) { CompletableDeferred() }.complete(Unit)
            return try {
                prefill?.invoke(request) ?: run {
                    prefillCompletion.getOrPut(revision) { CompletableDeferred() }.await()
                    CacheMetrics(null, null)
                }
            } finally {
                prefillFinished.getOrPut(revision) { CompletableDeferred() }.complete(Unit)
            }
        }

        override fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent> {
            turnedActions += request.playerAction
            turnSlots += slot
            return stream(request)
        }

        suspend fun awaitPrefill(revision: Long) {
            prefillStarted.getOrPut(revision) { CompletableDeferred() }.await()
        }

        fun completePrefill(revision: Long) {
            prefillCompletion.getOrPut(revision) { CompletableDeferred() }.complete(Unit)
        }

        suspend fun awaitPrefillCompletion(revision: Long) {
            prefillFinished.getOrPut(revision) { CompletableDeferred() }.await()
        }

        private fun requestRevision(request: TurnRequest): Long = when (request.playerAction) {
            "open" -> 1
            "open the" -> 2
            "open the door" -> 3
            "look" -> 4
            else -> error("Unexpected action")
        }
    }
}
