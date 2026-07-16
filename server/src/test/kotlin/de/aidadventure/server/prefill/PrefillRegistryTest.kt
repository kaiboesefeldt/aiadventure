package de.aidadventure.server.prefill

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PrefillRegistryTest {
    @Test
    fun `returns one coordinator per key and expires it after the ttl`() = runBlocking {
        var now = 1_000L
        val created = mutableListOf<ClosingCoordinator>()
        val registry = ExpiringPrefillRegistry(
            coordinatorFactory = { ClosingCoordinator().also(created::add) },
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

    private class ClosingCoordinator : PrefillCoordinator {
        var closeCalls = 0

        override suspend fun submit(request: de.aidadventure.server.model.PrefillRequest) =
            de.aidadventure.server.model.PrefillDisposition.STARTED

        override suspend fun beginTurn(request: de.aidadventure.server.model.TurnRequest) =
            kotlinx.coroutines.flow.emptyFlow<de.aidadventure.server.model.TurnStreamEvent>()

        override suspend fun close() {
            closeCalls++
        }
    }
}
