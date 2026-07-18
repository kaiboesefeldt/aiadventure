package de.aidadventure.server.vectorstore

import org.apache.lucene.document.Document
import org.apache.lucene.document.DocumentStoredFieldVisitor
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.search.ScoreDoc
import de.aidadventure.server.VectorStore

internal class LuceneStoredDocumentReader {
    fun toSearchResult(
        scoreDoc: ScoreDoc,
        reader: DirectoryReader,
    ): VectorStore.QueryResult {
        val doc = scoreDoc.getDocument(reader)
        val fields = doc.getFieldMap()
        return VectorStore.QueryResult(
            id = doc[ID_FIELD_NAME],
            text = doc[TEXT_FIELD_NAME],
            similarity = scoreDoc.score.takeUnless(Float::isNaN) ?: 1.0f,
            fields = fields,
        )
    }

    fun toPostDump(
        scoreDoc: ScoreDoc,
        reader: DirectoryReader,
    ): VectorStore.PostDump {
        val doc = scoreDoc.getDocument(reader)
        val fields = doc.getFieldMap()
        return VectorStore.PostDump(
            id = doc[ID_FIELD_NAME],
            text = doc[TEXT_FIELD_NAME],
            fields = fields,
        )
    }

    private fun ScoreDoc.getDocument(reader: DirectoryReader): Document {
        val visitor = DocumentStoredFieldVisitor()
        reader.storedFields().document(doc, visitor)
        return visitor.getDocument()
    }

    private fun Document.getFieldMap(): Map<String, String> =
        fields
            .filter { it.name() !in SPECIAL_FIELDS }
            .associate { it.name() to it.storedValue().stringValue }
}
