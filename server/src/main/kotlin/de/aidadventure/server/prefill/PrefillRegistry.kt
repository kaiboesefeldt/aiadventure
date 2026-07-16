package de.aidadventure.server.prefill

import java.security.MessageDigest
import org.slf4j.LoggerFactory

interface PrefillRegistry {
    fun coordinatorFor(key: CoordinatorKey): PrefillCoordinator
    suspend fun remove(key: CoordinatorKey)
    suspend fun removeIdle(nowMillis: Long)
}

internal class ExpiringPrefillRegistry(
    private val coordinatorFactory: (CoordinatorKey) -> PrefillCoordinator = { SessionPrefillCoordinator(LlamaCppPrefillGateway()) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) : PrefillRegistry {
    private val lock = Any()
    private val entries = mutableMapOf<CoordinatorKey, Entry>()

    init {
        require(ttlMillis > 0) { "Prefill registry TTL must be positive." }
    }

    override fun coordinatorFor(key: CoordinatorKey): PrefillCoordinator = synchronized(lock) {
        val now = clock()
        entries[key]?.also {
            it.lastUsedMillis = now
            return@synchronized it.coordinator
        }
        coordinatorFactory(key).also { entries[key] = Entry(it, now) }
    }

    override suspend fun remove(key: CoordinatorKey) {
        val coordinator = synchronized(lock) { entries.remove(key)?.coordinator } ?: return
        coordinator.close()
        logger.debug("operation=prefill_registry result=removed key={}", opaqueKey(key))
    }

    override suspend fun removeIdle(nowMillis: Long) {
        val expired = synchronized(lock) {
            entries.entries
                .filter { nowMillis - it.value.lastUsedMillis >= ttlMillis }
                .map { it.key to it.value.coordinator }
                .also { entries.keys.removeAll(it.map { (key, _) -> key }.toSet()) }
        }
        expired.forEach { (key, coordinator) ->
            coordinator.close()
            logger.debug("operation=prefill_registry result=expired key={}", opaqueKey(key))
        }
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
