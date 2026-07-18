package de.aidadventure.server.vectorstore

import org.apache.lucene.search.Query
import org.apache.lucene.search.ScoreDoc
import org.apache.lucene.search.Sort
import org.apache.lucene.search.TopDocs
import de.aidadventure.server.VectorStore
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class QueryOnRegistry(
    val pageSize: Int,
    maxNotFinishedQueries: Int,
) {
    init {
        require(maxNotFinishedQueries >= 1) {
            "maxNotFinishedQueries must be >= 1 (actual: $maxNotFinishedQueries)"
        }
    }

    private val maxQueries = maxNotFinishedQueries
    private val records = LinkedHashMap<UUID, QueryOnRecord>()
    private val history = ArrayDeque<UUID>()
    private val lock = ReentrantLock()

    fun register(
        vectorStoreQuery: VectorStore.Query,
        topDocs: TopDocs,
        query: Query,
        sort: Sort?,
        hitsSoFar: Int,
    ): UUID {
        val scoreDoc =
            topDocs.scoreDocs.lastOrNull()
                ?: throw IllegalStateException("Cannot register pagination for empty result set")

        return UUID
            .randomUUID()
            .also { id ->
                lock.withLock {
                    removeOverflowLocked()
                    history.addLast(id)
                    records[id] =
                        QueryOnRecord(
                            vectorStoreQuery = vectorStoreQuery,
                            after = scoreDoc,
                            query = query,
                            n = pageSize,
                            sort = sort,
                            hitsSoFar = hitsSoFar,
                        )
                    check(records.size == history.size)
                }
            }
    }

    fun get(id: UUID): QueryOnRecord? = lock.withLock { records[id] }

    fun clear(id: UUID) {
        lock.withLock {
            records.remove(id)
            history.remove(id)
        }
    }

    private fun removeOverflowLocked() {
        while (maxQueries <= records.size) {
            val oldestId = history.removeFirst()
            records.remove(oldestId)
        }
    }

    data class QueryOnRecord(
        val vectorStoreQuery: VectorStore.Query,
        val after: ScoreDoc,
        val query: Query,
        val n: Int,
        val sort: Sort?,
        val hitsSoFar: Int,
    )
}
