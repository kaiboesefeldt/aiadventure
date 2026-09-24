package de.aidadventure.server.prefill

import de.aidadventure.server.ResolvedTurn
import de.aidadventure.server.game.PromptFactory
import de.aidadventure.server.llamacpp.CacheMetrics
import de.aidadventure.server.llamacpp.LlamaCppSlot
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.PrefillDisposition
import de.aidadventure.server.model.PrefillRequest
import de.aidadventure.server.model.RuntimeFragmentDefinition
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnStreamEvent
import de.aidadventure.server.model.WarmupRequest
import de.aidadventure.server.worldstate.WorldState
import de.aidadventure.server.worldstate.WorldStateResolutionRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrefillCoordinatorTest {
    @Test
    fun `world state and warmup report accepted and failed outcomes without transcript text`() = runBlocking {
        val fake = ControllableGateway(
            stream = { flowOf(TurnStreamEvent.Token("SECRET_GAME_MASTER_OUTPUT"), TurnStreamEvent.Done(TurnMetrics(null, 4, 3))) },
            worldState = { WorldState("Bell Tower", "Find the bell keeper") },
            warmup = { throw IllegalStateException("SECRET_GAME_MASTER_OUTPUT") },
        )
        val coordinator = SessionPrefillCoordinator(fake, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val request = turn("SECRET_PLAYER_ACTION")
        coordinator.beginTurn(request).toList()
        val diagnostics = mutableListOf<de.aidadventure.server.model.StreamDiagnostic>()
        val accepted = coordinator.resolveWorldStateAndWarmup(
            request, "SECRET_GAME_MASTER_OUTPUT", warmup(),
            WorldStateResolutionRequest("session-1", "# Harbor", WorldState(), "SECRET_PLAYER_ACTION", "SECRET_GAME_MASTER_OUTPUT"),
            "request-1", diagnostics::add,
        )

        assertEquals("Bell Tower", accepted.location)
        assertEquals(listOf("world_state", "warmup"), diagnostics.map { it.kind })
        assertEquals(listOf("accepted", "failed"), diagnostics.map { it.stage })
        assertFalse(diagnostics.joinToString().contains("SECRET_PLAYER_ACTION"))
        assertFalse(diagnostics.joinToString().contains("SECRET_GAME_MASTER_OUTPUT"))
        coordinator.close()
    }

    @Test
    fun `next turn reports prior background relevance and warmup outcome before generation`() = runBlocking {
        val fake = ControllableGateway(
            classify = { "Y" },
            warmup = { CacheMetrics(8, 2) },
            stream = { flowOf(TurnStreamEvent.Token("Safe output"), TurnStreamEvent.Done(TurnMetrics(null, 4, 3))) },
        )
        val coordinator = SessionPrefillCoordinator(fake, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        coordinator.rerankAndWarmup(warmup(), candidate(), "prior-request")
        fake.awaitWarmupCompletion()

        val events = coordinator.beginTurn(turn("SECRET_PLAYER_ACTION")).toList()
        val diagnostics = events.filterIsInstance<TurnStreamEvent.Diagnostic>().map { it.value }
        assertEquals("background_rerank", diagnostics.first().kind)
        assertEquals("prior_completed", diagnostics.first().stage)
        assertEquals("accepted", diagnostics.first().fields["result"])
        assertEquals("candidate key", diagnostics.first().fields["matchText"])
        assertEquals("8", diagnostics.first().metrics?.cacheTokens?.toString())
        assertTrue(events.indexOfFirst { it is TurnStreamEvent.Diagnostic } < events.indexOfFirst { it is TurnStreamEvent.GenerationReady })
        assertFalse(diagnostics.joinToString().contains("SECRET_PLAYER_ACTION"))
        coordinator.close()
    }

    @Test
    fun `prefill forwards the location catalog to the primary request`() = runBlocking {
        val fake = ControllableGateway(prefill = { CacheMetrics(null, null) })
        val coordinator = SessionPrefillCoordinator(fake)
        val request = prefill(1, "open").copy(locations = mapOf("Harbour" to "", "Tower" to "A stone tower"))

        coordinator.submit(request)
        fake.awaitPrefill(1)

        assertEquals(request.locations, fake.prefillRequests.single().locations)
        coordinator.close()
    }

    @Test
    fun `accepted reranking yes then warms canonical history with fragment on retained slot`() = runBlocking {
        val fake = ControllableGateway(classify = { "Y" }, warmup = { CacheMetrics(null, null) })
        val coordinator = SessionPrefillCoordinator(fake, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val request = warmup(listOf(ChatEntry("player", "look"), ChatEntry("game-master", "The bell rings.")))

        assertEquals(PrefillDisposition.STARTED, coordinator.rerankAndWarmup(request, candidate(), "request-42"))
        fake.awaitWarmupCompletion()

        val warmup = fake.warmupRequests.single()
        assertEquals(listOf("classify", "warmup"), fake.slotLifecycle.map(SlotLifecycleEvent::operation).drop(1))
        assertEquals(listOf(ChatEntry("runtime-fragment", "fragment text")), warmup.history.takeLast(1))
        assertFalse(warmup.history.any { it.content == "candidate key" || it.content == PromptFactory.FRAGMENT_RELEVANCE_PROMPT })
        assertEquals(fake.classifySlots.single(), fake.warmupSlots.single())
        coordinator.close()
    }

    @Test
    fun `world state resolution uses retained turn slot before canonical warmup`() = runBlocking {
        val fake = ControllableGateway(
            stream = { flowOf(TurnStreamEvent.Token("The bell rings."), TurnStreamEvent.Done(TurnMetrics(null, 4, 3))) },
            worldState = { WorldState("Bell Tower", "Find the bell keeper") },
            warmup = { CacheMetrics(8, 1) },
        )
        val coordinator = SessionPrefillCoordinator(fake, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val turn = turn("look")
        val stateRequest = WorldStateResolutionRequest("session-1", "# Harbor", WorldState(), "look", "The bell rings.")

        coordinator.beginTurn(turn).toList()
        val accepted = coordinator.resolveWorldStateAndWarmup(
            turn,
            "The bell rings.",
            WarmupRequest("session-1", "test-model", 8081, "# Harbor", listOf(ChatEntry("game-master", "The bell rings."))),
            stateRequest,
            "request-1",
        )

        assertEquals(WorldState("Bell Tower", "Find the bell keeper"), accepted)
        assertEquals(listOf("acquire", "turn", "world_state", "warmup"), fake.slotLifecycle.map(SlotLifecycleEvent::operation))
        assertEquals(fake.turnSlots.single(), fake.worldStateSlots.single())
        assertEquals(fake.worldStateSlots.single(), fake.warmupSlots.single())
        coordinator.close()
    }

    @Test
    fun `rejected or malformed reranking warms canonical history without fragment`() = runBlocking {
        listOf("N", "not Y or N").forEach { decision ->
            val fake = ControllableGateway(classify = { decision }, warmup = { CacheMetrics(null, null) })
            val coordinator = SessionPrefillCoordinator(fake, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
            val request = warmup(listOf(ChatEntry("player", "look")))

            assertEquals(PrefillDisposition.STARTED, coordinator.rerankAndWarmup(request, candidate(), "request-42"))
            fake.awaitWarmupCompletion()

            assertEquals(request.history, fake.warmupRequests.single().history)
            assertEquals(listOf("acquire", "classify", "warmup"), fake.slotLifecycle.map(SlotLifecycleEvent::operation))
            coordinator.close()
        }
    }

    @Test
    fun `reranking records the raw response and parsed no decision`() = runBlocking {
        val diagnostics = mutableListOf<RerankDiagnostic>()
        val fake = ControllableGateway(classify = { "N" }, warmup = { CacheMetrics(null, null) })
        val coordinator = SessionPrefillCoordinator(
            fake,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            rerankDiagnosticSink = diagnostics::add,
        )

        assertEquals(PrefillDisposition.STARTED, coordinator.rerankAndWarmup(warmup(), candidate(), "request-42"))
        fake.awaitWarmupCompletion()

        assertEquals(listOf(RerankDiagnostic("N", false, false)), diagnostics)
        coordinator.close()
    }

    @Test
    fun `later prefill queues while reranking retains its slot`() = runBlocking {
        val fake = ControllableGateway(classify = { "Y" })
        val coordinator = SessionPrefillCoordinator(fake)

        assertEquals(PrefillDisposition.STARTED, coordinator.rerankAndWarmup(warmup(), candidate(), "request-42"))
        fake.awaitWarmup()
        assertEquals(PrefillDisposition.QUEUED, coordinator.submit(prefill(1, "follow the bell")))
        fake.completeWarmup()
        fake.awaitPrefill(1)

        assertEquals(fake.classifySlots.single(), fake.warmupSlots.single())
        assertEquals(fake.warmupSlots.single(), fake.prefillSlots.single())
        assertEquals(ChatEntry("runtime-fragment", "fragment text"), fake.prefillRequests.single().history.last())
        coordinator.close()
    }

    @Test
    fun `reserved reranking queues a later prefill until canonical warmup completes`() = runBlocking {
        val fake = ControllableGateway(classify = { "Y" })
        val coordinator = SessionPrefillCoordinator(fake)
        val reservation = requireNotNull(coordinator.reserveRerank(warmup(), "request-42"))

        assertEquals(PrefillDisposition.QUEUED, coordinator.submit(prefill(1, "follow the bell")))
        assertTrue(coordinator.completeReservedRerank(reservation, candidate()))
        fake.awaitWarmup()
        fake.completeWarmup()
        fake.awaitPrefill(1)

        assertEquals(fake.classifySlots.single(), fake.warmupSlots.single())
        assertEquals(fake.warmupSlots.single(), fake.prefillSlots.single())
        coordinator.close()
    }

    @Test
    fun `turn submitted during reranking waits for the accepted fragment`() = runBlocking {
        val fake = ControllableGateway(classify = { "Y" })
        val coordinator = SessionPrefillCoordinator(fake)
        val reservation = requireNotNull(coordinator.reserveRerank(warmup(), "request-42"))
        val turn = async(start = CoroutineStart.UNDISPATCHED) { coordinator.beginTurn(turn("follow the bell")).toList() }

        assertTrue(fake.turnRequests.isEmpty())
        assertTrue(coordinator.completeReservedRerank(reservation, candidate()))
        fake.awaitWarmup()
        assertTrue(fake.turnRequests.isEmpty())
        fake.completeWarmup()
        turn.await()

        assertEquals(ChatEntry("runtime-fragment", "fragment text"), fake.turnRequests.single().history.last())
        assertEquals(fake.warmupSlots.single(), fake.turnSlots.single())
        coordinator.close()
    }

    @Test
    fun `reserved reranking without a candidate warms only canonical history`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake)
        val request = warmup(listOf(ChatEntry("player", "look")))
        val reservation = requireNotNull(coordinator.reserveRerank(request, "request-42"))

        assertTrue(coordinator.completeReservedRerank(reservation, null))
        fake.awaitWarmup()

        assertEquals(request.history, fake.warmupRequests.single().history)
        assertTrue(fake.classifySlots.isEmpty())
        fake.completeWarmup()
        coordinator.close()
    }

    @Test
    fun `failed reranking releases slot and reports only safe failure fields`() = runBlocking {
        val failures = mutableListOf<WarmupFailure>()
        val fake = ControllableGateway(classify = { error("secret scenario and game history") })
        val coordinator = SessionPrefillCoordinator(
            fake,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onWarmupFailure = failures::add,
        )

        assertEquals(PrefillDisposition.STARTED, coordinator.rerankAndWarmup(warmup(), candidate(), "request-42"))
        fake.awaitClassifyCompletion()

        assertEquals(listOf(LlamaCppSlot(1)), fake.releasedSlots)
        assertEquals(listOf(WarmupFailure("request-42", "rerank", "IllegalStateException")), failures)
        assertFalse(failures.single().toString().contains("secret scenario"))
        coordinator.close()
    }

    @Test
    fun `lease lifecycle emits opaque diagnostics for acquired handoff retained and released slot`() = runBlocking {
        val diagnostics = mutableListOf<CoordinatorLeaseDiagnostic>()
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake, diagnosticSink = diagnostics::add)

        coordinator.submit(prefill(1, "open"))
        fake.awaitPrefill(1)
        fake.completePrefill(1)
        fake.awaitPrefillCompletion(1)
        coordinator.beginTurn(turn("look")).toList()
        coordinator.close()

        assertEquals(
            listOf("lease_acquired", "turn_handoff", "turn_retained", "lease_released"),
            diagnostics.map(CoordinatorLeaseDiagnostic::transition),
        )
        assertEquals(listOf(1, 1, 1, 1), diagnostics.map(CoordinatorLeaseDiagnostic::slotId))
        assertEquals(listOf(8081, 8081, 8081, 8081), diagnostics.map(CoordinatorLeaseDiagnostic::port))
        assertEquals(1, diagnostics.map(CoordinatorLeaseDiagnostic::coordinatorId).distinct().size)
    }

    @Test
    fun `turn waits for active warmup and receives its slot`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake)

        assertEquals(PrefillDisposition.STARTED, coordinator.warmup(warmup()))
        fake.awaitWarmup()
        val turn = async(start = CoroutineStart.UNDISPATCHED) { coordinator.beginTurn(turn("look")).toList() }
        fake.completeWarmup()
        turn.await()

        assertEquals(fake.warmupSlots.single(), fake.turnSlots.single())
        coordinator.close()
    }

    @Test
    fun `completed warmup retains its slot for the next prefill`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        assertEquals(PrefillDisposition.STARTED, coordinator.warmup(warmup()))
        fake.awaitWarmup()
        fake.completeWarmup()
        assertEquals(PrefillDisposition.STARTED, coordinator.submit(prefill(1, "open")))
        fake.awaitPrefill(1)

        assertEquals(fake.warmupSlots.single(), fake.prefillSlots.single())
        assertTrue(fake.releasedSlots.isEmpty())
        coordinator.close()
    }

    @Test
    fun `post turn fragment warmup queues later input prefill on its retained slot`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake)
        val fragmentWarmup = warmup(
            history = listOf(
                ChatEntry("player", "look"),
                ChatEntry("game-master", "The bell rings."),
                ChatEntry("runtime-fragment", "The keeper avoids the lighthouse."),
            ),
        )

        assertEquals(PrefillDisposition.STARTED, coordinator.warmup(fragmentWarmup))
        fake.awaitWarmup()
        assertEquals(PrefillDisposition.QUEUED, coordinator.submit(prefill(1, "follow the bell")))
        fake.completeWarmup()
        fake.awaitPrefill(1)

        assertEquals(fake.warmupSlots.single(), fake.prefillSlots.single())
        coordinator.close()
    }

    @Test
    fun `warmup during an active prefill is ignored as stale`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake)

        coordinator.submit(prefill(1, "open"))
        fake.awaitPrefill(1)

        assertEquals(PrefillDisposition.STALE, coordinator.warmup(warmup()))
        fake.completePrefill(1)
        fake.awaitPrefillCompletion(1)
        assertTrue(fake.warmupSlots.isEmpty())
        coordinator.close()
    }

    @Test
    fun `failed warmup releases its slot before reporting only safe failure fields`() = runBlocking {
        val events = mutableListOf<String>()
        val failures = mutableListOf<WarmupFailure>()
        val fake = ControllableGateway(
            warmup = { error("secret scenario and game history") },
            onRelease = { events += "released" },
        )
        val coordinator = SessionPrefillCoordinator(
            gateway = fake,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onWarmupFailure = {
                events += "reported"
                failures += it
            },
        )

        assertEquals(PrefillDisposition.STARTED, coordinator.warmup(warmup(), "request-42"))
        fake.awaitWarmupCompletion()

        assertEquals(listOf("released", "reported"), events)
        assertEquals(listOf(WarmupFailure("request-42", "warmup", "IllegalStateException")), failures)
        assertFalse(failures.single().toString().contains("secret scenario"))
        coordinator.close()
    }

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
    fun `prefill then turn uses the same enriched request on the same slot`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake)
        val enrichedHistory = listOf(ChatEntry("runtime-fragment", "keep quiet"))
        val prefill = prefill(1, "open", enrichedHistory)
        val turn = turn("open")
        val resolvedPrefill = ResolvedTurn(prefill.toTurnRequestForTest(), "keep quiet")

        coordinator.submit(prefill, resolvedPrefill)
        fake.awaitPrefill(1)
        fake.completePrefill(1)
        fake.awaitPrefillCompletion(1)
        val resolvedTurn = coordinator.resolvedTurnFor(turn)
        coordinator.beginTurn(requireNotNull(resolvedTurn).request).toList()

        assertEquals(listOf(prefill.toTurnRequestForTest()), fake.prefillRequests)
        assertEquals("keep quiet", resolvedTurn.fragment)
        assertEquals(listOf(prefill.toTurnRequestForTest()), fake.turnRequests)
        assertEquals(fake.prefillSlots.single(), fake.turnSlots.single())
        coordinator.close()
    }

    @Test
    fun `changed turn action does not reuse the prefilled resolved turn`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake)
        val prefill = prefill(1, "open", listOf(ChatEntry("runtime-fragment", "keep quiet")))
        val currentTurn = turn("kick")

        coordinator.submit(prefill, ResolvedTurn(prefill.toTurnRequestForTest(), "keep quiet"))
        fake.awaitPrefill(1)
        fake.completePrefill(1)
        fake.awaitPrefillCompletion(1)

        assertNull(coordinator.resolvedTurnFor(currentTurn))
        coordinator.beginTurn(currentTurn).toList()

        assertEquals(listOf(currentTurn), fake.turnRequests)
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
    fun `completed turn retains its slot for the next prefill`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake)

        coordinator.beginTurn(turn("look")).toList()
        coordinator.submit(prefill(1, "open"))
        fake.awaitPrefill(1)

        assertEquals(fake.turnSlots.single(), fake.prefillSlots.single())
        assertTrue(fake.releasedSlots.isEmpty())
        coordinator.close()
    }

    @Test
    fun `new game warmup keeps one warm slot through its first turn`() = runBlocking {
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        coordinator.warmup(warmup())
        fake.awaitWarmup()
        fake.completeWarmup()
        fake.awaitWarmupCompletion()
        coordinator.beginTurn(turn("enter the inn")).toList()

        assertWarmSlotLifecycle(fake, listOf("acquire", "warmup", "turn"))
        coordinator.close()
        assertEquals(listOf(LlamaCppSlot(1)), fake.releasedSlots)
        assertEquals(listOf("acquire", "warmup", "turn", "release"), fake.slotLifecycle.map(SlotLifecycleEvent::operation))
    }

    @Test
    fun `resumed game warmup keeps one warm slot through its first turn`() = runBlocking {
        val history = listOf(ChatEntry("player", "inspect the pier"), ChatEntry("game-master", "Fog covers the water."))
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        coordinator.warmup(warmup(history))
        fake.awaitWarmup()
        fake.completeWarmup()
        fake.awaitWarmupCompletion()
        coordinator.beginTurn(turn("follow the bell", history)).toList()

        assertWarmSlotLifecycle(fake, listOf("acquire", "warmup", "turn"))
        coordinator.close()
        assertEquals(listOf(LlamaCppSlot(1)), fake.releasedSlots)
        assertEquals(listOf("acquire", "warmup", "turn", "release"), fake.slotLifecycle.map(SlotLifecycleEvent::operation))
    }

    @Test
    fun `following turn keeps the completed turn slot warm across prefill`() = runBlocking {
        val history = listOf(
            ChatEntry("player", "inspect the pier"),
            ChatEntry("game-master", "Fog covers the water."),
        )
        val fake = ControllableGateway()
        val coordinator = SessionPrefillCoordinator(fake, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        coordinator.beginTurn(turn("inspect the pier")).toList()
        coordinator.submit(prefill(1, "follow the bell", history))
        fake.awaitPrefill(1)
        fake.completePrefill(1)
        fake.awaitPrefillCompletion(1)
        coordinator.beginTurn(turn("follow the bell", history)).toList()

        assertWarmSlotLifecycle(fake, listOf("acquire", "turn", "prefill", "turn"))
        coordinator.close()
        assertEquals(listOf(LlamaCppSlot(1)), fake.releasedSlots)
        assertEquals(listOf("acquire", "turn", "prefill", "turn", "release"), fake.slotLifecycle.map(SlotLifecycleEvent::operation))
    }

    @Test
    fun `reranking keeps the completed turn slot warm through the following turn`() = runBlocking {
        val history = listOf(
            ChatEntry("player", "look"),
            ChatEntry("game-master", "The bell rings."),
        )
        val fake = ControllableGateway(
            classify = { "Y" },
            warmup = { CacheMetrics(null, null) },
        )
        val coordinator = SessionPrefillCoordinator(fake, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        coordinator.beginTurn(turn("look")).toList()
        assertEquals(PrefillDisposition.STARTED, coordinator.rerankAndWarmup(warmup(history), candidate(), "request-42"))
        fake.awaitWarmupCompletion()
        coordinator.submit(prefill(1, "follow the bell", history + ChatEntry("runtime-fragment", "fragment text")))
        fake.awaitPrefill(1)
        fake.completePrefill(1)
        fake.awaitPrefillCompletion(1)
        coordinator.beginTurn(turn("follow the bell", history + ChatEntry("runtime-fragment", "fragment text"))).toList()

        assertWarmSlotLifecycle(fake, listOf("acquire", "turn", "classify", "warmup", "prefill", "turn"))
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

    private fun prefill(revision: Long, action: String, history: List<ChatEntry> = emptyList()) = PrefillRequest(
        sessionId = "session",
        revision = revision,
        modelId = "model",
        llamaCppPort = 8081,
        scenarioMarkdown = "# Scenario",
        history = history,
        playerAction = action,
    )

    private fun turn(action: String, history: List<ChatEntry> = emptyList()) = TurnRequest(
        sessionId = "session",
        modelId = "model",
        llamaCppPort = 8081,
        scenarioMarkdown = "# Scenario",
        history = history,
        playerAction = action,
    )

    private fun warmup(history: List<ChatEntry> = emptyList()) = WarmupRequest(
        sessionId = "session",
        modelId = "model",
        llamaCppPort = 8081,
        scenarioMarkdown = "# Scenario",
        history = history,
    )

    private fun candidate() = RuntimeFragmentDefinition("candidate key", "fragment text")

    private fun PrefillRequest.toTurnRequestForTest() = TurnRequest(
        sessionId = sessionId,
        modelId = modelId,
        llamaCppPort = llamaCppPort,
        scenarioMarkdown = scenarioMarkdown,
        history = history,
        playerAction = playerAction,
        runtimeFragments = runtimeFragments,
    )

    private fun assertWarmSlotLifecycle(fake: ControllableGateway, operations: List<String>) {
        assertEquals(1, fake.acquiredSlots.size)
        assertEquals(operations, fake.slotLifecycle.map(SlotLifecycleEvent::operation))
        assertEquals(listOf(LlamaCppSlot(1)), fake.slotLifecycle.map(SlotLifecycleEvent::slot).distinct())
        assertTrue(fake.releasedSlots.isEmpty())
    }

    private data class SlotLifecycleEvent(val operation: String, val slot: LlamaCppSlot)

    private class ControllableGateway(
        private val stream: (TurnRequest) -> Flow<TurnStreamEvent> = { flowOf() },
        private val prefill: (suspend (TurnRequest) -> CacheMetrics)? = null,
        private val warmup: (suspend (WarmupRequest) -> CacheMetrics)? = null,
        private val worldState: (suspend (WorldStateResolutionRequest) -> WorldState?)? = null,
        private val classify: (suspend () -> String)? = null,
        private val onRelease: () -> Unit = {},
        private var releaseFailures: Int = 0,
    ) : PrefillGateway {
        private val prefillCompletion = mutableMapOf<Long, CompletableDeferred<Unit>>()
        private val prefillStarted = mutableMapOf<Long, CompletableDeferred<Unit>>()
        private val prefillFinished = mutableMapOf<Long, CompletableDeferred<Unit>>()
        val prefilledActions = mutableListOf<String>()
        val prefillRequests = mutableListOf<TurnRequest>()
        val turnedActions = mutableListOf<String>()
        val turnRequests = mutableListOf<TurnRequest>()
        val prefillSlots = mutableListOf<LlamaCppSlot>()
        val warmupSlots = mutableListOf<LlamaCppSlot>()
        val warmupRequests = mutableListOf<WarmupRequest>()
        val classifySlots = mutableListOf<LlamaCppSlot>()
        val worldStateSlots = mutableListOf<LlamaCppSlot>()
        val turnSlots = mutableListOf<LlamaCppSlot>()
        val acquiredSlots = mutableListOf<LlamaCppSlot>()
        val releasedSlots = mutableListOf<LlamaCppSlot>()
        val slotLifecycle = mutableListOf<SlotLifecycleEvent>()
        var releaseAttempts = 0
            private set

        override fun acquireIdleSlot(port: Int, excludedSlots: Set<LlamaCppSlot>): LlamaCppSlot =
            LlamaCppSlot(1).also {
                check(it !in excludedSlots) { "No idle slot" }
                acquiredSlots += it
                slotLifecycle += SlotLifecycleEvent("acquire", it)
            }

        override fun releaseSlot(port: Int, slot: LlamaCppSlot) {
            releaseAttempts++
            if (releaseFailures > 0) {
                releaseFailures--
                error("release failed")
            }
            releasedSlots += slot
            slotLifecycle += SlotLifecycleEvent("release", slot)
            onRelease()
        }

        override suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot): CacheMetrics {
            prefillRequests += request
            prefilledActions += request.playerAction
            prefillSlots += slot
            slotLifecycle += SlotLifecycleEvent("prefill", slot)
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

        override suspend fun warmup(request: WarmupRequest, slot: LlamaCppSlot): CacheMetrics {
            warmupRequests += request
            warmupSlots += slot
            slotLifecycle += SlotLifecycleEvent("warmup", slot)
            warmupStarted.complete(Unit)
            return try {
                warmup?.invoke(request) ?: run {
                    warmupCompletion.await()
                    CacheMetrics(null, null)
                }
            } finally {
                warmupFinished.complete(Unit)
            }
        }

        override suspend fun resolveWorldState(
            primaryRequest: TurnRequest,
            gameMasterResponse: String,
            request: WorldStateResolutionRequest,
            slot: LlamaCppSlot,
        ): WorldState? {
            worldStateSlots += slot
            slotLifecycle += SlotLifecycleEvent("world_state", slot)
            return worldState?.invoke(request)
        }

        override suspend fun classifyFragment(request: de.aidadventure.server.model.FragmentRelevanceRequest, slot: LlamaCppSlot): String {
            classifySlots += slot
            slotLifecycle += SlotLifecycleEvent("classify", slot)
            classifyStarted.complete(Unit)
            return try {
                classify?.invoke() ?: "N"
            } finally {
                classifyFinished.complete(Unit)
            }
        }

        override fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent> {
            turnRequests += request
            turnedActions += request.playerAction
            turnSlots += slot
            slotLifecycle += SlotLifecycleEvent("turn", slot)
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

        private val warmupStarted = CompletableDeferred<Unit>()
        private val warmupCompletion = CompletableDeferred<Unit>()
        private val warmupFinished = CompletableDeferred<Unit>()
        private val classifyStarted = CompletableDeferred<Unit>()
        private val classifyFinished = CompletableDeferred<Unit>()

        suspend fun awaitWarmup() = warmupStarted.await()

        fun completeWarmup() {
            warmupCompletion.complete(Unit)
        }

        suspend fun awaitWarmupCompletion() = warmupFinished.await()

        suspend fun awaitClassifyCompletion() = classifyFinished.await()

        private fun requestRevision(request: TurnRequest): Long = when (request.playerAction) {
            "open" -> 1
            "open the" -> 2
            "open the door" -> 3
            "look" -> 4
            "follow the bell" -> 1
            else -> error("Unexpected action")
        }
    }
}
