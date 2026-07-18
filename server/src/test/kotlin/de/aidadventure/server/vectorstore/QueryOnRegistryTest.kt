package de.aidadventure.server.vectorstore

import org.apache.lucene.index.Term
import org.apache.lucene.search.ScoreDoc
import org.apache.lucene.search.Sort
import org.apache.lucene.search.SortField
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.TopDocs
import org.apache.lucene.search.TotalHits
import org.apache.lucene.search.TotalHits.Relation
import org.junit.jupiter.api.Test
import de.aidadventure.server.VectorStore
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QueryOnRegistryTest {
    private val vectorStoreQuery = VectorStore.Query(id = "vectorStoreQuery")
    private val query = TermQuery(Term("id", "42"))
    private val sort = Sort(SortField("time", SortField.Type.LONG))

    @Test
    fun `register stores query on record`() {
        val registry = QueryOnRegistry(pageSize = 2, maxNotFinishedQueries = 5)

        val searchOnId = registry.register(vectorStoreQuery, topDocsWithTwoResults(), query, sort, 0)

        val record = registry.get(searchOnId)

        assertNotNull(record)
        assertEquals(vectorStoreQuery, record.vectorStoreQuery)
        assertEquals(query, record.query)
        assertEquals(sort, record.sort)
        assertEquals(2, record.n)
    }

    @Test
    fun `register removes oldest query when capacity exceeded`() {
        val registry = QueryOnRegistry(pageSize = 2, maxNotFinishedQueries = 2)

        val firstId = registry.register(vectorStoreQuery, topDocsWithTwoResults(), query, sort, 2)
        val secondId = registry.register(vectorStoreQuery, topDocsWithTwoResults(), query, sort, 4)
        val thirdId = registry.register(vectorStoreQuery, topDocsWithTwoResults(), query, sort, 5)

        assertNotEquals(firstId, secondId)
        assertNotEquals(secondId, thirdId)

        assertNull(registry.get(firstId))
        assertNotNull(registry.get(secondId))
        assertNotNull(registry.get(thirdId))
    }

    @Test
    fun `clear removes entries`() {
        val registry = QueryOnRegistry(pageSize = 2, maxNotFinishedQueries = 3)
        val searchOnId = registry.register(vectorStoreQuery, topDocsWithTwoResults(), query, sort, 2)

        registry.clear(searchOnId)

        assertNull(registry.get(searchOnId))
    }

    @Test
    fun `register throws when top docs are empty`() {
        val registry = QueryOnRegistry(pageSize = 2, maxNotFinishedQueries = 3)
        val emptyTopDocs =
            TopDocs(TotalHits(0, Relation.EQUAL_TO), emptyArray())

        assertFailsWith<IllegalStateException> {
            registry.register(vectorStoreQuery, emptyTopDocs, query, sort, 2)
        }
    }

    @Test
    fun `constructor rejects zero capacity`() {
        assertFailsWith<IllegalArgumentException> {
            QueryOnRegistry(pageSize = 2, maxNotFinishedQueries = 0)
        }
    }

    private fun topDocsWithTwoResults(): TopDocs {
        val scoreDocs =
            arrayOf(
                ScoreDoc(0, 0.9f),
                ScoreDoc(1, 0.8f),
            )
        return TopDocs(TotalHits(2, Relation.EQUAL_TO), scoreDocs)
    }
}
