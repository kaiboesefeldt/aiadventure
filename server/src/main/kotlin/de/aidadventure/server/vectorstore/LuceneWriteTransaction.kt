package de.aidadventure.server.vectorstore

import io.ktor.server.plugins.BadRequestException
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.LongPoint
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.Term
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import de.aidadventure.server.VectorStore
import java.time.Clock

class LuceneWriteTransaction(
    private val reader: DirectoryReader?,
    private val writer: IndexWriter,
    private val clock: Clock,
    private val vectorSize: Int,
) : VectorStore.Transaction {
    private val addedItemIds = mutableSetOf<String>()

    override suspend fun add(
        id: String,
        text: String,
        fields: Map<String, String>,
        vector: FloatArray,
    ) {
        if (vector.size != vectorSize) {
            throw RuntimeException("Vector size mismatch: ${vector.size} vs. $vectorSize")
        }
        if ((fields.keys intersect SPECIAL_FIELDS).isNotEmpty()) {
            throw RuntimeException("Usage of special field name(s): ${(fields.keys intersect SPECIAL_FIELDS)}")
        }
        if (id in addedItemIds || reader?.containsId(id) == true) {
            throw BadRequestException("Item with ID $id already exists (text: $text, fields: $fields)")
        }

        addedItemIds.add(id)
        writer.addDocument(documentFor(id, text, fields, vector))
    }

    override suspend fun remove(id: String) {
        writer.deleteDocuments(Term(ID_FIELD_NAME, id))
    }

    private fun documentFor(
        id: String,
        text: String,
        fields: Map<String, String>,
        vector: FloatArray,
    ): Document {
        val timeAdded = clock.instant().epochSecond
        val document = Document()
        document.add(StringField(ID_FIELD_NAME, id, Field.Store.YES))
        document.add(TextField(TEXT_FIELD_NAME, text, Field.Store.YES))
        document.add(LongPoint(TIME_ADDED_FIELD_NAME, timeAdded))
        document.add(NumericDocValuesField(TIME_ADDED_FIELD_NAME, timeAdded))
        fields.forEach { (key, value) ->
            document.add(StringField(key, value, Field.Store.YES))
        }
        document.add(KnnFloatVectorField(VECTOR_FIELD_NAME, vector, VectorSimilarityFunction.COSINE))
        return document
    }

    private fun DirectoryReader.containsId(itemId: String): Boolean {
        val searcher = IndexSearcher(this)
        val result = searcher.search(TermQuery(Term(ID_FIELD_NAME, itemId)), 1)
        return result.scoreDocs.isNotEmpty()
    }
}
