package de.aidadventure.server.prefill

import de.aidadventure.server.game.ContextEpochs
import de.aidadventure.server.llamacpp.*
import de.aidadventure.server.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class CompressionCoordinatorTest {
    @Test fun `input ready precedes maintenance and completion returns artifact on retained slot`() = runBlocking {
        val fake = CompressionGateway()
        val coordinator = SessionPrefillCoordinator(fake)
        val events = mutableListOf<TurnStreamEvent>()
        val request = TurnRequest("s", "m", 8081, "scenario", emptyList(), "go")
        val turn = async { coordinator.beginTurn(request).toList(events) }
        withTimeout(3000) { fake.maintenanceStarted.await() }
        assertTrue(events.any { it is TurnStreamEvent.InputReady })
        assertFalse(events.any { it is TurnStreamEvent.Done })
        assertEquals(PrefillDisposition.TURN_IN_PROGRESS, coordinator.submit(PrefillRequest("s", 1, "m", 8081, "scenario", emptyList(), "next")))
        assertFalse(fake.maintenanceCancelled)
        fake.finish.complete(Unit)
        turn.await()
        assertNotNull((events.last() as TurnStreamEvent.Done).contextCompression.pending)
        assertEquals(listOf(7, 7), fake.slots)
        assertEquals(0, fake.releases)
        coordinator.close()
        assertEquals(1, fake.releases)
    }
    @Test fun `shutdown cancels planned maintenance and releases retained slot once`() = runBlocking {
        val fake = CompressionGateway()
        val coordinator = SessionPrefillCoordinator(fake)
        val job = launch { runCatching { coordinator.beginTurn(TurnRequest("s", "m", 8081, "scenario", emptyList(), "go")).collect() } }
        withTimeout(3000) { fake.maintenanceStarted.await() }
        coordinator.close()
        job.join()
        assertTrue(fake.maintenanceCancelled)
        assertEquals(1, fake.releases)
    }
    @Test fun `late older epoch prefill and warmup cannot restore retired prefix`() = runBlocking {
        val history = listOf(ChatEntry("player", "look"), ChatEntry("game-master", "A traveller arrived."))
        val source = WarmupRequest("s", "m", 8081, "scenario", history)
        val active = ContextCompression(1, ContextEpochs.checkpoint(source, "Arrival."))
        val fake = CompressionGateway().also { it.selected = active; it.finish.complete(Unit) }
        val coordinator = SessionPrefillCoordinator(fake)
        coordinator.beginTurn(TurnRequest("s", "m", 8081, "scenario", history, "go")).toList()
        assertEquals(PrefillDisposition.STALE, coordinator.submit(PrefillRequest("s", 99, "m", 8081, "scenario", history, "old action")))
        assertEquals(PrefillDisposition.STALE, coordinator.warmup(source))
        assertEquals(0, fake.releases)
        coordinator.close()
    }
    private class CompressionGateway : PrefillGateway {
        val maintenanceStarted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val slots = mutableListOf<Int>()
        var selected: ContextCompression? = null
        override suspend fun prepare(request: TurnRequest, slot: LlamaCppSlot) = selected?.let { request.copy(contextCompression = it) } ?: request
        var releases = 0
        var maintenanceCancelled = false
        override fun acquireIdleSlot(port: Int, excludedSlots: Set<LlamaCppSlot>) = LlamaCppSlot(7)
        override fun releaseSlot(port: Int, slot: LlamaCppSlot) { releases++ }
        override suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot) = CacheMetrics(null, null)
        override fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent> = flow {
            slots += slot.id
            emit(TurnStreamEvent.Token("A long completed story."))
            emit(TurnStreamEvent.Done(TurnMetrics(null, 3, 7)))
        }
        override suspend fun maintain(request: WarmupRequest, slot: LlamaCppSlot): ContextCompression {
            slots += slot.id
            maintenanceStarted.complete(Unit)
            try { finish.await() } catch (cancelled: CancellationException) { maintenanceCancelled = true; throw cancelled }
            return request.contextCompression.copy(pending = ContextEpochs.checkpoint(request, "Story."))
        }
    }
}
