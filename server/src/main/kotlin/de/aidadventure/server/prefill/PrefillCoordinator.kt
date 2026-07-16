package de.aidadventure.server.prefill

import de.aidadventure.server.llamacpp.CacheMetrics
import de.aidadventure.server.llamacpp.LlamaCppGateway
import de.aidadventure.server.llamacpp.LlamaCppSlot
import de.aidadventure.server.model.ModelProvider
import de.aidadventure.server.model.PrefillDisposition
import de.aidadventure.server.model.PrefillRequest
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CoordinatorKey(val sessionId: String, val modelId: String, val port: Int)

interface PrefillCoordinator {
    suspend fun submit(request: PrefillRequest): PrefillDisposition
    suspend fun beginTurn(request: TurnRequest): Flow<TurnStreamEvent>
    suspend fun close()
}

internal interface PrefillGateway {
    fun acquireIdleSlot(port: Int): LlamaCppSlot
    fun releaseSlot(port: Int, slot: LlamaCppSlot)
    suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot): CacheMetrics
    fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent>
}

internal class LlamaCppPrefillGateway(
    private val gateway: LlamaCppGateway = LlamaCppGateway(),
) : PrefillGateway {
    override fun acquireIdleSlot(port: Int): LlamaCppSlot = gateway.acquireIdleSlot(port)

    override fun releaseSlot(port: Int, slot: LlamaCppSlot) = gateway.releaseSlot(port, slot)

    override suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot): CacheMetrics = gateway.prefill(request, slot)

    override fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent> = gateway.stream(request, slot)
}

internal class SessionPrefillCoordinator(
    private val gateway: PrefillGateway,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : PrefillCoordinator {
    private val mutex = Mutex()
    private var latestRevision = Long.MIN_VALUE
    private var generation = 0L
    private var closed = false
    private var state: State = State.Idle

    override suspend fun submit(request: PrefillRequest): PrefillDisposition = mutex.withLock {
        if (closed || state is State.Turning || state is State.TurnWaiting) return@withLock PrefillDisposition.TURN_IN_PROGRESS
        if (request.revision <= latestRevision) return@withLock PrefillDisposition.STALE

        latestRevision = request.revision
        when (val current = state) {
            State.Idle -> {
                startPrefillLocked(request)
                PrefillDisposition.STARTED
            }

            is State.Prefilling -> {
                state = current.copy(pending = request)
                PrefillDisposition.QUEUED
            }

            is State.Ready -> {
                releaseWorkLocked(current.work)
                startPrefillLocked(request)
                PrefillDisposition.STARTED
            }

            is State.Turning, is State.TurnWaiting -> PrefillDisposition.TURN_IN_PROGRESS
        }
    }

    override suspend fun beginTurn(request: TurnRequest): Flow<TurnStreamEvent> = mutex.withLock {
        if (closed) return@withLock rejectedTurn()

        when (val current = state) {
            State.Idle -> startTurnLocked(request)
            is State.Prefilling -> {
                val ready = CompletableDeferred<Work>()
                state = State.TurnWaiting(current.work, ready)
                waitingTurn(ready, request)
            }

            is State.Ready -> {
                state = State.Turning(current.work)
                streamTurn(current.work, request)
            }

            is State.TurnWaiting, is State.Turning -> rejectedTurn()
        }
    }

    override suspend fun close() {
        var waiting: CompletableDeferred<Work>? = null
        mutex.withLock {
            if (closed) return
            closed = true
            generation++
            waiting = (state as? State.TurnWaiting)?.ready
            state.activeWorkOrNull()?.let(::releaseWorkLocked)
            state = State.Idle
        }
        waiting?.completeExceptionally(CancellationException("Prefill coordinator closed."))
        scope.cancel()
    }

    private fun startPrefillLocked(request: PrefillRequest) {
        val port = requireNotNull(request.llamaCppPort) { "llama.cpp port is required." }
        val work = Work(port, gateway.acquireIdleSlot(port), ++generation)
        state = State.Prefilling(work, pending = null)
        scope.launch {
            try {
                gateway.prefill(request.toTurnRequest(), work.slot)
                prefillFinished(work, failure = null)
            } catch (_: CancellationException) {
                // close() owns state transition and slot release for cancelled work.
            } catch (failure: Throwable) {
                prefillFinished(work, failure)
            }
        }
    }

    private fun startTurnLocked(request: TurnRequest): Flow<TurnStreamEvent> {
        val port = requireNotNull(request.llamaCppPort) { "llama.cpp port is required." }
        val work = Work(port, gateway.acquireIdleSlot(port), ++generation)
        state = State.Turning(work)
        return streamTurn(work, request)
    }

    private suspend fun prefillFinished(work: Work, failure: Throwable?) {
        var waitingFailure: Pair<CompletableDeferred<Work>, Throwable>? = null
        var readyTurn: Pair<CompletableDeferred<Work>, Work>? = null

        mutex.withLock {
            if (work.generation != generation) {
                releaseWorkLocked(work)
                return@withLock
            }

            when (val current = state) {
                is State.Prefilling -> if (current.work === work) {
                    if (failure != null) {
                        generation++
                        releaseWorkLocked(work)
                        state = State.Idle
                    } else if (current.pending != null) {
                        releaseWorkLocked(work)
                        state = State.Idle
                        startPrefillLocked(current.pending)
                    } else {
                        state = State.Ready(work)
                    }
                } else {
                    releaseWorkLocked(work)
                }

                is State.TurnWaiting -> if (current.work === work) {
                    if (failure != null) {
                        generation++
                        releaseWorkLocked(work)
                        state = State.Idle
                        waitingFailure = current.ready to failure
                    } else {
                        state = State.Turning(work)
                        readyTurn = current.ready to work
                    }
                } else {
                    releaseWorkLocked(work)
                }

                else -> releaseWorkLocked(work)
            }
        }

        waitingFailure?.let { (ready, cause) -> ready.completeExceptionally(cause) }
        readyTurn?.let { (ready, active) -> ready.complete(active) }
    }

    private fun waitingTurn(ready: CompletableDeferred<Work>, request: TurnRequest): Flow<TurnStreamEvent> = flow {
        var work: Work? = null
        try {
            work = ready.await()
            emitAll(gateway.stream(request, work.slot))
        } finally {
            if (work != null) finishTurn(work) else cancelWaitingTurn(ready)
        }
    }

    private fun streamTurn(work: Work, request: TurnRequest): Flow<TurnStreamEvent> = flow {
        try {
            emitAll(gateway.stream(request, work.slot))
        } finally {
            finishTurn(work)
        }
    }

    private suspend fun finishTurn(work: Work) = mutex.withLock {
        if (state is State.Turning && (state as State.Turning).work === work) {
            generation++
            releaseWorkLocked(work)
            state = State.Idle
        } else {
            releaseWorkLocked(work)
        }
    }

    private suspend fun cancelWaitingTurn(ready: CompletableDeferred<Work>) = mutex.withLock {
        val current = state as? State.TurnWaiting ?: return@withLock
        if (current.ready === ready) state = State.Prefilling(current.work, pending = null)
    }

    private fun releaseWorkLocked(work: Work) {
        if (work.released) return
        work.released = true
        runCatching { gateway.releaseSlot(work.port, work.slot) }
    }

    private fun rejectedTurn(): Flow<TurnStreamEvent> = flow {
        throw IllegalStateException("A turn is already in progress for this coordinator.")
    }

    private fun PrefillRequest.toTurnRequest() = TurnRequest(
        modelId = modelId,
        scenarioMarkdown = scenarioMarkdown,
        history = history,
        playerAction = playerAction,
        provider = ModelProvider.LLAMA_CPP,
        llamaCppPort = llamaCppPort,
        sessionId = sessionId,
    )

    private sealed interface State {
        data object Idle : State
        data class Prefilling(val work: Work, val pending: PrefillRequest?) : State
        data class Ready(val work: Work) : State
        data class TurnWaiting(val work: Work, val ready: CompletableDeferred<Work>) : State
        data class Turning(val work: Work) : State

        fun activeWorkOrNull(): Work? = when (this) {
            Idle -> null
            is Prefilling -> work
            is Ready -> work
            is TurnWaiting -> work
            is Turning -> work
        }
    }

    private class Work(val port: Int, val slot: LlamaCppSlot, val generation: Long) {
        var released = false
    }
}
