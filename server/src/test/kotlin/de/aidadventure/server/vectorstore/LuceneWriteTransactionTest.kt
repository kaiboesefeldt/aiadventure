package de.aidadventure.server.vectorstore

import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.test.runTest
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.DocumentStoredFieldVisitor
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.ByteBuffersDirectory
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LuceneWriteTransactionTest {
    private val clock = Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC)

    @Test
    fun `add rejects mismatched vector size`() =
        runTest {
            val directory = ByteBuffersDirectory()
            val analyzer = StandardAnalyzer()
            val writer = IndexWriter(directory, IndexWriterConfig(analyzer))
            try {
                val transaction = LuceneWriteTransaction(reader = null, writer = writer, clock = clock, vectorSize = 3)
                assertFailsWith<RuntimeException> {
                    transaction.add("id-1", "text", emptyMap(), floatArrayOf(0.1f, 0.2f))
                }
            } finally {
                writer.close()
                analyzer.close()
                directory.close()
            }
        }

    @Test
    fun `add rejects usage of special fields`() =
        runTest {
            val directory = ByteBuffersDirectory()
            val analyzer = StandardAnalyzer()
            val writer = IndexWriter(directory, IndexWriterConfig(analyzer))
            try {
                val transaction = LuceneWriteTransaction(reader = null, writer = writer, clock = clock, vectorSize = 2)
                assertFailsWith<RuntimeException> {
                    transaction.add(
                        id = "id-1",
                        text = "text",
                        fields = mapOf(ID_FIELD_NAME to "value"),
                        vector = floatArrayOf(0.1f, 0.2f),
                    )
                }
            } finally {
                writer.close()
                analyzer.close()
                directory.close()
            }
        }

    @Test
    fun `add rejects duplicate id within transaction`() =
        runTest {
            val directory = ByteBuffersDirectory()
            val analyzer = StandardAnalyzer()
            val writer = IndexWriter(directory, IndexWriterConfig(analyzer))
            try {
                val transaction = LuceneWriteTransaction(reader = null, writer = writer, clock = clock, vectorSize = 2)
                transaction.add("id-1", "text", emptyMap(), floatArrayOf(0.1f, 0.2f))
                assertFailsWith<BadRequestException> {
                    transaction.add("id-1", "other", emptyMap(), floatArrayOf(0.1f, 0.2f))
                }
            } finally {
                writer.close()
                analyzer.close()
                directory.close()
            }
        }

    @Test
    fun `add rejects id already present in index`() =
        runTest {
            val directory = ByteBuffersDirectory()
            val analyzer = StandardAnalyzer()
            val writer = IndexWriter(directory, IndexWriterConfig(analyzer))
            val document =
                Document().apply {
                    add(StringField(ID_FIELD_NAME, "existing", Field.Store.YES))
                    add(StringField(TEXT_FIELD_NAME, "text", Field.Store.YES))
                }
            writer.addDocument(document)
            writer.commit()
            val reader = DirectoryReader.open(directory)
            try {
                val transaction =
                    LuceneWriteTransaction(
                        reader = reader,
                        writer = writer,
                        clock = clock,
                        vectorSize = 2,
                    )
                assertFailsWith<BadRequestException> {
                    transaction.add("existing", "new", emptyMap(), floatArrayOf(0.1f, 0.2f))
                }
            } finally {
                reader.close()
                writer.close()
                analyzer.close()
                directory.close()
            }
        }

    @Test
    fun `add writes stored fields`() =
        runTest {
            val directory = ByteBuffersDirectory()
            val analyzer = StandardAnalyzer()
            val writer = IndexWriter(directory, IndexWriterConfig(analyzer))
            try {
                val transaction = LuceneWriteTransaction(reader = null, writer = writer, clock = clock, vectorSize = 2)
                transaction.add(
                    id = "id-1",
                    text = "body text",
                    fields = mapOf("category" to "news"),
                    vector = floatArrayOf(0.1f, 0.2f),
                )
                writer.commit()
                val reader = DirectoryReader.open(directory)
                try {
                    val visitor = DocumentStoredFieldVisitor()
                    reader.storedFields().document(0, visitor)
                    val document = visitor.getDocument()
                    assertEquals("id-1", document[ID_FIELD_NAME])
                    assertEquals("body text", document[TEXT_FIELD_NAME])
                    assertEquals("news", document["category"])
                } finally {
                    reader.close()
                }
            } finally {
                writer.close()
                analyzer.close()
                directory.close()
            }
        }

    @Test
    fun `remove deletes document by id`() =
        runTest {
            val directory = ByteBuffersDirectory()
            val analyzer = StandardAnalyzer()
            val writer = IndexWriter(directory, IndexWriterConfig(analyzer))
            try {
                val transaction = LuceneWriteTransaction(reader = null, writer = writer, clock = clock, vectorSize = 2)
                transaction.add("id-1", "text", emptyMap(), floatArrayOf(0.1f, 0.2f))
                writer.commit()
                transaction.remove("id-1")
                writer.commit()
                val reader = DirectoryReader.open(directory)
                try {
                    val searcher = IndexSearcher(reader)
                    val result = searcher.search(TermQuery(Term(ID_FIELD_NAME, "id-1")), 1)
                    assertEquals(0, result.totalHits.value)
                } finally {
                    reader.close()
                }
            } finally {
                writer.close()
                analyzer.close()
                directory.close()
            }
        }
}
