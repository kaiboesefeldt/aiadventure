package de.aidadventure.server.prefill

import de.aidadventure.server.llamacpp.CacheMetrics
import de.aidadventure.server.llamacpp.LlamaCppSlot
import de.aidadventure.server.model.PrefillDisposition
import de.aidadventure.server.model.PrefillRequest
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class PrefillRegistryTest {
    @Test
    fun `returns one coordinator per key and expires it after the ttl`() = runBlocking {
        var now = 1_000L
        val created = mutableListOf<ClosingCoordinator>()
        val registry = ExpiringPrefillRegistry(
            coordinatorFactory = { _, _ -> ClosingCoordinator().also(created::add) },
            clock = { now },
            ttlMillis = 100,
        )
        val key = CoordinatorKey("session", "model", 8081)

        val coordinator = registry.coordinatorFor(key)
        assertSame(coordinator, registry.coordinatorFor(key))
        now += 100
        registry.removeIdle(now)

        assertEquals(1, created.single().closeCalls)
        val replacement = registry.coordinatorFor(key)
        assertEquals(2, created.size)
        assertSame(created.last(), replacement)
    }

    @Test
    fun `ttl cleanup leaves an active coordinator registered`() = runBlocking {
        var now = 1_000L
        val gateway = RegistryGateway()
        val registry = ExpiringPrefillRegistry(gateway = gateway, clock = { now }, ttlMillis = 100)
        val key = CoordinatorKey("session", "model", 8081)
        val coordinator = registry.coordinatorFor(key)
        coordinator.submit(prefill("session", 1))
        gateway.prefillStarted.await()

        now += 100
        registry.removeIdle(now)

        assertSame(coordinator, registry.coordinatorFor(key))
        assertEquals(0, gateway.releaseAttempts)

        gateway.prefillCompletion.complete(Unit)
        gateway.prefillFinished.await()
        now += 100
        registry.removeIdle(now)

        assertEquals(1, gateway.releaseAttempts)
        assertNotSame(coordinator, registry.coordinatorFor(key))
    }

    @Test
    fun `registry coordinators share slot leases per port`() = runBlocking {
        val gateway = RegistryGateway(slots = listOf(LlamaCppSlot(1), LlamaCppSlot(2)))
        val registry = ExpiringPrefillRegistry(gateway = gateway)
        val first = registry.coordinatorFor(CoordinatorKey("first", "model", 8081))
        val second = registry.coordinatorFor(CoordinatorKey("second", "model", 8081))

        first.submit(prefill("first", 1))
        gateway.awaitPrefillCount(1)
        second.submit(prefill("second", 1))
        gateway.awaitPrefillCount(2)

        assertEquals(listOf(LlamaCppSlot(1), LlamaCppSlot(2)), gateway.prefillSlots)
        assertEquals(listOf(emptySet(), setOf(LlamaCppSlot(1))), gateway.acquireExclusions)
        first.close()
        second.close()
    }

    private fun prefill(sessionId: String, revision: Long) = PrefillRequest(
        sessionId = sessionId,
        revision = revision,
        modelId = "model",
        llamaCppPort = 8081,
        scenarioMarkdown = "# Scenario",
        history = emptyList(),
        playerAction = "open",
    )

    private class ClosingCoordinator : PrefillCoordinator {
        var closeCalls = 0

        override suspend fun submit(request: de.aidadventure.server.model.PrefillRequest) =
            de.aidadventure.server.model.PrefillDisposition.STARTED

        override suspend fun beginTurn(request: de.aidadventure.server.model.TurnRequest) =
            kotlinx.coroutines.flow.emptyFlow<de.aidadventure.server.model.TurnStreamEvent>()

        override suspend fun close() {
            closeCalls++
        }

        override fun tryBeginIdleClose(): Boolean = true
    }

    private class RegistryGateway(
        private val slots: List<LlamaCppSlot> = listOf(LlamaCppSlot(1)),
    ) : PrefillGateway {
        val prefillStarted = CompletableDeferred<Unit>()
        val prefillCompletion = CompletableDeferred<Unit>()
        val prefillFinished = CompletableDeferred<Unit>()
        val prefillSlots = mutableListOf<LlamaCppSlot>()
        val acquireExclusions = mutableListOf<Set<LlamaCppSlot>>()
        var releaseAttempts = 0
            private set
        private val prefillCountSignals = List(slots.size) { CompletableDeferred<Unit>() }
        private val lock = Any()

        override fun acquireIdleSlot(port: Int, excludedSlots: Set<LlamaCppSlot>): LlamaCppSlot {
            acquireExclusions += excludedSlots
            return slots.first { it !in excludedSlots }
        }

        override fun releaseSlot(port: Int, slot: LlamaCppSlot) {
            releaseAttempts++
        }

        override suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot): CacheMetrics {
            val count = synchronized(lock) {
                prefillSlots += slot
                prefillSlots.size
            }
            prefillStarted.complete(Unit)
            prefillCountSignals[count - 1].complete(Unit)
            return try {
                prefillCompletion.await()
                CacheMetrics(null, null)
            } finally {
                prefillFinished.complete(Unit)
            }
        }

        override fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent> = emptyFlow()

        suspend fun awaitPrefillCount(count: Int) {
            prefillCountSignals[count - 1].await()
        }
    }
}
