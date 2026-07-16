package de.aidadventure.server.prefill

import de.aidadventure.server.llamacpp.CacheMetrics
import de.aidadventure.server.llamacpp.LlamaCppGateway
import de.aidadventure.server.llamacpp.LlamaCppSlot
import de.aidadventure.server.model.ModelProvider
import de.aidadventure.server.model.PrefillDisposition
import de.aidadventure.server.model.PrefillRequest
import de.aidadventure.server.model.TurnRequest
import de.aidadventure.server.model.TurnStreamEvent
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

data class CoordinatorKey(val sessionId: String, val modelId: String, val port: Int)

interface PrefillCoordinator {
    suspend fun submit(request: PrefillRequest): PrefillDisposition
    suspend fun beginTurn(request: TurnRequest): Flow<TurnStreamEvent>
    fun tryBeginIdleClose(): Boolean
    suspend fun close()
}

internal interface PrefillGateway {
    fun acquireIdleSlot(port: Int, excludedSlots: Set<LlamaCppSlot>): LlamaCppSlot
    fun releaseSlot(port: Int, slot: LlamaCppSlot)
    suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot): CacheMetrics
    fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent>
}

internal class LlamaCppPrefillGateway(
    private val gateway: LlamaCppGateway = LlamaCppGateway(),
) : PrefillGateway {
    override fun acquireIdleSlot(port: Int, excludedSlots: Set<LlamaCppSlot>): LlamaCppSlot =
        gateway.acquireIdleSlot(port, excludedSlots)

    override fun releaseSlot(port: Int, slot: LlamaCppSlot) = gateway.releaseSlot(port, slot)

    override suspend fun prefill(request: TurnRequest, slot: LlamaCppSlot): CacheMetrics = gateway.prefill(request, slot)

    override fun stream(request: TurnRequest, slot: LlamaCppSlot): Flow<TurnStreamEvent> = gateway.stream(request, slot)
}

internal class SessionPrefillCoordinator(
    private val gateway: PrefillGateway,
    private val slotLeases: PrefillSlotLeaseManager = PrefillSlotLeaseManager(gateway),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : PrefillCoordinator {
    private val mutex = Mutex()
    private var latestRevision = Long.MIN_VALUE
    private var generation = 0L
    private var closed = false
    private var shutdownComplete = false
    private var state: State = State.Idle

    override suspend fun submit(request: PrefillRequest): PrefillDisposition = mutex.withLock {
        if (closed || state is State.Turning || state is State.TurnWaiting) {
            return@withLock PrefillDisposition.TURN_IN_PROGRESS
        }
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
                startPrefillLocked(request, current.lease)
                PrefillDisposition.STARTED
            }

            is State.ReleasePending -> {
                releaseLeaseLocked(current.lease)
                state = State.Idle
                startPrefillLocked(request)
                PrefillDisposition.STARTED
            }

            is State.Turning, is State.TurnWaiting -> PrefillDisposition.TURN_IN_PROGRESS
        }
    }

    override suspend fun beginTurn(request: TurnRequest): Flow<TurnStreamEvent> = mutex.withLock {
        if (closed) return@withLock rejectedTurn("Prefill coordinator is closed.")

        when (val current = state) {
            State.Idle -> startTurnLocked(request)
            is State.Prefilling -> {
                val execution = TurnExecution(request).also(TurnExecution::initialize)
                state = State.TurnWaiting(current.lease, current.operation, execution)
                execution.flow()
            }

            is State.Ready -> startTurnLocked(request, current.lease)
            is State.ReleasePending -> {
                releaseLeaseLocked(current.lease)
                state = State.Idle
                startTurnLocked(request)
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

    private fun startPrefillLocked(request: PrefillRequest, existingLease: Lease? = null) {
        val lease = existingLease ?: acquireLease(request.llamaCppPort)
        val operation = PrefillOperation(++generation)
        state = State.Prefilling(lease, operation, pending = null)
        operation.job = scope.launch(start = CoroutineStart.LAZY) {
            val failure = try {
                gateway.prefill(request.toTurnRequest(), lease.slot)
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

    private fun startTurnLocked(request: TurnRequest, existingLease: Lease? = null): Flow<TurnStreamEvent> {
        val lease = existingLease ?: acquireLease(request.llamaCppPort)
        val execution = TurnExecution(request).also(TurnExecution::initialize)
        state = State.Turning(lease, execution)
        execution.handoff(lease)
        return execution.flow()
    }

    private suspend fun prefillFinished(lease: Lease, operation: PrefillOperation, failure: Throwable?) {
        mutex.withLock {
            if (closed || operation.generation != generation) return@withLock

            when (val current = state) {
                is State.Prefilling -> if (current.operation === operation && current.lease === lease) {
                    if (failure != null) {
                        generation++
                        releaseAfterFailureLocked(lease)
                    } else if (current.pending != null) {
                        startPrefillLocked(current.pending, lease)
                    } else {
                        state = State.Ready(lease)
                    }
                }

                is State.TurnWaiting -> if (current.operation === operation && current.lease === lease) {
                    if (failure != null) {
                        generation++
                        val releaseFailure = releaseAfterFailureLocked(lease)
                        current.execution.fail(releaseFailure ?: failure)
                    } else {
                        state = State.Turning(lease, current.execution)
                        current.execution.handoff(lease)
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
                state = State.Prefilling(current.lease, current.operation, pending = null)
                null
            } else {
                null
            }

            is State.Turning -> if (current.execution === execution && current.lease === lease) {
                generation++
                try {
                    releaseLeaseLocked(current.lease)
                    state = State.Idle
                    null
                } catch (failure: Throwable) {
                    state = State.ReleasePending(current.lease)
                    failure
                }
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

    private fun acquireLease(port: Int?): Lease {
        val requiredPort = requireNotNull(port) { "llama.cpp port is required." }
        return Lease(requiredPort, slotLeases.acquire(requiredPort))
    }

    private fun releaseLeaseLocked(lease: Lease) {
        if (lease.released) return
        slotLeases.release(lease.port, lease.slot)
        lease.released = true
    }

    private fun rejectedTurn(message: String): Flow<TurnStreamEvent> = flow {
        throw IllegalStateException(message)
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

    private inner class TurnExecution(private val request: TurnRequest) {
        private val ready = CompletableDeferred<Lease>()
        private val output = Channel<TurnStreamEvent>(Channel.RENDEZVOUS)
        private val collected = AtomicBoolean()
        lateinit var job: Job

        fun initialize() {
            job = scope.launch(start = CoroutineStart.LAZY) {
                var lease: Lease? = null
                var streamFailure: Throwable? = null
                try {
                    lease = ready.await()
                    gateway.stream(request, lease.slot).collect(output::send)
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

        fun handoff(lease: Lease) {
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
            val operation: PrefillOperation,
            val pending: PrefillRequest?,
        ) : State

        data class Ready(val lease: Lease) : State
        data class TurnWaiting(
            val lease: Lease,
            val operation: PrefillOperation,
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
    }

    private class Lease(val port: Int, val slot: LlamaCppSlot) {
        var released = false
    }

    private class PrefillOperation(val generation: Long) {
        lateinit var job: Job
    }

    private data class Shutdown(
        val lease: Lease?,
        val jobs: List<Job>,
        val turnExecution: TurnExecution?,
    )
}
