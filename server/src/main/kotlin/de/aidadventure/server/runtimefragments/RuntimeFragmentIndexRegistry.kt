package de.aidadventure.server.runtimefragments

import de.aidadventure.server.EmbeddingService
import de.aidadventure.server.VectorStore
import de.aidadventure.server.model.RuntimeFragmentDefinition
import de.aidadventure.server.vectorstore.LuceneIndexAccess
import de.aidadventure.server.vectorstore.LuceneLocalVectorStore
import de.aidadventure.server.vectorstore.LuceneQueryPlanner
import de.aidadventure.server.vectorstore.QueryOnRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.lucene.store.ByteBuffersDirectory
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

/** A bounded, process-local cache of Lucene indexes for runtime-fragment definitions. */
class RuntimeFragmentIndexRegistry(
    private val clock: Clock = Clock.systemUTC(),
    private val idleTtl: Duration = Duration.ofMinutes(15),
    private val maxIndexes: Int = 32,
) : AutoCloseable {
    init {
        require(!idleTtl.isNegative && !idleTtl.isZero) { "idleTtl must be positive" }
        require(maxIndexes >= 1) { "maxIndexes must be at least one" }
    }

    private val mutex = Mutex()
    private val indexes = mutableMapOf<String, IndexedDefinitions>()
    private var accessSequence = 0L

    val indexCount: Int get() = indexes.size

    suspend fun prepare(definitions: List<RuntimeFragmentDefinition>, embedding: EmbeddingService) {
        indexFor(definitions, embedding)
    }

    suspend fun query(
        definitions: List<RuntimeFragmentDefinition>,
        embedding: EmbeddingService,
        queryVector: FloatArray,
    ): String? =
        mutex.withLock {
            val index = indexForLocked(definitions, embedding)
            index.store.query(VectorStore.Query(vector = queryVector)).resultsPage.firstOrNull()?.fields?.get(FRAGMENT_FIELD)
        }

    private suspend fun indexFor(
        definitions: List<RuntimeFragmentDefinition>,
        embedding: EmbeddingService,
    ): IndexedDefinitions = mutex.withLock { indexForLocked(definitions, embedding) }

    private suspend fun indexForLocked(
        definitions: List<RuntimeFragmentDefinition>,
        embedding: EmbeddingService,
    ): IndexedDefinitions {
        evictIdleLocked()
        val fingerprint = fingerprintFor(definitions)
        indexes[fingerprint]?.let {
            touchLocked(it)
            return it
        }
        evictOverflowLocked()
        val indexAccess = LuceneIndexAccess { ByteBuffersDirectory() }
        val store = LuceneLocalVectorStore.create(
            indexAccess = indexAccess,
            paginationRegistry = QueryOnRegistry(pageSize = 1, maxNotFinishedQueries = 1),
            queryPlanner = LuceneQueryPlanner(maxResults = 1),
            clock = clock,
            vectorSize = embedding.dimension,
        )
        try {
            store.writeAccess {
                definitions
                    .sortedBy(::documentIdFor)
                    .forEach { definition ->
                        add(
                            id = documentIdFor(definition),
                            text = definition.matchText,
                            fields = mapOf(FRAGMENT_FIELD to definition.fragment),
                            vector = embedding.embed(definition.matchText),
                        )
                    }
            }
            return IndexedDefinitions(store, clock.millis(), nextAccessSequenceLocked()).also { indexes[fingerprint] = it }
        } catch (failure: Throwable) {
            store.close()
            throw failure
        }
    }

    private fun evictIdleLocked() {
        val cutoff = clock.millis() - idleTtl.toMillis()
        indexes.entries.removeIf { (_, index) ->
            (index.lastAccessedMillis <= cutoff).also { expired -> if (expired) index.store.close() }
        }
    }

    private fun evictOverflowLocked() {
        while (indexes.size >= maxIndexes) {
            val leastRecentlyUsed = indexes.minBy { it.value.lastAccessSequence }
            indexes.remove(leastRecentlyUsed.key)?.store?.close()
        }
    }

    private fun touchLocked(index: IndexedDefinitions) {
        index.lastAccessedMillis = clock.millis()
        index.lastAccessSequence = nextAccessSequenceLocked()
    }

    private fun nextAccessSequenceLocked(): Long = ++accessSequence

    override fun close() {
        indexes.values.forEach { it.store.close() }
        indexes.clear()
    }

    private data class IndexedDefinitions(
        val store: VectorStore,
        var lastAccessedMillis: Long,
        var lastAccessSequence: Long,
    )

    companion object {
        internal const val FRAGMENT_FIELD = "runtimeFragment"

        fun documentIdFor(definition: RuntimeFragmentDefinition): String =
            sha256("${definition.matchText.length}:${definition.matchText}${definition.fragment.length}:${definition.fragment}")

        private fun fingerprintFor(definitions: List<RuntimeFragmentDefinition>): String =
            sha256(definitions.joinToString(separator = "") { definition ->
                "${definition.matchText.length}:${definition.matchText}${definition.fragment.length}:${definition.fragment}"
            })

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
