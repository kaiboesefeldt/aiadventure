package de.aidadventure.server.vectorstore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexNotFoundException
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.ScoreDoc
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.util.BytesRef
import de.aidadventure.server.VectorStore
import de.aidadventure.server.VectorStore.PostDump
import de.aidadventure.server.VectorStore.QueryResults
import java.time.Clock
import java.util.UUID

class LuceneLocalVectorStore private constructor(
    private val indexAccess: LuceneIndexAccess,
    private val paginationRegistry: QueryOnRegistry,
    private val queryPlanner: LuceneQueryPlanner,
    private val clock: Clock,
    private val vectorSize: Int,
) : VectorStore {
    private var closed = false
    private val documentReader = LuceneStoredDocumentReader()
    private val queryPaginationFlow = LuceneQueryPaginationFlow(paginationRegistry, queryPlanner, documentReader)

    override fun close() {
        if (closed) return
        closed = true
        indexAccess.close()
    }

    override suspend fun writeAccess(transactionBlock: suspend VectorStore.Transaction.() -> Unit) {
        checkClosed()
        indexAccess.write {
            indexAccess.transaction(clock, vectorSize).transactionBlock()
        }
    }

    override suspend fun query(query: VectorStore.Query): QueryResults {
        checkClosed()

        return runCatching {
            indexAccess.read {
                queryPaginationFlow.query(this, query)
            }
        }.onFailure {
            if (it !is IndexNotFoundException) throw it
        }.getOrDefault(QueryResults(resultsPage = emptyList(), queryOnId = null))
    }

    override suspend fun queryOn(queryOnId: UUID?): QueryResults {
        checkClosed()

        return runCatching {
            indexAccess.read {
                queryPaginationFlow.queryOn(this, queryOnId)
            }
        }.onFailure {
            if (it !is IndexNotFoundException) throw it
        }.getOrDefault(QueryResults(resultsPage = emptyList(), queryOnId = null))
    }

    override suspend fun fetch(ids: List<String>): List<PostDump> {
        checkClosed()
        if (ids.isEmpty()) return emptyList()

        return runCatching {
            indexAccess.read {
                val searcher = IndexSearcher(this)
                val query =
                    TermInSetQuery(
                        ID_FIELD_NAME,
                        ids.map(::BytesRef),
                    )
                val postsById =
                    searcher
                        .search(query, ids.size)
                        .scoreDocs
                        .map { documentReader.toPostDump(it, this) }
                        .associateBy { it.id }
                ids.mapNotNull(postsById::get)
            }
        }.onFailure {
            if (it !is IndexNotFoundException) throw it
        }.getOrDefault(emptyList())
    }

    override suspend fun fetchByFieldValues(
        fieldName: String,
        values: Set<String>,
    ): List<PostDump> {
        checkClosed()
        if (values.isEmpty()) return emptyList()

        return runCatching {
            indexAccess.read {
                collectPostDumps(
                    TermInSetQuery(
                        fieldName,
                        values.map(::BytesRef),
                    ),
                )
            }
        }.onFailure {
            if (it !is IndexNotFoundException) throw it
        }.getOrDefault(emptyList())
    }

    override fun dumpAll(): Flow<PostDump> {
        checkClosed()

        return flow {
            var after: ScoreDoc? = null
            while (true) {
                val page =
                    runCatching {
                        indexAccess.read {
                            val searcher = IndexSearcher(this)
                            val topDocs =
                                searcher.searchAfter(
                                    after,
                                    MatchAllDocsQuery.INSTANCE,
                                    pageSize,
                                    SORT_BY_TIME_ADDED,
                                )
                            after = topDocs.scoreDocs.lastOrNull()
                            topDocs.scoreDocs.map { documentReader.toPostDump(it, this) }
                        }
                    }.onFailure {
                        if (it !is IndexNotFoundException) throw it
                    }.getOrDefault(emptyList())

                if (page.isEmpty()) break
                page.forEach { emit(it) }
                if (page.size < pageSize) break
            }
        }
    }

    private val pageSize = queryPaginationFlow.pageSize

    private fun DirectoryReader.collectPostDumps(query: Query): List<PostDump> {
        val searcher = IndexSearcher(this)
        val results = mutableListOf<PostDump>()
        var after: ScoreDoc? = null
        while (true) {
            val topDocs = searcher.searchAfter(after, query, pageSize, SORT_BY_TIME_ADDED)
            val page = topDocs.scoreDocs.map { documentReader.toPostDump(it, this) }
            if (page.isEmpty()) return results
            results.addAll(page)
            if (page.size < pageSize) return results
            after = topDocs.scoreDocs.last()
        }
    }

    private fun checkClosed() {
        if (closed) throw RuntimeException("The vector store has already been closed")
    }

    companion object {
        fun create(
            indexAccess: LuceneIndexAccess,
            paginationRegistry: QueryOnRegistry,
            queryPlanner: LuceneQueryPlanner,
            clock: Clock,
            vectorSize: Int,
        ): VectorStore =
            LuceneLocalVectorStore(
                indexAccess,
                paginationRegistry,
                queryPlanner,
                clock,
                vectorSize,
            )
    }
}
