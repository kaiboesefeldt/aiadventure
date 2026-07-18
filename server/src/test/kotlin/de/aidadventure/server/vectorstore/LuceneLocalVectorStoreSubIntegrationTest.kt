package de.aidadventure.server.vectorstore

import io.ktor.server.plugins.BadRequestException
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.apache.lucene.store.ByteBuffersDirectory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import de.aidadventure.server.VectorStore
import de.aidadventure.server.VectorStore.Query
import de.aidadventure.server.testutils.TestClock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class LuceneLocalVectorStoreSubIntegrationTest {
    private val test get() =
        object : AutoCloseable {
            val testClock = TestClock(Instant.ofEpochSecond(170000000L))
            val indexAccess = LuceneIndexAccess { ByteBuffersDirectory() }
            val sut =
                LuceneLocalVectorStore.create(
                    indexAccess,
                    paginationRegistry = QueryOnRegistry(pageSize = 3, maxNotFinishedQueries = 2),
                    queryPlanner = LuceneQueryPlanner(maxResults = 10),
                    clock = testClock,
                    vectorSize = 3,
                )

            override fun close() {
                sut.close()
                indexAccess.close()
            }
        }

    private operator fun <T : AutoCloseable> T.invoke(block: suspend T.() -> Unit): Unit =
        apply {
            runTest { block() }
        }.close()

    @Nested
    inner class Construction {
        @Test
        fun `non-positive maxNotFinishedQueries throws exception`() {
            LuceneIndexAccess { ByteBuffersDirectory() }.use { indexAccess ->
                assertThrows<RuntimeException> {
                    LuceneLocalVectorStore.create(
                        indexAccess,
                        paginationRegistry =
                            QueryOnRegistry(
                                pageSize = 3,
                                maxNotFinishedQueries = 0,
                            ),
                        queryPlanner = LuceneQueryPlanner(maxResults = 10),
                        clock = mockk(),
                        vectorSize = 3,
                    )
                }
            }
        }
    }

    @Nested
    inner class Add {
        @Test
        fun `adding invalid vector size throws exception`() =
            test {
                sut.writeAccess {
                    assertThrows<RuntimeException> {
                        add(
                            id = "id",
                            text = "Text",
                            fields = mapOf("Hello" to "world"),
                            vector = vector(1, 2),
                        )
                    }
                }
            }

        @Test
        fun `adding special name of field throws exception`() =
            test {
                sut.writeAccess {
                    listOf("id", "text", "vector").forEach { invalidName ->
                        assertThrows<RuntimeException> {
                            add(
                                id = "id",
                                text = "Text",
                                fields = mapOf(invalidName to "value"),
                                vector = vector,
                            )
                        }
                    }
                }
            }

        @Test
        fun `adding the same record twice in the same transaction throws exception`() =
            test {
                sut.writeAccess {
                    assertDoesNotThrow {
                        add(
                            id = "id",
                            text = "Text",
                            fields = emptyMap(),
                            vector = vector,
                        )
                    }
                    assertThrows<BadRequestException> {
                        add(
                            id = "id",
                            text = "Text",
                            fields = emptyMap(),
                            vector = vector,
                        )
                    }
                }
            }

        @Test
        fun `adding the same record twice in the different transactions throws exception`() =
            test {
                sut.writeAccess {
                    assertDoesNotThrow {
                        add(
                            id = "id",
                            text = "Text",
                            fields = emptyMap(),
                            vector = vector,
                        )
                    }
                }
                sut.writeAccess {
                    assertThrows<BadRequestException> {
                        add(
                            id = "id",
                            text = "Text",
                            fields = emptyMap(),
                            vector = vector,
                        )
                    }
                }
            }
    }

    @Nested
    inner class Query {
        @Test
        fun `happy path - add item and retrieve it again`() =
            test {
                sut.writeAccess {
                    add(
                        id = "id",
                        text = "Text",
                        fields = mapOf("Hello" to "world"),
                        vector = vector,
                    )
                }

                val queryResult = sut.query(Query(vector = vector))

                assertEquals(
                    listOf(
                        VectorStore.QueryResult(
                            id = "id",
                            text = "Text",
                            similarity = 1.0f,
                            fields = mapOf("Hello" to "world"),
                        ),
                    ),
                    queryResult.resultsPage,
                )
                assertNull(queryResult.queryOnId)
            }

        @Test
        fun `items are retrieved ordered by similarity`() =
            test {
                sut.writeAccess {
                    (0..2).forEach {
                        add(
                            id = "id$it",
                            text = "Text",
                            fields = emptyMap(),
                            vector = vector(1.0, it.toDouble(), 0.0),
                        )
                        testClock.advance(-1.days)
                    }
                }

                val searchResult = sut.query(Query(vector = vector(0.0, 1.0, 0.0)))

                assertEquals(
                    listOf("id2", "id1", "id0"),
                    searchResult.resultsPage.map { it.id },
                )
            }

        @Test
        fun `items can be filtered by doc id`() =
            test {
                sut.writeAccess {
                    (0..2).forEach {
                        add(
                            id = "id$it",
                            text = "Text",
                            fields = emptyMap(),
                            vector = vector(1.0, it.toDouble(), 0.0),
                        )
                    }
                }

                val searchResult =
                    sut.query(Query(id = "id1"))

                assertEquals(
                    listOf("id1"),
                    searchResult.resultsPage.map { it.id },
                )
            }

        @Test
        fun `items can be filtered by field value`() =
            test {
                sut.writeAccess {
                    (0..2).forEach {
                        add(
                            id = "id$it",
                            text = "Text",
                            fields = mapOf("tag" to "${it != 1}"),
                            vector = vector(1.0, it.toDouble(), 0.0),
                        )
                        testClock.advance(1.days)
                    }
                }

                val searchResult =
                    sut.query(
                        Query(fields = mapOf("tag" to "true")),
                    )

                assertEquals(
                    listOf("id2", "id0"),
                    searchResult.resultsPage.map { it.id },
                )
            }

        @Test
        fun `querying for null field value excludes documents with field set`() =
            test {
                sut.writeAccess {
                    add(
                        id = "withoutField",
                        text = "Text",
                        fields = emptyMap(),
                        vector = vector,
                    )
                    testClock.advance(1.seconds)
                    add(
                        id = "withField",
                        text = "Text",
                        fields = mapOf("tag" to "value"),
                        vector = vector,
                    )
                }

                val searchResult =
                    sut.query(
                        Query(fields = mapOf("tag" to null)),
                    )

                assertEquals(
                    listOf("withoutField"),
                    searchResult.resultsPage.map { it.id },
                )
            }

        @Test
        fun `items can be filtered by min time`() =
            test {
                val itemTimes = mutableListOf<Instant>()
                sut.writeAccess {
                    (0..2).forEach {
                        add(
                            id = "id$it",
                            text = "Text",
                            fields = emptyMap(),
                            vector = vector(1.0, it.toDouble(), 0.0),
                        )
                        itemTimes.add(testClock.instant())
                        testClock.advance(1.days)
                    }
                }

                val searchResult =
                    sut.query(Query(fromTime = itemTimes[1] - 1.seconds.toJavaDuration()))

                assertEquals(
                    listOf("id2", "id1"),
                    searchResult.resultsPage.map { it.id },
                )
            }

        @Test
        fun `items can be filtered by max time`() =
            test {
                val itemTimes = mutableListOf<Instant>()
                sut.writeAccess {
                    (0..2).forEach {
                        add(
                            id = "id$it",
                            text = "Text",
                            fields = emptyMap(),
                            vector = vector(1.0, it.toDouble(), 0.0),
                        )
                        itemTimes.add(testClock.instant())
                        testClock.advance(1.days)
                    }
                }

                val searchResult =
                    sut.query(Query(toTime = itemTimes[1] + 1.seconds.toJavaDuration()))

                assertEquals(
                    listOf("id1", "id0"),
                    searchResult.resultsPage.map { it.id },
                )
            }

        @Test
        fun `items can be filtered by time range`() =
            test {
                val itemTimes = mutableListOf<Instant>()
                sut.writeAccess {
                    (0..2).forEach {
                        add(
                            id = "id$it",
                            text = "Text",
                            fields = emptyMap(),
                            vector = vector(1.0, it.toDouble(), 0.0),
                        )
                        itemTimes.add(testClock.instant())
                        testClock.advance(1.days)
                    }
                }

                val searchResult =
                    sut.query(
                        Query(
                            fromTime = itemTimes[1] - 1.seconds.toJavaDuration(),
                            toTime = itemTimes[1] + 1.seconds.toJavaDuration(),
                        ),
                    )

                assertEquals(
                    listOf("id1"),
                    searchResult.resultsPage.map { it.id },
                )
            }

        @Test
        fun `getting exactly as many results as pageSize yields null queryOnId`() =
            test {
                sut.writeAccess {
                    (1..3).forEach {
                        add(
                            id = "id$it",
                            text = "Text",
                            fields = mapOf("Hello" to "world"),
                            vector = vector,
                        )
                    }
                }

                val searchResult = sut.query(Query(vector = vector))

                assertEquals(3, searchResult.resultsPage.size)
                assertNull(searchResult.queryOnId)
            }

        @Test
        fun `values added in multiple transactions are available for searching`() =
            test {
                (1..3).forEach { numItems ->
                    sut.writeAccess {
                        add(
                            id = "id$numItems",
                            text = "Text",
                            fields = mapOf("Hello" to "world"),
                            vector = vector,
                        )
                    }

                    val searchResult = sut.query(Query(vector = vector))

                    assertEquals(
                        (1..numItems).map { "id$it" },
                        searchResult.resultsPage.map { it.id },
                    )
                    assertNull(searchResult.queryOnId)
                }
            }

        @Test
        fun `getting exactly one result more than pageSize yields a queryOnId`() =
            test {
                sut.writeAccess {
                    (1..4).forEach {
                        add(
                            id = "id$it",
                            text = "Text",
                            fields = mapOf("Hello" to "world"),
                            vector = vector,
                        )
                    }
                }

                val searchResult = sut.query(Query(vector = vector))

                assertEquals(3, searchResult.resultsPage.size)
                assertNotNull(searchResult.queryOnId)
            }

        @Test
        fun `searching initial index yields empty result`() =
            test {
                val searchResult = sut.query(Query(vector = vector))

                assertEquals(
                    emptyList(),
                    searchResult.resultsPage,
                )
                assertNull(searchResult.queryOnId)
            }

        @Test
        fun `searching for document id`() =
            test {
                sut.writeAccess {
                    add(
                        id = "id0",
                        text = "other",
                        fields = emptyMap(),
                        vector = vector(1.0, 0.0, 0.0),
                    )
                    add(
                        id = "id1",
                        text = "target",
                        fields = mapOf("tag" to "value"),
                        vector = vector(0.0, 1.0, 0.0),
                    )
                }

                val searchResult = sut.query(Query(id = "id1"))

                assertEquals(
                    listOf(
                        VectorStore.QueryResult(
                            id = "id1",
                            text = "target",
                            similarity = 1.0f,
                            fields = mapOf("tag" to "value"),
                        ),
                    ),
                    searchResult.resultsPage,
                )
                assertNull(searchResult.queryOnId)
            }
    }

    @Nested
    inner class QueryOnKnn {
        @Test
        fun `searching on the next page of knn search results`() =
            test {
                sut.writeAccess {
                    (1..4).forEach {
                        add(
                            id = "id$it",
                            text = "Text",
                            fields = mapOf("Hello" to "world"),
                            vector = vector + vector(1.0, 0.0, 0.0),
                        )
                    }
                }

                val initialQueryResult = sut.query(Query(vector = vector))
                val queryOnResult = sut.queryOn(initialQueryResult.queryOnId)

                assertEquals(
                    listOf("id4"),
                    queryOnResult.resultsPage.map { it.id },
                )
                assertNull(queryOnResult.queryOnId)
            }

        @Test
        fun `searching on the pager after the next page of knn search results`() =
            test {
                sut.writeAccess {
                    (1..7).forEach {
                        add(
                            id = "id$it",
                            text = "Text",
                            fields = mapOf("Hello" to "world"),
                            vector = vector + vector(1.0, 0.0, 0.0),
                        )
                    }
                }

                val searchResult =
                    sut.queryOn(
                        sut
                            .queryOn(
                                sut.query(Query(vector = vector)).queryOnId,
                            ).queryOnId,
                    )

                assertEquals(
                    listOf("id7"),
                    searchResult.resultsPage.map { it.id },
                )
                assertNull(searchResult.queryOnId)
            }

        @Test
        fun `searching on with vanished searchOnId yields empty knn search results`() =
            test {
                sut.writeAccess {
                    add(
                        id = "id",
                        text = "Text",
                        fields = mapOf("Hello" to "world"),
                        vector = vector + vector(1.0, 0.0, 0.0),
                    )
                }

                val searchResult = sut.queryOn(UUID.randomUUID())

                assertEquals(emptyList(), searchResult.resultsPage)
                assertNull(searchResult.queryOnId)
            }

        @Test
        fun `searching on with null searchOnId yields empty knn search results`() =
            test {
                sut.writeAccess {
                    add(
                        id = "id",
                        text = "Text",
                        fields = mapOf("Hello" to "world"),
                        vector = vector + vector(1.0, 0.0, 0.0),
                    )
                }

                val searchResult = sut.queryOn(null)

                assertEquals(emptyList(), searchResult.resultsPage)
                assertNull(searchResult.queryOnId)
            }
    }

    @Nested
    inner class QueryOnField {
        @Test
        fun `searching on the next page of field search results`() =
            test {
                sut.writeAccess {
                    (1..4).forEach {
                        add(
                            id = "id$it",
                            text = "Text",
                            fields = mapOf("tag" to "value"),
                            vector = vector,
                        )
                        testClock.advance(1.days)
                    }
                }

                val initialQueryResult = sut.query(Query(fields = mapOf("tag" to "value")))
                val queryOnResult = sut.queryOn(initialQueryResult.queryOnId)

                assertEquals(
                    listOf("id1"),
                    queryOnResult.resultsPage.map { it.id },
                )
                assertNull(queryOnResult.queryOnId)
            }

        @Test
        fun `searching on the pager after the next page of field search results`() =
            test {
                sut.writeAccess {
                    (1..7).forEach {
                        add(
                            id = "id$it",
                            text = "Text",
                            fields = mapOf("tag" to "value"),
                            vector = vector + vector(1.0, 0.0, 0.0),
                        )
                    }
                }

                val searchResult =
                    sut.queryOn(
                        sut
                            .queryOn(
                                sut.query(Query(fields = mapOf("tag" to "value"))).queryOnId,
                            ).queryOnId,
                    )

                assertEquals(
                    listOf("id7"),
                    searchResult.resultsPage.map { it.id },
                )
                assertNull(searchResult.queryOnId)
            }
    }

    @Nested
    inner class Fetch {
        @Test
        fun `fetch returns documents in requested id order`() =
            test {
                sut.writeAccess {
                    add(
                        id = "id1",
                        text = "first",
                        fields = mapOf("tag" to "a"),
                        vector = vector,
                    )
                    testClock.advance(1.days)
                    add(
                        id = "id2",
                        text = "second",
                        fields = mapOf("tag" to "b"),
                        vector = vector,
                    )
                }

                val posts = sut.fetch(listOf("id2", "id1"))

                assertEquals(
                    listOf(
                        VectorStore.PostDump("id2", "second", mapOf("tag" to "b")),
                        VectorStore.PostDump("id1", "first", mapOf("tag" to "a")),
                    ),
                    posts,
                )
            }

        @Test
        fun `fetchByFieldValues returns all matching posts across multiple pages in time order`() =
            test {
                sut.writeAccess {
                    (1..4).forEach {
                        add(
                            id = "id$it",
                            text = "Text $it",
                            fields = mapOf("tag" to "value"),
                            vector = vector,
                        )
                        testClock.advance(1.days)
                    }
                }

                val posts = sut.fetchByFieldValues("tag", setOf("value"))

                assertEquals(
                    listOf("id4", "id3", "id2", "id1"),
                    posts.map { it.id },
                )
            }
    }

    @Nested
    inner class Remove {
        @Test
        fun `removing not existing data does nothing`() =
            test {
                sut.writeAccess {
                    assertDoesNotThrow { remove("notExistingId") }
                }
            }

        @Test
        fun `removing data previously added in same transaction is not available for search`() =
            test {
                sut.writeAccess {
                    add(
                        id = "id",
                        text = "Text",
                        fields = mapOf("Hello" to "world"),
                        vector = vector + vector(1.0, 0.0, 0.0),
                    )
                    remove("id")
                }

                val searchResult = sut.query(Query(vector = vector))

                assertEquals(emptyList(), searchResult.resultsPage)
            }

        @Test
        fun `removing data previously added in another transaction is not available for search`() =
            test {
                sut.writeAccess {
                    add(
                        id = "id",
                        text = "Text",
                        fields = mapOf("Hello" to "world"),
                        vector = vector + vector(1.0, 0.0, 0.0),
                    )
                }
                sut.writeAccess {
                    remove("id")
                }

                val searchResult = sut.query(Query(vector = vector))

                assertEquals(emptyList(), searchResult.resultsPage)
            }
    }

    @Nested
    inner class LifeCycleOfQueryOnRecords {
        @Test
        fun `oldest searchOn record is cleaned up when searchOn space is expired`() =
            test {
                sut.writeAccess {
                    (1..4).forEach {
                        add(
                            id = "id$it",
                            text = "Text",
                            fields = mapOf("Hello" to "world"),
                            vector = vector + vector(1.0, 0.0, 0.0),
                        )
                    }
                }

                val searchResults = (1..3).map { sut.query(Query(vector = vector)) }
                val searchOnResults = searchResults.map { sut.queryOn(it.queryOnId) }

                searchResults.forEach {
                    assertNotNull(it.queryOnId)
                    assertTrue(it.resultsPage.any())
                }
                assertTrue(searchOnResults.first().resultsPage.isEmpty())
                searchOnResults.drop(1).forEach {
                    assertEquals(
                        listOf("id4"),
                        it.resultsPage.map { it.id },
                    )
                }
            }

        @Test
        fun `concurrent query and queryOn calls keep pagination registry consistent`() =
            test {
                sut.writeAccess {
                    (1..30).forEach {
                        add(
                            id = "id$it",
                            text = "Text $it",
                            fields = mapOf("Hello" to "world"),
                            vector = vector + vector(1.0, 0.0, 0.0),
                        )
                        testClock.advance(1.days)
                    }
                }

                val pendingSearchOnIds = mutableListOf<UUID>()
                val pendingMutex = Mutex()
                val errors = mutableListOf<Throwable>()
                val errorMutex = Mutex()
                val iterations = 500

                coroutineScope {
                    val producer =
                        launch(Dispatchers.Default) {
                            repeat(iterations) {
                                val result =
                                    runCatching {
                                        sut.query(Query(vector = vector))
                                    }

                                result.onFailure {
                                    errorMutex.withLock { errors.add(it) }
                                }

                                result
                                    .getOrNull()
                                    ?.queryOnId
                                    ?.let { searchOnId ->
                                        pendingMutex.withLock { pendingSearchOnIds.add(searchOnId) }
                                    }
                                yield()
                            }
                        }

                    val consumer =
                        launch(Dispatchers.Default) {
                            repeat(iterations) {
                                val searchOnId =
                                    pendingMutex.withLock {
                                        if (pendingSearchOnIds.isEmpty()) {
                                            null
                                        } else {
                                            pendingSearchOnIds.removeAt(0)
                                        }
                                    }
                                        ?: run {
                                            yield()
                                            return@repeat
                                        }

                                val result =
                                    runCatching {
                                        sut.queryOn(searchOnId)
                                    }

                                result.onFailure {
                                    errorMutex.withLock { errors.add(it) }
                                }

                                result
                                    .getOrNull()
                                    ?.queryOnId
                                    ?.let { nextSearchOnId ->
                                        pendingMutex.withLock { pendingSearchOnIds.add(nextSearchOnId) }
                                    }
                                yield()
                            }
                        }

                    producer.join()
                    consumer.cancel()
                    consumer.join()
                }

                errorMutex.withLock {
                    assertTrue(errors.isEmpty())
                }
            }
    }

    @Nested
    inner class Close {
        @Test
        fun `duplicate call of close does nothing`() {
            test
                .apply {
                    assertDoesNotThrow { sut.close() }
                    assertDoesNotThrow { sut.close() }
                }
        }

        @Test
        fun `writeAccess after close throws exception`() {
            test
                .apply {
                    sut.close()
                    runTest {
                        assertThrows<RuntimeException> { sut.writeAccess { } }
                    }
                }
        }

        @Test
        fun `search after close throws exception`() {
            test
                .apply {
                    sut.close()
                    runTest {
                        assertThrows<RuntimeException> { sut.query(Query(vector = vector)) }
                    }
                }
        }

        @Test
        fun `searchOn after close throws exception`() {
            test
                .apply {
                    sut.close()
                    runTest {
                        assertThrows<RuntimeException> { sut.queryOn(UUID.randomUUID()) }
                    }
                }
        }
    }

    private companion object {
        fun vector(vararg components: Number) = FloatArray(components.size) { components[it].toFloat() }

        operator fun FloatArray.plus(other: FloatArray) = FloatArray(minOf(size, other.size)) { this[it] + other[it] }

        val vector = vector(1.0f, 2.0f, 4.0f)
    }
}
