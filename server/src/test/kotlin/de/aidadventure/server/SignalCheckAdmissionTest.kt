package de.aidadventure.server

import de.aidadventure.server.llamacpp.*
import de.aidadventure.server.model.*
import de.aidadventure.server.prefill.*
import de.aidadventure.server.runtimefragments.RuntimeFragmentResolver
import de.aidadventure.server.worldstate.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class SignalCheckAdmissionTest {
    @Test fun `gate installed after HTTP snapshot updates generation eligibility and final completion`() = testApplication {
        val checkEntered = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        val snapshotTaken = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val gateway = CheckGateway(checkEntered, releaseCheck)
        val actual = SessionPrefillCoordinator(gateway)
        val coordinator = object : PrefillCoordinator by actual {
            override suspend fun awaitPostTurn(request: TurnRequest): TurnRequest {
                val snapshot = actual.awaitPostTurn(request)
                if (request.playerAction == "following") {
                    snapshotTaken.complete(Unit)
                    releaseSnapshot.await()
                }
                return snapshot
            }
        }
        application { adventureModule(prefillRegistry = registry(coordinator), runtimeFragmentResolver = noFragments()) }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        try {
            coroutineScope {
                val following = async { jsonClient.post("/api/turn") { contentType(ContentType.Application.Json); setBody(request("following")) }.bodyAsText() }
                withTimeout(2_000) { snapshotTaken.await() }
                val origin = async { jsonClient.post("/api/turn") { contentType(ContentType.Application.Json); setBody(request("origin")) }.bodyAsText() }
                withTimeout(2_000) { checkEntered.await() }
                releaseSnapshot.complete(Unit)
                yield()
                releaseCheck.complete(Unit)
                assertContains(withTimeout(2_000) { origin.await() }.substringAfter("event: done"), "\"visited\":true")
                val body = withTimeout(2_000) { following.await() }
                assertEquals(mapOf("visited" to true), gateway.turns.last().flags)
                assertEquals(1, gateway.checks.size)
                assertContains(body.substringAfter("event: done"), "\"visited\":true")
            }
        } finally { actual.close() }
    }

    @Test fun `cancelled signal waiter settles held checks before releasing overlapping HTTP turn`() = testApplication {
        val checkEntered = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        val cancellationReached = CompletableDeferred<Unit>()
        val gateway = CheckGateway(checkEntered, releaseCheck)
        val actual = SessionPrefillCoordinator(gateway)
        var cancellationReservation: RerankReservation? = null
        val coordinator = object : PrefillCoordinator by actual {
            override suspend fun reserveRerank(request: WarmupRequest, requestId: String): RerankReservation? =
                actual.reserveRerank(request, requestId).also { if (request.history.any { it.content == "origin" }) cancellationReservation = it }
            override suspend fun awaitSignalChecks(reservation: RerankReservation): de.aidadventure.server.runtimefragments.SignalCheckOutcome {
                if (reservation === cancellationReservation) {
                    checkEntered.await()
                    cancellationReached.complete(Unit)
                    throw CancellationException("cancel originating SSE waiter")
                }
                return actual.awaitSignalChecks(reservation)
            }
        }
        application { adventureModule(prefillRegistry = registry(coordinator), runtimeFragmentResolver = noFragments()) }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        try {
            coroutineScope {
                val origin = async { runCatching { jsonClient.post("/api/turn") { contentType(ContentType.Application.Json); setBody(request("origin")) }.bodyAsText() } }
                withTimeout(2_000) { cancellationReached.await() }
                val following = async { jsonClient.post("/api/turn") { contentType(ContentType.Application.Json); setBody(request("following")) }.bodyAsText() }
                yield()
                releaseCheck.complete(Unit)
                withTimeout(2_000) { origin.await() }
                val body = withTimeout(2_000) { following.await() }
                assertEquals(mapOf("visited" to true), gateway.turns.last().flags)
                assertEquals(1, gateway.checks.size)
                assertContains(body.substringAfter("event: done"), "\"visited\":true")
            }
        } finally { actual.close() }
    }

    private fun request(action: String) = TurnRequest("live", scenarioMarkdown = "# Game", history = emptyList(), playerAction = action,
        flags = mapOf("visited" to false), signals = mapOf("enter" to listOf(FlagAssignment("visited", true))),
        signalChecks = listOf(SignalCheckDefinition("enter", "Entered lighthouse", listOf(FlagCondition("visited", false)))))

    private fun registry(coordinator: PrefillCoordinator) = object : PrefillRegistry {
        override fun coordinatorFor(key: CoordinatorKey) = coordinator
        override suspend fun remove(key: CoordinatorKey) = coordinator.close()
        override suspend fun removeIdle(nowMillis: Long) = Unit
    }
    private fun noFragments() = object : RuntimeFragmentResolver {
        override suspend fun prepare(definitions: List<RuntimeFragmentDefinition>) = Unit
        override suspend fun resolve(history: List<ChatEntry>, definitions: List<RuntimeFragmentDefinition>): RuntimeFragmentDefinition? = null
    }
    private class CheckGateway(val entered: CompletableDeferred<Unit>, val release: CompletableDeferred<Unit>) : PrefillGateway {
        val turns = mutableListOf<TurnRequest>()
        val checks = mutableListOf<WarmupRequest>()
        override fun acquireIdleSlot(port: Int, excludedSlots: Set<LlamaCppSlot>) = LlamaCppSlot(7)
        override fun releaseSlot(port: Int, slot: LlamaCppSlot) = Unit
        override suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot) = CacheMetrics(4, 1)
        override fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent> {
            turns += request
            return flowOf(TurnStreamEvent.Token("You enter the lighthouse."), TurnStreamEvent.Done(TurnMetrics(1, 4, 1)))
        }
        override suspend fun resolveWorldState(primaryRequest: TurnRequest, gameMasterResponse: String,
            request: WorldStateResolutionRequest, slot: LlamaCppSlot) = WorldState("Lighthouse", "Keeper")
        override suspend fun checkSignal(request: WarmupRequest, definition: SignalCheckDefinition, slot: LlamaCppSlot,
            onCall: (StreamDiagnostic) -> Unit): String {
            checks += request
            entered.complete(Unit)
            release.await()
            return if (checks.size == 1) "Y" else "N"
        }
        override suspend fun warmup(request: WarmupRequest, slot: LlamaCppSlot) = CacheMetrics(12, 2)
    }
}
