package de.aidadventure.server.vectorstore

import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.ScoreDoc
import org.apache.lucene.search.TopDocs
import org.apache.lucene.search.TotalHits
import de.aidadventure.server.VectorStore
import java.util.UUID

internal class LuceneQueryPaginationFlow(
    private val paginationRegistry: QueryOnRegistry,
    private val queryPlanner: LuceneQueryPlanner,
    private val documentReader: LuceneStoredDocumentReader,
) {
    val pageSize: Int = paginationRegistry.pageSize

    fun query(
        reader: DirectoryReader,
        query: VectorStore.Query,
    ): VectorStore.QueryResults {
        val searcher = IndexSearcher(reader)
        val luceneQuery = queryPlanner.create(query)
        val searchResult =
            if (query.vector == null) {
                searcher
                    .search(luceneQuery, pageSize, SORT_BY_TIME_ADDED)
                    .unfilteredSearchResult()
            } else {
                with(searcher) {
                    afterKnnFilter(search(luceneQuery, pageSize), query, luceneQuery, 0)
                }
            }
        return searchResult.toQueryResults(
            reader = reader,
            vectorStoreQuery = query,
            query = luceneQuery,
            sort = if (query.vector == null) SORT_BY_TIME_ADDED else null,
            hitsSoFar = searchResult.unfilteredDocs,
        )
    }

    fun queryOn(
        reader: DirectoryReader,
        queryOnId: UUID?,
    ): VectorStore.QueryResults {
        if (queryOnId == null) {
            return VectorStore.QueryResults(emptyList(), null)
        }
        val queryOnRecord =
            paginationRegistry.get(queryOnId)
                ?: return VectorStore.QueryResults(emptyList(), null)
        val searcher = IndexSearcher(reader)
        val docs =
            with(queryOnRecord) {
                if (sort != null) {
                    searcher.searchAfter(after, query, n, sort).unfilteredSearchResult()
                } else {
                    with(searcher) {
                        afterKnnFilter(
                            searchAfter(after, query, n),
                            queryOnRecord.vectorStoreQuery,
                            queryOnRecord.query,
                            queryOnRecord.hitsSoFar,
                        )
                    }
                }
            }
        paginationRegistry.clear(queryOnId)
        return docs.toQueryResults(
            reader = reader,
            vectorStoreQuery = queryOnRecord.vectorStoreQuery,
            query = queryOnRecord.query,
            sort = queryOnRecord.sort,
            hitsSoFar = queryOnRecord.hitsSoFar + docs.unfilteredDocs,
        )
    }

    private fun FilteredSearchResult.toQueryResults(
        reader: DirectoryReader,
        vectorStoreQuery: VectorStore.Query,
        query: Query,
        sort: org.apache.lucene.search.Sort?,
        hitsSoFar: Int,
    ): VectorStore.QueryResults {
        val resultsPage = filtered.scoreDocs.map { documentReader.toSearchResult(it, reader) }
        val nextQueryOnId =
            if (unfilteredDocs == 0) {
                null
            } else {
                paginationRegistry
                    .register(
                        vectorStoreQuery = vectorStoreQuery,
                        topDocs = filtered,
                        query = query,
                        sort = sort,
                        hitsSoFar = hitsSoFar,
                    ).takeIf { isFurtherPaginationPossible }
            }
        return VectorStore.QueryResults(resultsPage = resultsPage, queryOnId = nextQueryOnId)
    }

    private tailrec fun IndexSearcher.afterKnnFilter(
        docs: TopDocs,
        query: VectorStore.Query,
        luceneQuery: Query,
        resultsSoFar: Int,
    ): FilteredSearchResult {
        val firstDocMatch by lazy { query.matchesScoreRangeFor(docs.scoreDocs.first()) }
        val lastDocMatch by lazy { query.matchesScoreRangeFor(docs.scoreDocs.last()) }

        return when {
            docs.scoreDocs.isEmpty() -> {
                FilteredSearchResult.EMPTY
            }

            firstDocMatch == ScoreRangeMatch.ScoreTooLow -> {
                FilteredSearchResult.EMPTY
            }

            else -> {
                val matches =
                    docs.scoreDocs
                        .filter { query.matchesScoreRangeFor(it) == ScoreRangeMatch.ScoreMatches }
                        .toTypedArray()
                if (matches.any()) {
                    FilteredSearchResult(
                        TopDocs(
                            TotalHits(
                                matches.size.toLong(),
                                TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO,
                            ),
                            matches,
                        ),
                        isFurtherPaginationPossible =
                            (lastDocMatch == ScoreRangeMatch.ScoreMatches) && !docs.areResultsExhausted(resultsSoFar),
                        unfilteredDocs = docs.scoreDocs.size,
                    )
                } else {
                    afterKnnFilter(
                        searchAfter(docs.scoreDocs.last(), luceneQuery, pageSize),
                        query,
                        luceneQuery,
                        resultsSoFar + docs.scoreDocs.size,
                    )
                }
            }
        }
    }

    private data class FilteredSearchResult(
        val filtered: TopDocs,
        val unfilteredDocs: Int,
        val isFurtherPaginationPossible: Boolean,
    ) {
        companion object {
            val EMPTY =
                FilteredSearchResult(
                    TopDocs(TotalHits(0, TotalHits.Relation.EQUAL_TO), emptyArray()),
                    unfilteredDocs = 0,
                    isFurtherPaginationPossible = false,
                )
        }
    }

    private enum class ScoreRangeMatch { ScoreTooHigh, ScoreMatches, ScoreTooLow }

    private fun TopDocs.unfilteredSearchResult() =
        FilteredSearchResult(
            this,
            unfilteredDocs = scoreDocs.size,
            isFurtherPaginationPossible = isFullPage() && !areResultsExhausted(0),
        )

    private fun TopDocs.isFullPage(): Boolean = pageSize <= scoreDocs.size

    private fun VectorStore.Query.matchesScoreRangeFor(doc: ScoreDoc): ScoreRangeMatch {
        scoreMustBeAbove?.let { if (doc.score <= it) return ScoreRangeMatch.ScoreTooLow }
        scoreMustBeBelow?.let { if (it <= doc.score) return ScoreRangeMatch.ScoreTooHigh }
        return ScoreRangeMatch.ScoreMatches
    }

    private fun TopDocs.areResultsExhausted(resultsSoFar: Int = 0): Boolean =
        totalHits.value == (resultsSoFar + scoreDocs.size.toLong()) && totalHits.relation == TotalHits.Relation.EQUAL_TO
}
