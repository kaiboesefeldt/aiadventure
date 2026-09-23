package de.aidadventure.server.prefill

import de.aidadventure.server.ResolvedTurn
import de.aidadventure.server.llamacpp.CacheMetrics
import de.aidadventure.server.llamacpp.LlamaCppGateway
import de.aidadventure.server.llamacpp.LlamaCppSlot
import de.aidadventure.server.llamacpp.escapeForLlmTrace
import de.aidadventure.server.model.ContextCompression
import de.aidadventure.server.model.PrefillDisposition
import de.aidadventure.server.model.PrefillRequest
import de.aidadventure.server.model.FragmentRelevanceRequest
import de.aidadventure.server.model.RuntimeFragmentDefinition
import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import de.aidadventure.server.model.WarmupRequest
import de.aidadventure.server.worldstate.WorldState
import de.aidadventure.server.worldstate.WorldStateResolutionRequest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val coordinatorLogger = LoggerFactory.getLogger(SessionPrefillCoordinator::class.java)

private fun PrefillRequest.asTurnRequest() = TurnRequest(
    sessionId = sessionId,
    modelId = modelId,
    llamaCppPort = llamaCppPort,
    scenarioMarkdown = scenarioMarkdown,
    history = history,
    playerAction = playerAction,
    runtimeFragments = runtimeFragments,
    worldState = worldState,
    contextCompression = contextCompression,
    locations = locations,
)

data class CoordinatorKey(val sessionId: String, val modelId: String, val port: Int)

class RerankReservation internal constructor(internal val generation: Long) {
    private val candidate = CompletableDeferred<RuntimeFragmentDefinition?>()

    internal suspend fun awaitCandidate(): RuntimeFragmentDefinition? = candidate.await()
    internal fun complete(candidate: RuntimeFragmentDefinition?): Boolean = this.candidate.complete(candidate)
}

internal data class CoordinatorLeaseDiagnostic(
    val coordinatorId: String,
    val transition: String,
    val slotId: Int,
    val port: Int,
)

internal data class RerankDiagnostic(
    val response: String?,
    val decision: Boolean?,
    val accepted: Boolean,
)

internal data class WarmupFailure(
    val requestId: String,
    val operation: String,
    val failureCategory: String,
)

interface PrefillCoordinator {
    suspend fun submit(request: PrefillRequest): PrefillDisposition
    suspend fun submit(request: PrefillRequest, resolvedTurn: ResolvedTurn): PrefillDisposition = submit(request)
    suspend fun warmup(
        request: WarmupRequest,
        requestId: String = "unknown",
    ): PrefillDisposition = error("Warmup is not supported by this coordinator.")
    suspend fun rerankAndWarmup(
        request: WarmupRequest,
        candidate: RuntimeFragmentDefinition,
        requestId: String = "unknown",
    ): PrefillDisposition = error("Fragment reranking is not supported by this coordinator.")
    suspend fun reserveRerank(request: WarmupRequest, requestId: String = "unknown"): RerankReservation? = null
    suspend fun completeReservedRerank(
        reservation: RerankReservation,
        candidate: RuntimeFragmentDefinition?,
    ): Boolean = false
    suspend fun resolveWorldStateAndWarmup(
        primaryRequest: TurnRequest,
        gameMasterResponse: String,
        request: WarmupRequest,
        resolution: WorldStateResolutionRequest,
        requestId: String = "unknown",
    ): WorldState = request.worldState
    suspend fun resolvedTurnFor(request: TurnRequest): ResolvedTurn? = null
    suspend fun beginTurn(request: TurnRequest, requestId: String): Flow<TurnStreamEvent> = beginTurn(request)
    suspend fun beginTurn(request: TurnRequest): Flow<TurnStreamEvent>
    fun tryBeginIdleClose(): Boolean
    suspend fun close()
}

internal interface PrefillGateway {
    suspend fun prepare(request: TurnRequest, slot: LlamaCppSlot): TurnRequest = request
    suspend fun prepareWarmup(request: WarmupRequest, slot: LlamaCppSlot): WarmupRequest = request
    suspend fun maintain(request: WarmupRequest, slot: LlamaCppSlot): ContextCompression = request.contextCompression
    fun acquireIdleSlot(port: Int, excludedSlots: Set<LlamaCppSlot>): LlamaCppSlot
    fun releaseSlot(port: Int, slot: LlamaCppSlot)
    suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot): CacheMetrics
    suspend fun warmup(request: WarmupRequest, slot: LlamaCppSlot): CacheMetrics =
        error("Warmup is not supported by this gateway.")
    suspend fun classifyFragment(request: FragmentRelevanceRequest, slot: LlamaCppSlot): String =
        error("Fragment classification is not supported by this gateway.")
    suspend fun resolveWorldState(
        primaryRequest: TurnRequest,
        gameMasterResponse: String,
        request: WorldStateResolutionRequest,
        slot: LlamaCppSlot,
    ): WorldState? =
        error("World-State resolution is not supported by this gateway.")
    fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent>
}

internal class LlamaCppPrefillGateway(
    private val gateway: LlamaCppGateway = LlamaCppGateway(),
) : PrefillGateway {
    override suspend fun prepare(request: TurnRequest, slot: LlamaCppSlot) = gateway.prepare(request, slot)
    override suspend fun prepareWarmup(request: WarmupRequest, slot: LlamaCppSlot) = gateway.prepareWarmup(request, slot)
    override suspend fun maintain(request: WarmupRequest, slot: LlamaCppSlot) = gateway.maintain(request, slot)

    override fun acquireIdleSlot(port: Int, excludedSlots: Set<LlamaCppSlot>): LlamaCppSlot =
        gateway.acquireIdleSlot(port, excludedSlots)

    override fun releaseSlot(port: Int, slot: LlamaCppSlot) = gateway.releaseSlot(port, slot)

    override suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot): CacheMetrics = gateway.prefill(request, slot)

    override suspend fun warmup(request: WarmupRequest, slot: LlamaCppSlot): CacheMetrics = gateway.warmup(request, slot)

    override suspend fun classifyFragment(request: FragmentRelevanceRequest, slot: LlamaCppSlot): String =
        gateway.classifyFragment(request, slot)

    override suspend fun resolveWorldState(
        primaryRequest: TurnRequest,
        gameMasterResponse: String,
        request: WorldStateResolutionRequest,
        slot: LlamaCppSlot,
    ): WorldState? = gateway.resolveWorldState(primaryRequest, gameMasterResponse, request, slot)

    override fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent> = gateway.stream(request, slot)
}

internal class SessionPrefillCoordinator(
    private val gateway: PrefillGateway,
    private val slotLeases: PrefillSlotLeaseManager = PrefillSlotLeaseManager(gateway),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onWarmupFailure: (WarmupFailure) -> Unit = { failure ->
        logger.warn(
            "requestId={} operation={} result=llama_cpp_unavailable cause={}",
            failure.requestId,
            failure.operation,
            failure.failureCategory,
        )
    },
    private val diagnosticSink: (CoordinatorLeaseDiagnostic) -> Unit = { diagnostic ->
        coordinatorLogger.debug(
            "coordinator={} transition={} slot={} port={}",
            diagnostic.coordinatorId,
            diagnostic.transition,
            diagnostic.slotId,
            diagnostic.port,
        )
    },
    private val rerankDiagnosticSink: (RerankDiagnostic) -> Unit = { diagnostic ->
        coordinatorLogger.trace(
            "operation=rerank response=\"{}\" decision={} accepted={}",
            diagnostic.response?.escapeForLlmTrace(),
            diagnostic.decision,
            diagnostic.accepted,
        )
    },
) : PrefillCoordinator {
    private val mutex = Mutex()
    private val coordinatorId = Integer.toHexString(System.identityHashCode(this))
    private var latestRevision = Long.MIN_VALUE
    private var generation = 0L
    private var closed = false
    private var shutdownComplete = false
    private var nextFragment: String? = null
    private var latestContextCompression = ContextCompression()
    private var state: State = State.Idle

    override suspend fun submit(request: PrefillRequest): PrefillDisposition = mutex.withLock {
        submitLocked(request, request.asTurnRequest().withNextFragment())
    }

    override suspend fun submit(request: PrefillRequest, resolvedTurn: ResolvedTurn): PrefillDisposition = mutex.withLock {
        submitLocked(request, resolvedTurn)
    }

    private fun submitLocked(request: PrefillRequest, resolvedTurn: ResolvedTurn): PrefillDisposition {
        if (request.contextCompression.epoch < latestContextCompression.epoch) return PrefillDisposition.STALE
        if (closed || state is State.Turning || state is State.TurnWaiting) {
            return PrefillDisposition.TURN_IN_PROGRESS
        }
        if (request.revision <= latestRevision) return PrefillDisposition.STALE

        latestRevision = request.revision
        return when (val current = state) {
            State.Idle -> {
                startPrefillLocked(request, resolvedTurn)
                PrefillDisposition.STARTED
            }

            is State.Prefilling -> {
                state = current.copy(pending = PendingPrefill(request, resolvedTurn))
                PrefillDisposition.QUEUED
            }

            is State.Ready -> {
                startPrefillLocked(request, resolvedTurn, current.lease)
                PrefillDisposition.STARTED
            }

            is State.ReleasePending -> {
                releaseLeaseLocked(current.lease)
                state = State.Idle
                startPrefillLocked(request, resolvedTurn)
                PrefillDisposition.STARTED
            }

            is State.Turning, is State.TurnWaiting -> PrefillDisposition.TURN_IN_PROGRESS
        }
    }

    override suspend fun resolvedTurnFor(request: TurnRequest): ResolvedTurn? = mutex.withLock {
        state.resolvedTurnOrNull()?.takeIf { it.matches(request) }?.forRequest(request)
            ?: nextFragment?.let { request.withNextFragment() }
    }

    override suspend fun warmup(request: WarmupRequest, requestId: String): PrefillDisposition = mutex.withLock {
        if (request.contextCompression.epoch < latestContextCompression.epoch) return@withLock PrefillDisposition.STALE
        if (closed || state is State.Turning || state is State.TurnWaiting) {
            return@withLock PrefillDisposition.TURN_IN_PROGRESS
        }

        when (val current = state) {
            State.Idle -> {
                startWarmupLocked(request, requestId)
                PrefillDisposition.STARTED
            }

            is State.Prefilling -> PrefillDisposition.STALE

            is State.Ready -> {
                startWarmupLocked(request, requestId, current.lease)
                PrefillDisposition.STARTED
            }

            is State.ReleasePending -> {
                releaseLeaseLocked(current.lease)
                state = State.Idle
                startWarmupLocked(request, requestId)
                PrefillDisposition.STARTED
            }

            is State.Turning, is State.TurnWaiting -> PrefillDisposition.TURN_IN_PROGRESS
        }
    }

    override suspend fun resolveWorldStateAndWarmup(
        primaryRequest: TurnRequest,
        gameMasterResponse: String,
        request: WarmupRequest,
        resolution: WorldStateResolutionRequest,
        requestId: String,
    ): WorldState {
        val operation = mutex.withLock {
            check(!closed) { "Prefill coordinator is closed." }
            val current = state as? State.Ready ?: error("World-State resolution requires a retained turn slot.")
            val worldStateOperation = WorldStateOperation(++generation)
            state = State.Prefilling(current.lease, worldStateOperation, resolvedTurn = null, pending = null)
            worldStateOperation.job = scope.launch(start = CoroutineStart.LAZY) {
                val accepted = try {
                    gateway.resolveWorldState(primaryRequest, gameMasterResponse, resolution, current.lease.slot) ?: request.worldState
                } catch (_: CancellationException) {
                    throw CancellationException("World-State resolution cancelled.")
                } catch (failure: Throwable) {
                    onWarmupFailure(WarmupFailure(requestId, "world_state", failure.javaClass.simpleName))
                    request.worldState
                }
                val canonicalWarmup = request.copy(worldState = accepted)
                worldStateOperation.acceptedState = accepted
                try {
                    gateway.warmup(canonicalWarmup, current.lease.slot)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    onWarmupFailure(WarmupFailure(requestId, "warmup", failure.javaClass.simpleName))
                } finally {
                    worldStateOperation.result.complete(accepted)
                    prefillFinished(current.lease, worldStateOperation, null)
                }
            }
            worldStateOperation.job.start()
            worldStateOperation
        }
        return operation.result.await()
    }

    override suspend fun rerankAndWarmup(
        request: WarmupRequest,
        candidate: RuntimeFragmentDefinition,
        requestId: String,
    ): PrefillDisposition = mutex.withLock {
        if (closed || state is State.Turning || state is State.TurnWaiting) {
            return@withLock PrefillDisposition.TURN_IN_PROGRESS
        }

        when (val current = state) {
            State.Idle -> {
                startRerankingLocked(request, requestId).reservation.complete(candidate)
                PrefillDisposition.STARTED
            }

            is State.Prefilling -> PrefillDisposition.STALE

            is State.Ready -> {
                startRerankingLocked(request, requestId, current.lease).reservation.complete(candidate)
                PrefillDisposition.STARTED
            }

            is State.ReleasePending -> {
                releaseLeaseLocked(current.lease)
                state = State.Idle
                startRerankingLocked(request, requestId).reservation.complete(candidate)
                PrefillDisposition.STARTED
            }

            is State.Turning, is State.TurnWaiting -> PrefillDisposition.TURN_IN_PROGRESS
        }
    }

    override suspend fun reserveRerank(request: WarmupRequest, requestId: String): RerankReservation? = mutex.withLock {
        if (closed || state is State.Turning || state is State.TurnWaiting || state is State.Prefilling) {
            return@withLock null
        }

        val operation = when (val current = state) {
            State.Idle -> startRerankingLocked(request, requestId)
            is State.Ready -> startRerankingLocked(request, requestId, current.lease)
            is State.ReleasePending -> {
                releaseLeaseLocked(current.lease)
                state = State.Idle
                startRerankingLocked(request, requestId)
            }

            is State.Prefilling, is State.Turning, is State.TurnWaiting -> return@withLock null
        }
        operation.reservation
    }

    override suspend fun completeReservedRerank(
        reservation: RerankReservation,
        candidate: RuntimeFragmentDefinition?,
    ): Boolean = mutex.withLock {
        val operation = when (val current = state) {
            is State.Prefilling -> current.operation
            is State.TurnWaiting -> current.operation
            else -> return@withLock false
        } as? RerankingOperation ?: return@withLock false
        if (operation.reservation !== reservation) return@withLock false
        reservation.complete(candidate)
    }

    override suspend fun beginTurn(request: TurnRequest): Flow<TurnStreamEvent> = beginTurn(request, "unknown")

    override suspend fun beginTurn(request: TurnRequest, requestId: String): Flow<TurnStreamEvent> = mutex.withLock {
        if (closed) return@withLock rejectedTurn("Prefill coordinator is closed.")

        when (val current = state) {
            State.Idle -> startTurnLocked(request.withNextFragment().request, requestId = requestId)
            is State.Prefilling -> {
                val execution = TurnExecution(requestId).also(TurnExecution::initialize)
                state = State.TurnWaiting(current.lease, current.operation, current.resolvedTurn, request, execution)
                execution.flow()
            }

            is State.Ready -> {
                val resolved = current.resolvedTurn?.takeIf { it.matches(request) }?.forRequest(request)
                    ?: request.withNextFragment()
                startTurnLocked(resolved.request, current.lease, requestId)
            }
            is State.ReleasePending -> {
                releaseLeaseLocked(current.lease)
                state = State.Idle
                startTurnLocked(request.withNextFragment().request, requestId = requestId)
            }

            is State.TurnWaiting, is State.Turning -> rejectedTurn("A turn is already in progress for this coordinator.")
        }
    }

    override fun tryBeginIdleClose(): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            if (closed || !state.isIdleForTtl()) {
                false
            } else {
                closed = true
                generation++
                true
            }
        } finally {
            mutex.unlock()
        }
    }

    override suspend fun close() {
        val shutdown = mutex.withLock {
            if (shutdownComplete) return
            if (!closed) {
                closed = true
                generation++
            }
            Shutdown(state.activeLeaseOrNull(), state.activeJobs(), state.turnExecutionOrNull())
        }

        shutdown.turnExecution?.cancel(CancellationException("Prefill coordinator closed."))
        shutdown.jobs.forEach(Job::cancel)
        shutdown.jobs.joinAll()

        val releaseFailure = mutex.withLock {
            try {
                shutdown.lease?.let(::releaseLeaseLocked)
                state = State.Idle
                shutdownComplete = true
                null
            } catch (failure: Throwable) {
                shutdown.lease?.let { state = State.ReleasePending(it) }
                failure
            }
        }
        scope.cancel()
        releaseFailure?.let { throw it }
    }

    private fun startPrefillLocked(
        request: PrefillRequest,
        resolvedTurn: ResolvedTurn,
        existingLease: Lease? = null,
    ) {
        val lease = existingLease ?: acquireLease(request.llamaCppPort)
        val operation = PrefillOperation(++generation)
        state = State.Prefilling(lease, operation, resolvedTurn, pending = null)
        operation.job = scope.launch(start = CoroutineStart.LAZY) {
            val failure = try {
                gateway.prefill(prepareTurn(resolvedTurn.request, lease.slot), lease.slot)
                null
            } catch (_: CancellationException) {
                return@launch
            } catch (failure: Throwable) {
                failure
            }
            prefillFinished(lease, operation, failure)
        }
        operation.job.start()
    }

    private fun startWarmupLocked(
        request: WarmupRequest,
        requestId: String,
        existingLease: Lease? = null,
    ) {
        val lease = existingLease ?: acquireLease(request.llamaCppPort)
        val operation = PrefillOperation(++generation)
        state = State.Prefilling(lease, operation, resolvedTurn = null, pending = null)
        operation.job = scope.launch(start = CoroutineStart.LAZY) {
            val failure = try {
                gateway.warmup(prepareWarmup(request, lease.slot), lease.slot)
                null
            } catch (_: CancellationException) {
                return@launch
            } catch (failure: Throwable) {
                failure
            }
            prefillFinished(lease, operation, failure)
            failure?.let { onWarmupFailure(WarmupFailure(requestId, "warmup", it.javaClass.simpleName)) }
        }
        operation.job.start()
    }

    private fun startRerankingLocked(
        request: WarmupRequest,
        requestId: String,
        existingLease: Lease? = null,
    ): RerankingOperation {
        nextFragment = null
        val lease = existingLease ?: acquireLease(request.llamaCppPort)
        val operation = RerankingOperation(RerankReservation(++generation))
        state = State.Prefilling(lease, operation, resolvedTurn = null, pending = null)
        operation.job = scope.launch(start = CoroutineStart.LAZY) {
            val failure = try {
                val candidate = operation.reservation.awaitCandidate()
                val prepared = prepareWarmup(request, lease.slot)
                val response = candidate?.let {
                    val relevanceRequest = FragmentRelevanceRequest(
                        sessionId = request.sessionId,
                        modelId = request.modelId,
                        llamaCppPort = request.llamaCppPort,
                        scenarioMarkdown = request.scenarioMarkdown,
                        history = request.history,
                        candidateKey = it.matchText,
                        worldState = prepared.worldState,
                        contextCompression = prepared.contextCompression,
                        locations = request.locations,
                    )
                    gateway.classifyFragment(relevanceRequest, lease.slot)
                }
                val decision = response?.let(::parseFragmentDecision)
                val accepted = candidate != null && decision == true
                operation.resolvedFragment = candidate?.takeIf { accepted }?.fragment
                rerankDiagnosticSink(RerankDiagnostic(response, decision, accepted))
                val canonicalWarmup = prepared.copy(
                    history = candidate?.takeIf { accepted }
                        ?.let { request.history + ChatEntry("runtime-fragment", it.fragment) }
                        ?: request.history,
                )
                gateway.warmup(canonicalWarmup, lease.slot)
                null
            } catch (_: CancellationException) {
                return@launch
            } catch (failure: Throwable) {
                failure
            }
            prefillFinished(lease, operation, failure)
            failure?.let { onWarmupFailure(WarmupFailure(requestId, "rerank", it.javaClass.simpleName)) }
        }
        operation.job.start()
        return operation
    }

    private fun startTurnLocked(request: TurnRequest, existingLease: Lease? = null, requestId: String = "unknown"): Flow<TurnStreamEvent> {
        val lease = existingLease ?: acquireLease(request.llamaCppPort)
        val execution = TurnExecution(requestId).also(TurnExecution::initialize)
        state = State.Turning(lease, execution)
        recordLeaseTransition("turn_handoff", lease)
        execution.handoff(lease, request)
        return execution.flow()
    }

    private suspend fun prefillFinished(lease: Lease, operation: Operation, failure: Throwable?) {
        mutex.withLock {
            if (closed || operation.generation != generation) return@withLock

            when (val current = state) {
                is State.Prefilling -> if (current.operation === operation && current.lease === lease) {
                    if (failure != null) {
                        generation++
                        releaseAfterFailureLocked(lease)
                    } else if (current.pending != null) {
                        val pending = current.pending
                        if (operation is RerankingOperation) nextFragment = operation.resolvedFragment
                        val resolved = if (operation is RerankingOperation) {
                            pending.request.asTurnRequest().withNextFragment()
                        } else if (operation is WorldStateOperation) {
                            val acceptedState = operation.acceptedState ?: pending.resolvedTurn.request.worldState
                            pending.resolvedTurn.copy(
                                request = pending.resolvedTurn.request.copy(worldState = acceptedState),
                            )
                        } else {
                            pending.resolvedTurn
                        }
                        startPrefillLocked(pending.request, resolved, lease)
                    } else {
                        if (operation is RerankingOperation) nextFragment = operation.resolvedFragment
                        state = State.Ready(lease, current.resolvedTurn)
                    }
                }

                is State.TurnWaiting -> if (current.operation === operation && current.lease === lease) {
                    if (failure != null) {
                        generation++
                        val releaseFailure = releaseAfterFailureLocked(lease)
                        current.execution.fail(releaseFailure ?: failure)
                    } else {
                        if (operation is RerankingOperation) nextFragment = operation.resolvedFragment
                        val resolved = current.resolvedTurn?.takeIf { it.matches(current.request) }?.forRequest(current.request)
                            ?: current.request.withNextFragment()
                        state = State.Turning(lease, current.execution)
                        recordLeaseTransition("turn_handoff", lease)
                        current.execution.handoff(lease, resolved.request)
                    }
                }

                else -> Unit
            }
        }
    }

    private suspend fun turnFinished(execution: TurnExecution, lease: Lease?): Throwable? = mutex.withLock {
        if (closed) return@withLock null

        when (val current = state) {
            is State.TurnWaiting -> if (current.execution === execution) {
                state = State.Prefilling(current.lease, current.operation, current.resolvedTurn, pending = null)
                null
            } else {
                null
            }

            is State.Turning -> if (current.execution === execution && current.lease === lease) {
                nextFragment = null
                state = State.Ready(current.lease, resolvedTurn = null)
                recordLeaseTransition("turn_retained", current.lease)
                null
            } else {
                null
            }

            else -> null
        }
    }

    private fun releaseAfterFailureLocked(lease: Lease): Throwable? = try {
        releaseLeaseLocked(lease)
        state = State.Idle
        null
    } catch (failure: Throwable) {
        state = State.ReleasePending(lease)
        failure
    }

    private fun acquireLease(port: Int): Lease {
        return Lease(port, slotLeases.acquire(port)).also { recordLeaseTransition("lease_acquired", it) }
    }

    private fun recordLeaseTransition(transition: String, lease: Lease) {
        diagnosticSink(CoordinatorLeaseDiagnostic(coordinatorId, transition, lease.slot.id, lease.port))
    }

    private fun releaseLeaseLocked(lease: Lease) {
        if (lease.released) return
        slotLeases.release(lease.port, lease.slot)
        lease.released = true
        recordLeaseTransition("lease_released", lease)
    }

    private fun rejectedTurn(message: String): Flow<TurnStreamEvent> = flow {
        throw IllegalStateException(message)
    }

    private fun ResolvedTurn.matches(request: TurnRequest): Boolean {
        val visibleHistory = if (fragment == null) this.request.history else this.request.history.dropLast(1)
        return this.request.sessionId == request.sessionId &&
            this.request.modelId == request.modelId &&
            this.request.llamaCppPort == request.llamaCppPort &&
            this.request.scenarioMarkdown == request.scenarioMarkdown &&
            visibleHistory == request.history &&
            this.request.playerAction == request.playerAction &&
            this.request.runtimeFragments == request.runtimeFragments &&
            this.request.worldState == request.worldState &&
            this.request.contextCompression == request.contextCompression
    }

    private fun ResolvedTurn.forRequest(request: TurnRequest): ResolvedTurn = copy(
        request = request.copy(history = this.request.history),
    )

    private fun TurnRequest.withNextFragment(): ResolvedTurn = nextFragment?.let { fragment ->
        ResolvedTurn(copy(history = history + ChatEntry("runtime-fragment", fragment)), fragment)
    } ?: ResolvedTurn(this, null)

    private suspend fun prepareTurn(request: TurnRequest, slot: LlamaCppSlot): TurnRequest {
        val reconciled = mutex.withLock {
            if (request.contextCompression.epoch < latestContextCompression.epoch) request.copy(contextCompression = latestContextCompression) else request
        }
        return gateway.prepare(reconciled, slot).also { prepared ->
            mutex.withLock { if (prepared.contextCompression.epoch >= latestContextCompression.epoch) latestContextCompression = prepared.contextCompression }
        }
    }

    private suspend fun prepareWarmup(request: WarmupRequest, slot: LlamaCppSlot): WarmupRequest {
        val reconciled = mutex.withLock {
            if (request.contextCompression.epoch < latestContextCompression.epoch) request.copy(contextCompression = latestContextCompression) else request
        }
        return gateway.prepareWarmup(reconciled, slot).also { prepared ->
            mutex.withLock { if (prepared.contextCompression.epoch >= latestContextCompression.epoch) latestContextCompression = prepared.contextCompression }
        }
    }

    private inner class TurnExecution(private val requestId: String) {
        private val ready = CompletableDeferred<Lease>()
        private val output = Channel<TurnStreamEvent>(Channel.RENDEZVOUS)
        private val collected = AtomicBoolean()
        private lateinit var request: TurnRequest
        lateinit var job: Job

        fun initialize() {
            job = scope.launch(start = CoroutineStart.LAZY) {
                var lease: Lease? = null
                var streamFailure: Throwable? = null
                try {
                    lease = ready.await()
                    request.history.lastOrNull()
                        ?.takeIf { it.role == "runtime-fragment" }
                        ?.let { output.send(TurnStreamEvent.Fragment(it.content)) }
                    request = prepareTurn(request, lease.slot)
                    output.send(TurnStreamEvent.GenerationReady)
                    val generated = StringBuilder()
                    var done: TurnStreamEvent.Done? = null
                    gateway.stream(request, lease.slot).collect { event ->
                        when (event) {
                            is TurnStreamEvent.Token -> { generated.append(event.text); output.send(event) }
                            is TurnStreamEvent.Done -> done = event
                            else -> output.send(event)
                        }
                    }
                    done?.let { completed ->
                        output.send(TurnStreamEvent.InputReady)
                        val warmup = WarmupRequest(request.sessionId, request.modelId, request.llamaCppPort,
                            request.scenarioMarkdown, request.history + listOf(ChatEntry("player", request.playerAction), ChatEntry("game-master", generated.toString())),
                            request.runtimeFragments, request.worldState, request.contextCompression, request.locations)
                        val artifact = try {
                            gateway.maintain(warmup, lease.slot)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            onWarmupFailure(WarmupFailure(requestId, "context_recap", failure.javaClass.simpleName))
                            request.contextCompression
                        }
                        output.send(completed.copy(contextCompression = artifact))
                    }
                } catch (failure: Throwable) {
                    streamFailure = failure
                }

                val releaseFailure = withContext(NonCancellable) {
                    turnFinished(this@TurnExecution, lease)
                }
                val terminalFailure = streamFailure ?: releaseFailure
                if (terminalFailure == null) output.close() else output.close(terminalFailure)
            }
        }

        fun flow(): Flow<TurnStreamEvent> = flow {
            check(collected.compareAndSet(false, true)) { "Turn flow can only be collected once." }
            job.start()
            try {
                for (event in output) emit(event)
            } finally {
                withContext(NonCancellable) {
                    cancelAndJoin(CancellationException("Turn collector stopped."))
                }
            }
        }

        fun handoff(lease: Lease, request: TurnRequest) {
            this.request = request
            ready.complete(lease)
        }

        fun fail(failure: Throwable) {
            ready.completeExceptionally(failure)
        }

        fun cancel(cause: CancellationException) {
            output.close(cause)
            job.cancel(cause)
        }

        private suspend fun cancelAndJoin(cause: CancellationException) {
            cancel(cause)
            job.cancelAndJoin()
        }
    }

    private sealed interface State {
        data object Idle : State
        data class Prefilling(
            val lease: Lease,
            val operation: Operation,
            val resolvedTurn: ResolvedTurn?,
            val pending: PendingPrefill?,
        ) : State

        data class Ready(val lease: Lease, val resolvedTurn: ResolvedTurn?) : State
        data class TurnWaiting(
            val lease: Lease,
            val operation: Operation,
            val resolvedTurn: ResolvedTurn?,
            val request: TurnRequest,
            val execution: TurnExecution,
        ) : State

        data class Turning(val lease: Lease, val execution: TurnExecution) : State
        data class ReleasePending(val lease: Lease) : State

        fun activeLeaseOrNull(): Lease? = when (this) {
            Idle -> null
            is Prefilling -> lease
            is Ready -> lease
            is TurnWaiting -> lease
            is Turning -> lease
            is ReleasePending -> lease
        }

        fun activeJobs(): List<Job> = when (this) {
            is Prefilling -> listOf(operation.job)
            is TurnWaiting -> listOf(operation.job, execution.job)
            is Turning -> listOf(execution.job)
            Idle, is Ready, is ReleasePending -> emptyList()
        }

        fun turnExecutionOrNull(): TurnExecution? = when (this) {
            is TurnWaiting -> execution
            is Turning -> execution
            else -> null
        }

        fun isIdleForTtl(): Boolean = this is Idle || this is Ready || this is ReleasePending

        fun resolvedTurnOrNull(): ResolvedTurn? = when (this) {
            is Prefilling -> resolvedTurn
            is Ready -> resolvedTurn
            is TurnWaiting -> resolvedTurn
            Idle, is Turning, is ReleasePending -> null
        }
    }

    private class Lease(val port: Int, val slot: LlamaCppSlot) {
        var released = false
    }

    private interface Operation {
        val generation: Long
        var job: Job
    }

    private class PrefillOperation(override val generation: Long) : Operation {
        override lateinit var job: Job
    }

    private class RerankingOperation(val reservation: RerankReservation) : Operation {
        override val generation: Long get() = reservation.generation
        override lateinit var job: Job
        var resolvedFragment: String? = null
    }

    private class WorldStateOperation(override val generation: Long) : Operation {
        override lateinit var job: Job
        val result = CompletableDeferred<WorldState>()
        var acceptedState: WorldState? = null
    }

    private data class PendingPrefill(val request: PrefillRequest, val resolvedTurn: ResolvedTurn)

    private data class Shutdown(
        val lease: Lease?,
        val jobs: List<Job>,
        val turnExecution: TurnExecution?,
    )

    private companion object {
        val logger = LoggerFactory.getLogger(SessionPrefillCoordinator::class.java)
    }
}
