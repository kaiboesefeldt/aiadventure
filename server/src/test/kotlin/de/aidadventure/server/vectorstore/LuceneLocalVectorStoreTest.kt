package de.aidadventure.server.vectorstore

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.apache.lucene.index.IndexNotFoundException
import org.apache.lucene.store.ByteBuffersDirectory
import org.junit.jupiter.api.Test
import de.aidadventure.server.VectorStore
import de.aidadventure.server.VectorStore.Query
import de.aidadventure.server.testutils.TestClock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.use

class LuceneLocalVectorStoreTest {
    @Test
    fun `query returns empty results when index missing`() =
        runTest {
            newFixture(pageSize = 2).use { fixture ->
                val result = fixture.store.query(Query())
                assertTrue(result.resultsPage.isEmpty())
                assertNull(result.queryOnId)
            }
        }

    @Test
    fun `query with empty result from lucene`() =
        runTest {
            newFixture(pageSize = 10).use { fixture ->
                fixture.store.addDocument("doc-1", "first", emptyMap(), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-2", "second", emptyMap(), floatArrayOf(0f, 1f))
                fixture.clock.advance(1.seconds)

                val result =
                    fixture.store.query(
                        Query(
                            vector = floatArrayOf(0f, 1f),
                            fromTime = fixture.clock.instant(), // Is in the future
                        ),
                    )

                assertEquals(emptyList(), result.resultsPage)
                assertNull(result.queryOnId)
            }
        }

    @Test
    fun `query post-filters for scoreMustBeAbove`() =
        runTest {
            newFixture(pageSize = 10).use { fixture ->
                fixture.store.addDocument("doc-1", "first", emptyMap(), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-2", "second", emptyMap(), floatArrayOf(0f, 1f))

                val result =
                    fixture.store.query(
                        Query(
                            vector = floatArrayOf(0f, 1f),
                            scoreMustBeAbove = 0.75f,
                        ),
                    )

                assertEquals(
                    listOf(
                        VectorStore.QueryResult("doc-2", "second", similarity = 1.0f, fields = emptyMap()),
                    ),
                    result.resultsPage,
                )
                assertNull(result.queryOnId)
            }
        }

    @Test
    fun `query post-filters for scoreMustBeAbove with all results below`() =
        runTest {
            newFixture(pageSize = 10).use { fixture ->
                fixture.store.addDocument("doc-1", "first", emptyMap(), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-2", "second", emptyMap(), floatArrayOf(0f, 1f))

                val result =
                    fixture.store.query(
                        Query(
                            vector = floatArrayOf(0f, 1f),
                            scoreMustBeAbove = 1.1f,
                        ),
                    )

                assertEquals(emptyList(), result.resultsPage)
                assertNull(result.queryOnId)
            }
        }

    @Test
    fun `query post-filters for scoreMustBeBelow`() =
        runTest {
            newFixture(pageSize = 10).use { fixture ->
                fixture.store.addDocument("doc-1", "first", emptyMap(), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-2", "second", emptyMap(), floatArrayOf(0f, 1f))

                val result =
                    fixture.store.query(
                        Query(
                            vector = floatArrayOf(0f, 1f),
                            scoreMustBeBelow = 0.75f,
                        ),
                    )

                assertEquals(
                    listOf(
                        VectorStore.QueryResult("doc-1", "first", similarity = 0.5f, fields = emptyMap()),
                    ),
                    result.resultsPage,
                )
                assertNull(result.queryOnId)
            }
        }

    @Test
    fun `query post-filters for scoreMustBeBelow with internal requery for next page`() =
        runTest {
            newFixture(pageSize = 2).use { fixture ->
                (1..9).forEach { docId ->
                    fixture.store.addDocument("doc-$docId", "Text $docId", emptyMap(), floatArrayOf(1f, docId.toFloat()))
                    fixture.clock.advance(1.seconds)
                }

                val result =
                    fixture.store.query(
                        Query(
                            vector = floatArrayOf(1f, 1f),
                            scoreMustBeBelow = 0.92f,
                        ),
                    )

                assertEquals(
                    listOf("doc-5", "doc-6"),
                    result.resultsPage.map { it.id },
                    "actual result: $result",
                )
                assertNotNull(result.queryOnId)
            }
        }

    @Test
    fun `query post-filters for scoreWindow`() =
        runTest {
            newFixture(pageSize = 10).use { fixture ->
                (1..9).forEach { docId ->
                    fixture.store.addDocument("doc-$docId", "Text $docId", emptyMap(), floatArrayOf(1f, docId.toFloat()))
                    fixture.clock.advance(1.seconds)
                }

                val result =
                    fixture.store.query(
                        Query(
                            vector = floatArrayOf(1f, 1f),
                            scoreMustBeBelow = 0.98f,
                            scoreMustBeAbove = 0.92f,
                        ),
                    )

                assertEquals(
                    listOf("doc-2", "doc-3", "doc-4"),
                    result.resultsPage.map { it.id },
                    "actual result: $result",
                )
                assertNull(result.queryOnId)
            }
        }

    @Test
    fun `query post-filters for scoreWindow with paging, last page empty`() =
        runTest {
            newFixture(pageSize = 2).use { fixture ->
                (1..9).forEach { docId ->
                    fixture.store.addDocument("doc-$docId", "Text $docId", emptyMap(), floatArrayOf(1f, docId.toFloat()))
                    fixture.clock.advance(1.seconds)
                }

                val firstPage =
                    fixture.store.query(
                        Query(
                            vector = floatArrayOf(1f, 1f),
                            scoreMustBeBelow = 0.98f,
                            scoreMustBeAbove = 0.92f,
                        ),
                    )

                assertEquals(
                    listOf("doc-2"),
                    firstPage.resultsPage.map { it.id },
                    "actual first page: $firstPage",
                )
                assertNotNull(firstPage.queryOnId)

                val secondPage = fixture.store.queryOn(firstPage.queryOnId)

                assertEquals(
                    listOf("doc-3", "doc-4"),
                    secondPage.resultsPage.map { it.id },
                    "actual second page: $secondPage",
                )
                assertNotNull(secondPage.queryOnId)

                val thirdPage = fixture.store.queryOn(secondPage.queryOnId)
                assertEquals(
                    emptyList(),
                    thirdPage.resultsPage.map { it.id },
                    "actual third page: $secondPage",
                )
                assertNull(thirdPage.queryOnId)
            }
        }

    @Test
    fun `query post-filters for scoreWindow with paging, last page not empty`() =
        runTest {
            newFixture(pageSize = 2).use { fixture ->
                (1..9).forEach { docId ->
                    fixture.store.addDocument("doc-$docId", "Text $docId", emptyMap(), floatArrayOf(1f, docId.toFloat()))
                    fixture.clock.advance(1.seconds)
                }

                val firstPage =
                    fixture.store.query(
                        Query(
                            vector = floatArrayOf(1f, 1f),
                            scoreMustBeBelow = 0.98f,
                            scoreMustBeAbove = 0.93f,
                        ),
                    )

                assertEquals(
                    listOf("doc-2"),
                    firstPage.resultsPage.map { it.id },
                    "actual first page: $firstPage",
                )
                assertNotNull(firstPage.queryOnId)

                val secondPage = fixture.store.queryOn(firstPage.queryOnId)

                assertEquals(
                    listOf("doc-3"),
                    secondPage.resultsPage.map { it.id },
                    "actual second page: $secondPage",
                )
                assertNull(secondPage.queryOnId)
            }
        }

    @Test
    fun `query continues to later pages when high scores are filtered out`() =
        runTest {
            newFixture(pageSize = 1).use { fixture ->
                fixture.store.addDocument("doc-1", "north", emptyMap(), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-2", "south", emptyMap(), floatArrayOf(0f, 1f))

                val result =
                    fixture.store.query(
                        Query(
                            vector = floatArrayOf(1f, 0f),
                            scoreMustBeBelow = 0.9f,
                        ),
                    )

                assertEquals(listOf("doc-2"), result.resultsPage.map { it.id })
                assertNull(result.queryOnId)
            }
        }

    @Test
    fun `query registers pagination with time sort when vector missing`() =
        runTest {
            newFixture(pageSize = 1).use { fixture ->
                fixture.store.addDocument("doc-1", "first", mapOf("category" to "shared", "topic" to "news-1"), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-2", "second", mapOf("category" to "shared", "topic" to "news-2"), floatArrayOf(0f, 1f))

                val result =
                    fixture.store.query(
                        Query(
                            fields = mapOf("category" to "shared"),
                        ),
                    )

                assertEquals(1, result.resultsPage.size)
                assertNotNull(result.queryOnId)
                val record = fixture.registry.get(result.queryOnId)
                assertNotNull(record)
                assertEquals(SORT_BY_TIME_ADDED, record.sort)
            }
        }

    @Test
    fun `query does not paginate when exactly one page available`() =
        runTest {
            newFixture(pageSize = 2).use { fixture ->
                fixture.store.addDocument("doc-1", "first", mapOf("category" to "shared"), floatArrayOf(1f, 0f))
                fixture.store.addDocument("doc-2", "second", mapOf("category" to "shared"), floatArrayOf(0f, 1f))

                val result =
                    fixture.store.query(
                        Query(
                            fields = mapOf("category" to "shared"),
                        ),
                    )

                assertEquals(2, result.resultsPage.size)
                assertNull(result.queryOnId)
            }
        }

    @Test
    fun `query registers pagination without sort when vector provided`() =
        runTest {
            newFixture(pageSize = 1).use { fixture ->
                fixture.store.addDocument("doc-1", "first", mapOf("category" to "shared", "topic" to "news-1"), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-2", "second", mapOf("category" to "shared", "topic" to "news-2"), floatArrayOf(0f, 1f))

                val result =
                    fixture.store.query(
                        Query(
                            vector = floatArrayOf(1f, 0f),
                        ),
                    )

                assertEquals("doc-1", result.resultsPage.first().id)
                assertNotNull(result.queryOnId)
                val record = fixture.registry.get(result.queryOnId)
                assertNotNull(record)
                assertNull(record.sort)
            }
        }

    @Test
    fun `query registers pagination when more hits available`() =
        runTest {
            newFixture(pageSize = 2).use { fixture ->
                repeat(5) { index ->
                    fixture.store.addDocument(
                        id = "bulk-${index + 1}",
                        text = "entry ${index + 1}",
                        fields = mapOf("category" to "bulk"),
                        vector = floatArrayOf(1f, 0f),
                    )
                    fixture.clock.advance(1.seconds)
                }

                val result =
                    fixture.store.query(
                        Query(
                            fields = mapOf("category" to "bulk"),
                        ),
                    )

                assertEquals(2, result.resultsPage.size)
                assertNotNull(result.queryOnId)
            }
        }

    @Test
    fun `query propagates unexpected errors`() =
        runTest {
            val failingPlanner = mockk<LuceneQueryPlanner>()
            every { failingPlanner.create(any()) } throws IllegalStateException("boom")
            newFixture(pageSize = 1, queryPlanner = failingPlanner).use { fixture ->
                fixture.store.addDocument("doc-1", "seed", emptyMap(), floatArrayOf(1f, 0f))
                assertFailsWith<IllegalStateException> {
                    fixture.store.query(Query())
                }
            }
        }

    @Test
    fun `query omits pagination when result page smaller than capacity`() =
        runTest {
            newFixture(pageSize = 2).use { fixture ->
                fixture.store.addDocument("doc-1", "single", mapOf("category" to "shared"), floatArrayOf(1f, 0f))

                val result =
                    fixture.store.query(
                        Query(
                            fields = mapOf("category" to "shared"),
                        ),
                    )

                assertEquals(1, result.resultsPage.size)
                assertNull(result.queryOnId)
            }
        }

    @Test
    fun `query re-throws exceptions other than IndexNotFoundException`() =
        runTest {
            val exception = RuntimeException("Test")
            val indexAccess =
                mockk<LuceneIndexAccess>(relaxUnitFun = true) {
                    coEvery { read<VectorStore.QueryResult>(any()) } throws exception
                }

            newFixture(pageSize = 1, indexAccess = indexAccess).use { fixture ->
                val ex =
                    assertFails {
                        fixture.store.query(Query())
                    }

                assertSame(exception, ex)
            }
        }

    @Test
    fun `dumpAll emits all documents and completes after last page`() =
        runTest {
            newFixture(pageSize = 1).use { fixture ->
                fixture.store.addDocument("first-id", "first", emptyMap(), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("second-id", "second", emptyMap(), floatArrayOf(0f, 1f))

                val results =
                    withTimeout(2_000) {
                        fixture.store.dumpAll().toList()
                    }

                assertEquals(listOf("second-id", "first-id"), results.map { it.id })
            }
        }

    @Test
    fun `dumpAll stops after incomplete final page`() =
        runTest {
            newFixture(pageSize = 2).use { fixture ->
                fixture.store.addDocument("first-id", "first", emptyMap(), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("second-id", "second", emptyMap(), floatArrayOf(0f, 1f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("third-id", "third", emptyMap(), floatArrayOf(1f, 1f))

                val results =
                    withTimeout(2_000) {
                        fixture.store.dumpAll().toList()
                    }

                assertEquals(listOf("third-id", "second-id", "first-id"), results.map { it.id })
            }
        }

    @Test
    fun `dumpAll re-throws exceptions other than IndexNotFoundException`() =
        runTest {
            val exception = RuntimeException("Test")
            val indexAccess =
                mockk<LuceneIndexAccess>(relaxUnitFun = true) {
                    coEvery { read<List<VectorStore.PostDump>>(any()) } throws exception
                }

            newFixture(pageSize = 1, indexAccess = indexAccess).use { fixture ->
                val ex =
                    assertFails {
                        fixture.store.dumpAll().toList()
                    }

                assertSame(exception, ex)
            }
        }

    @Test
    fun `dumpAll ignores IndexNotFoundException`() =
        runTest {
            val exception = IndexNotFoundException("Test")
            val indexAccess =
                mockk<LuceneIndexAccess>(relaxUnitFun = true) {
                    coEvery { read<List<VectorStore.PostDump>>(any()) } throws exception
                }

            newFixture(pageSize = 1, indexAccess = indexAccess).use { fixture ->
                val result = fixture.store.dumpAll().toList()
                assertEquals(emptyList(), result)
            }
        }

    @Test
    fun `fetch returns posts in request order`() =
        runTest {
            newFixture(pageSize = 4).use { fixture ->
                fixture.store.addDocument("doc-1", "first", mapOf("tag" to "a"), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-2", "second", mapOf("tag" to "b"), floatArrayOf(0f, 1f))

                val result = fixture.store.fetch(listOf("doc-2", "doc-1"))

                assertEquals(
                    listOf(
                        VectorStore.PostDump("doc-2", "second", mapOf("tag" to "b")),
                        VectorStore.PostDump("doc-1", "first", mapOf("tag" to "a")),
                    ),
                    result,
                )
            }
        }

    @Test
    fun `fetch ignores missing ids`() =
        runTest {
            newFixture(pageSize = 4).use { fixture ->
                fixture.store.addDocument("doc-1", "only", mapOf("tag" to "present"), floatArrayOf(1f, 0f))

                val result = fixture.store.fetch(listOf("missing", "doc-1"))

                assertEquals(
                    listOf(VectorStore.PostDump("doc-1", "only", mapOf("tag" to "present"))),
                    result,
                )
            }
        }

    @Test
    fun `fetchByFieldValues returns matching posts in deterministic order`() =
        runTest {
            newFixture(pageSize = 1).use { fixture ->
                fixture.store.addDocument("doc-1", "first", mapOf("tag" to "a"), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-2", "second", mapOf("tag" to "b"), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-3", "third", mapOf("tag" to "a"), floatArrayOf(1f, 0f))

                val result = fixture.store.fetchByFieldValues("tag", setOf("a", "c"))

                assertEquals(
                    listOf(
                        VectorStore.PostDump("doc-3", "third", mapOf("tag" to "a")),
                        VectorStore.PostDump("doc-1", "first", mapOf("tag" to "a")),
                    ),
                    result,
                )
            }
        }

    @Test
    fun `fetchByFieldValues stops after incomplete final page`() =
        runTest {
            newFixture(pageSize = 2).use { fixture ->
                fixture.store.addDocument("doc-1", "first", mapOf("tag" to "a"), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-2", "second", mapOf("tag" to "a"), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-3", "third", mapOf("tag" to "a"), floatArrayOf(1f, 0f))

                val result = fixture.store.fetchByFieldValues("tag", setOf("a"))

                assertEquals(
                    listOf(
                        VectorStore.PostDump("doc-3", "third", mapOf("tag" to "a")),
                        VectorStore.PostDump("doc-2", "second", mapOf("tag" to "a")),
                        VectorStore.PostDump("doc-1", "first", mapOf("tag" to "a")),
                    ),
                    result,
                )
            }
        }

    @Test
    fun `fetchByFieldValues returns empty list for empty values`() =
        runTest {
            newFixture(pageSize = 4).use { fixture ->
                fixture.store.addDocument("doc-1", "first", mapOf("tag" to "a"), floatArrayOf(1f, 0f))

                val result = fixture.store.fetchByFieldValues("tag", emptySet())

                assertEquals(emptyList(), result)
            }
        }

    @Test
    fun `fetch ignores IndexNotFoundException`() =
        runTest {
            val exception = IndexNotFoundException("Test")
            val indexAccess =
                mockk<LuceneIndexAccess>(relaxUnitFun = true) {
                    coEvery { read<List<VectorStore.PostDump>>(any()) } throws exception
                }

            newFixture(pageSize = 1, indexAccess = indexAccess).use { fixture ->
                val result = fixture.store.fetch(listOf("id-1"))

                assertEquals(emptyList(), result)
            }
        }

    @Test
    fun `fetchByFieldValues ignores IndexNotFoundException`() =
        runTest {
            val exception = IndexNotFoundException("Test")
            val indexAccess =
                mockk<LuceneIndexAccess>(relaxUnitFun = true) {
                    coEvery { read<List<VectorStore.PostDump>>(any()) } throws exception
                }

            newFixture(pageSize = 1, indexAccess = indexAccess).use { fixture ->
                val result = fixture.store.fetchByFieldValues("tag", setOf("id-1"))

                assertEquals(emptyList(), result)
            }
        }

    @Test
    fun `fetch re-throws exceptions other than IndexNotFoundException`() =
        runTest {
            val exception = RuntimeException("Test")
            val indexAccess =
                mockk<LuceneIndexAccess>(relaxUnitFun = true) {
                    coEvery { read<List<VectorStore.PostDump>>(any()) } throws exception
                }

            newFixture(pageSize = 1, indexAccess = indexAccess).use { fixture ->
                val ex =
                    assertFails {
                        fixture.store.fetch(listOf("id-1"))
                    }

                assertSame(exception, ex)
            }
        }

    @Test
    fun `fetchByFieldValues re-throws exceptions other than IndexNotFoundException`() =
        runTest {
            val exception = RuntimeException("Test")
            val indexAccess =
                mockk<LuceneIndexAccess>(relaxUnitFun = true) {
                    coEvery { read<List<VectorStore.PostDump>>(any()) } throws exception
                }

            newFixture(pageSize = 1, indexAccess = indexAccess).use { fixture ->
                val ex =
                    assertFails {
                        fixture.store.fetchByFieldValues("tag", setOf("id-1"))
                    }

                assertSame(exception, ex)
            }
        }

    @Test
    fun `queryOn uses time sorting when registry stored sort`() =
        runTest {
            newFixture(pageSize = 1).use { fixture ->
                fixture.store.addDocument("doc-1", "first", mapOf("category" to "shared"), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-2", "second", mapOf("category" to "shared"), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-3", "third", mapOf("category" to "shared"), floatArrayOf(1f, 0f))

                val initial =
                    fixture.store.query(
                        Query(
                            fields = mapOf("category" to "shared"),
                        ),
                    )
                val firstPageId = initial.queryOnId!!

                val followUp = fixture.store.queryOn(firstPageId)

                assertEquals("doc-2", followUp.resultsPage.first().id)
                assertNotNull(followUp.queryOnId)
                assertNull(fixture.registry.get(firstPageId))
            }
        }

    @Test
    fun `queryOn clears pagination after full final page`() =
        runTest {
            newFixture(pageSize = 2).use { fixture ->
                repeat(4) { index ->
                    fixture.store.addDocument(
                        id = "full-${index + 1}",
                        text = "entry ${index + 1}",
                        fields = mapOf("category" to "full"),
                        vector = floatArrayOf(1f, 0f),
                    )
                    fixture.clock.advance(1.seconds)
                }

                val initial =
                    fixture.store.query(
                        Query(
                            fields = mapOf("category" to "full"),
                        ),
                    )
                val firstPageId = initial.queryOnId!!
                val followUp = fixture.store.queryOn(firstPageId)

                assertEquals(2, followUp.resultsPage.size)
                assertNotNull(followUp.queryOnId)
            }
        }

    @Test
    fun `queryOn stops pagination when final page incomplete`() =
        runTest {
            newFixture(pageSize = 2).use { fixture ->
                fixture.store.addDocument("doc-1", "first", mapOf("category" to "shared"), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-2", "second", mapOf("category" to "shared"), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-3", "third", mapOf("category" to "shared"), floatArrayOf(1f, 0f))

                val initial =
                    fixture.store.query(
                        Query(
                            fields = mapOf("category" to "shared"),
                        ),
                    )
                val firstPageId = initial.queryOnId!!

                val followUp = fixture.store.queryOn(firstPageId)

                assertEquals(1, followUp.resultsPage.size)
                assertNull(followUp.queryOnId)
            }
        }

    @Test
    fun `queryOn uses knn continuation when registry stored vector query`() =
        runTest {
            newFixture(pageSize = 1).use { fixture ->
                fixture.store.addDocument("doc-1", "north", mapOf("category" to "shared"), floatArrayOf(1f, 0f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-2", "east", mapOf("category" to "shared"), floatArrayOf(0f, 1f))
                fixture.clock.advance(1.seconds)
                fixture.store.addDocument("doc-3", "north-east", mapOf("category" to "shared"), floatArrayOf(0.7f, 0.7f))

                val initial =
                    fixture.store.query(
                        Query(
                            vector = floatArrayOf(1f, 0f),
                        ),
                    )
                val firstPageId = initial.queryOnId!!

                val followUp = fixture.store.queryOn(firstPageId)
                val secondPageId = followUp.queryOnId!!
                val finalPage = fixture.store.queryOn(secondPageId)
                val trailingId = finalPage.queryOnId

                assertEquals("doc-3", followUp.resultsPage.first().id)
                assertEquals("doc-2", finalPage.resultsPage.first().id)
                assertNull(fixture.registry.get(firstPageId))
                assertNull(fixture.registry.get(secondPageId))
                if (trailingId != null) {
                    val trailingPage = fixture.store.queryOn(trailingId)
                    assertTrue(trailingPage.resultsPage.isEmpty())
                    assertNull(trailingPage.queryOnId)
                    assertNull(fixture.registry.get(trailingId))
                }
            }
        }

    @Test
    fun `queryOn returns empty result when id missing`() =
        runTest {
            newFixture(pageSize = 1).use { fixture ->
                val result = fixture.store.queryOn(null)
                assertTrue(result.resultsPage.isEmpty())
                assertNull(result.queryOnId)

                val randomResult = fixture.store.queryOn(UUID.randomUUID())
                assertTrue(randomResult.resultsPage.isEmpty())
                assertNull(randomResult.queryOnId)
            }
        }

    @Test
    fun `queryOn re-throws exceptions other than IndexNotFoundException`() =
        runTest {
            val exception = RuntimeException("Test")
            val indexAccess =
                mockk<LuceneIndexAccess>(relaxUnitFun = true) {
                    coEvery { read<VectorStore.QueryResults>(any()) } throws exception
                }
            val queryOnRegistry = mockk<QueryOnRegistry>(relaxed = true)

            newFixture(pageSize = 1, indexAccess = indexAccess, queryOnRegistry = queryOnRegistry).use { fixture ->
                val ex =
                    assertFails {
                        fixture.store.queryOn(UUID.randomUUID())
                    }

                assertSame(exception, ex)
            }
        }

    @Test
    fun `operations fail after close`() =
        runTest {
            val fixture = newFixture(pageSize = 1)
            fixture.store.close()

            assertFailsWith<RuntimeException> {
                fixture.store.writeAccess { }
            }
            assertFailsWith<RuntimeException> {
                fixture.store.query(Query())
            }
            assertFailsWith<RuntimeException> {
                fixture.store.queryOn(UUID.randomUUID())
            }
            assertFailsWith<RuntimeException> {
                fixture.store.dumpAll().toList()
            }
            assertFailsWith<RuntimeException> {
                fixture.store.fetch(emptyList())
            }
            assertFailsWith<RuntimeException> {
                fixture.store.fetchByFieldValues("tag", setOf("value"))
            }
            fixture.close()
        }

    private fun newFixture(
        pageSize: Int,
        vectorSize: Int = 2,
        queryPlanner: LuceneQueryPlanner = LuceneQueryPlanner(maxResults = 10),
        indexAccess: LuceneIndexAccess? = null,
        queryOnRegistry: QueryOnRegistry? = null,
    ): StoreFixture {
        val registry = queryOnRegistry ?: QueryOnRegistry(pageSize = pageSize, maxNotFinishedQueries = 10)
        val indexAccess = indexAccess ?: LuceneIndexAccess { ByteBuffersDirectory() }
        val clock = TestClock(Instant.ofEpochSecond(1))
        val store =
            LuceneLocalVectorStore.create(
                indexAccess = indexAccess,
                paginationRegistry = registry,
                queryPlanner = queryPlanner,
                clock = clock,
                vectorSize = vectorSize,
            ) as LuceneLocalVectorStore
        return StoreFixture(store, registry, clock)
    }

    private class StoreFixture(
        val store: LuceneLocalVectorStore,
        val registry: QueryOnRegistry,
        val clock: TestClock,
    ) : AutoCloseable {
        override fun close() {
            store.close()
        }
    }

    private suspend fun VectorStore.addDocument(
        id: String,
        text: String,
        fields: Map<String, String>,
        vector: FloatArray,
    ) {
        writeAccess {
            add(id, text, fields, vector)
        }
    }
}
