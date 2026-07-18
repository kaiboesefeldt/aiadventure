package de.aidadventure.server.vectorstore

import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.PointRangeQuery
import org.apache.lucene.search.WildcardQuery
import org.junit.jupiter.api.Test
import de.aidadventure.server.VectorStore
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LuceneQueryPlannerTest {
    private val planner = LuceneQueryPlanner(maxResults = 4)

    @Test
    fun `create produces knn clause when vector provided`() {
        val query =
            planner.create(
                VectorStore.Query(
                    id = "subject-1",
                    vector = floatArrayOf(0.1f, 0.5f),
                    fromTime = Instant.ofEpochSecond(10),
                    toTime = Instant.ofEpochSecond(20),
                    fields = mapOf("topic" to "science"),
                ),
            )

        val booleanQuery = assertIs<BooleanQuery>(query)
        assertEquals(4, booleanQuery.clauses().size)
        val knnClause = booleanQuery.clauses().single { it.query is KnnFloatVectorQuery }
        assertEquals(BooleanClause.Occur.MUST, knnClause.occur)
        assertEquals(3, booleanQuery.clauses().count { it.occur == BooleanClause.Occur.FILTER })
    }

    @Test
    fun `create returns match all when vector missing`() {
        val query = planner.create(VectorStore.Query())

        assertIs<MatchAllDocsQuery>(query)
    }

    @Test
    fun `create builds closed upper bound time range`() {
        val query =
            planner.create(
                VectorStore.Query(
                    fromTime = Instant.ofEpochSecond(5),
                ),
            )

        val booleanQuery = assertIs<BooleanQuery>(query)
        val rangeClause = booleanQuery.clauses().single()
        assertEquals(BooleanClause.Occur.FILTER, rangeClause.occur)
        val pointRange = assertIs<PointRangeQuery>(rangeClause.query)
        assertEquals("timeadded:[5 TO 9223372036854775807]", pointRange.toString())
    }

    @Test
    fun `create builds open lower bound time range`() {
        val query =
            planner.create(
                VectorStore.Query(
                    toTime = Instant.ofEpochSecond(11),
                ),
            )

        val booleanQuery = assertIs<BooleanQuery>(query)
        val rangeClause = booleanQuery.clauses().single()
        assertEquals(BooleanClause.Occur.FILTER, rangeClause.occur)
        val pointRange = assertIs<PointRangeQuery>(rangeClause.query)
        assertEquals("timeadded:[-9223372036854775808 TO 11]", pointRange.toString())
    }

    @Test
    fun `null field value filters out documents where field is set`() {
        val query =
            planner.create(
                VectorStore.Query(
                    fields = mapOf("fieldName" to null),
                ),
            )

        val booleanQuery = assertIs<BooleanQuery>(query)
        assertEquals(2, booleanQuery.clauses().size)
        val matchAllClause = booleanQuery.clauses().single { it.query is MatchAllDocsQuery }
        assertEquals(BooleanClause.Occur.FILTER, matchAllClause.occur)
        val mustNotClause = booleanQuery.clauses().single { it.query is WildcardQuery }
        assertEquals(BooleanClause.Occur.MUST_NOT, mustNotClause.occur)
    }
}
