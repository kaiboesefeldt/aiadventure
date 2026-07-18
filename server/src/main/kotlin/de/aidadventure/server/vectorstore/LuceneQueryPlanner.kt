package de.aidadventure.server.vectorstore

import org.apache.lucene.document.LongPoint
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.WildcardQuery
import de.aidadventure.server.VectorStore
import java.time.Instant

class LuceneQueryPlanner(
    private val maxResults: Int,
) {
    fun create(query: VectorStore.Query): Query =
        BooleanQuery
            .Builder()
            .apply {
                addKnnQuery(query.vector)
                query.id?.let { addTermQuery(ID_FIELD_NAME, it) }
                addTimeRangeQuery(TIME_ADDED_FIELD_NAME, query.fromTime, query.toTime)
                query.fields.forEach { (key, value) -> addTermQuery(key, value) }
            }.build()
            .takeUnless { it.clauses().isEmpty() } ?: MatchAllDocsQuery.INSTANCE

    private fun BooleanQuery.Builder.addKnnQuery(vector: FloatArray?) =
        also {
            if (vector != null) {
                add(
                    KnnFloatVectorQuery(VECTOR_FIELD_NAME, vector, maxResults),
                    BooleanClause.Occur.MUST,
                )
            }
        }

    private fun BooleanQuery.Builder.addTermQuery(
        fieldName: String,
        value: String?,
    ) = also {
        when (value) {
            null -> {
                add(
                    MatchAllDocsQuery.INSTANCE,
                    BooleanClause.Occur.FILTER,
                )
                add(
                    WildcardQuery(Term(fieldName, "*")),
                    BooleanClause.Occur.MUST_NOT,
                )
            }

            else -> {
                add(
                    TermQuery(Term(fieldName, value)),
                    BooleanClause.Occur.FILTER,
                )
            }
        }
    }

    private fun BooleanQuery.Builder.addTimeRangeQuery(
        fieldName: String,
        from: Instant?,
        to: Instant?,
    ) = also {
        if (from != null || to != null) {
            add(
                LongPoint.newRangeQuery(
                    fieldName,
                    from?.epochSecond ?: Long.MIN_VALUE,
                    to?.epochSecond ?: Long.MAX_VALUE,
                ),
                BooleanClause.Occur.FILTER,
            )
        }
    }
}
