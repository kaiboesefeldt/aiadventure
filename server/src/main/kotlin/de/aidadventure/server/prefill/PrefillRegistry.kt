package de.aidadventure.server.prefill

import java.security.MessageDigest
import org.slf4j.LoggerFactory

interface PrefillRegistry {
    fun coordinatorFor(key: CoordinatorKey): PrefillCoordinator
    fun coordinatorIfPresent(key: CoordinatorKey): PrefillCoordinator? = null
    suspend fun remove(key: CoordinatorKey)
    suspend fun removeIdle(nowMillis: Long)
}

internal class ExpiringPrefillRegistry(
    private val gateway: PrefillGateway = LlamaCppPrefillGateway(),
    private val coordinatorFactory: (CoordinatorKey, PrefillSlotLeaseManager) -> PrefillCoordinator =
        { key, sharedLeases -> SessionPrefillCoordinator(gateway, sharedLeases, coordinatorKey = key) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) : PrefillRegistry {
    private val lock = Any()
    private val sharedLeases = PrefillSlotLeaseManager(gateway)
    private val entries = mutableMapOf<CoordinatorKey, Entry>()
    private val pendingClosures = mutableListOf<Pair<CoordinatorKey, PrefillCoordinator>>()

    init {
        require(ttlMillis > 0) { "Prefill registry TTL must be positive." }
    }

    override fun coordinatorFor(key: CoordinatorKey): PrefillCoordinator = synchronized(lock) {
        val now = clock()
        entries[key]?.also {
            it.lastUsedMillis = now
            return@synchronized it.coordinator
        }
        coordinatorFactory(key, sharedLeases).also { entries[key] = Entry(it, now) }
    }

    override fun coordinatorIfPresent(key: CoordinatorKey): PrefillCoordinator? = synchronized(lock) {
        entries[key]?.also { it.lastUsedMillis = clock() }?.coordinator
    }

    override suspend fun remove(key: CoordinatorKey) {
        val removals = synchronized(lock) {
            val matchingPending = pendingClosures.filter { (pendingKey, _) -> pendingKey == key }
            pendingClosures.removeAll(matchingPending.toSet())
            matchingPending + listOfNotNull(entries.remove(key)?.coordinator?.let { key to it })
        }
        closeAll(removals, "removed")
    }

    override suspend fun removeIdle(nowMillis: Long) {
        val removals = synchronized(lock) {
            val retries = pendingClosures.toList()
            pendingClosures.clear()
            val expired = entries.entries
                .filter { nowMillis - it.value.lastUsedMillis >= ttlMillis }
                .mapNotNull { (key, entry) ->
                    if (entry.coordinator.tryBeginIdleClose()) {
                        entries.remove(key)
                        key to entry.coordinator
                    } else {
                        null
                    }
                }
            retries + expired
        }
        closeAll(removals, "expired")
    }

    private suspend fun closeAll(
        removals: List<Pair<CoordinatorKey, PrefillCoordinator>>,
        result: String,
    ) {
        var firstFailure: Throwable? = null
        removals.forEach { (key, coordinator) ->
            try {
                coordinator.close()
                logger.debug("operation=prefill_registry result={} key={}", result, opaqueKey(key))
            } catch (failure: Throwable) {
                synchronized(lock) {
                    if (pendingClosures.none { (_, pending) -> pending === coordinator }) {
                        pendingClosures += key to coordinator
                    }
                }
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
    }

    private data class Entry(val coordinator: PrefillCoordinator, var lastUsedMillis: Long)

    private fun opaqueKey(key: CoordinatorKey): String = MessageDigest.getInstance("SHA-256")
        .digest("${key.sessionId}\u0000${key.modelId}\u0000${key.port}".toByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1_000L
        val logger = LoggerFactory.getLogger(ExpiringPrefillRegistry::class.java)
    }
}
