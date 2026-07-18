package de.aidadventure.server

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

interface VectorStore : AutoCloseable {
    data class QueryResults(
        val resultsPage: List<QueryResult>,
        val queryOnId: UUID?,
    )

    data class QueryResult(
        val id: String,
        val text: String,
        val similarity: Float,
        val fields: Map<String, String>,
    )

    data class PostDump(
        val id: String,
        val text: String,
        val fields: Map<String, String>,
    )

    data class Query(
        val id: String? = null,
        val vector: FloatArray? = null,
        val fromTime: Instant? = null,
        val toTime: Instant? = null,
        val scoreMustBeAbove: Float? = null,
        val scoreMustBeBelow: Float? = null,
        val fields: Map<String, String?> = emptyMap(),
    ) {
        @Suppress("IDENTITY_SENSITIVE_OPERATIONS_WITH_VALUE_TYPE")
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Query

            if (id != other.id) return false
            if (vector == null) {
                if (other.vector != null) return false
            } else {
                if (other.vector == null) return false
                if (!vector.contentEquals(other.vector)) return false
            }
            if (fromTime != other.fromTime) return false
            if (toTime != other.toTime) return false
            if (fields != other.fields) return false

            return true
        }

        override fun hashCode(): Int {
            var result = id?.hashCode() ?: 0
            result = 31 * result + (vector?.contentHashCode() ?: 0)
            result = 31 * result + (fromTime?.hashCode() ?: 0)
            result = 31 * result + (toTime?.hashCode() ?: 0)
            result = 31 * result + fields.hashCode()
            return result
        }
    }

    interface Transaction {
        suspend fun add(
            id: String,
            text: String,
            fields: Map<String, String>,
            vector: FloatArray,
        )

        suspend fun remove(id: String)
    }

    suspend fun writeAccess(transactionBlock: suspend Transaction.() -> Unit)

    suspend fun query(query: Query): QueryResults

    suspend fun queryOn(queryOnId: UUID?): QueryResults

    suspend fun fetch(ids: List<String>): List<PostDump>

    suspend fun fetchByFieldValues(
        fieldName: String,
        values: Set<String>,
    ): List<PostDump>

    fun dumpAll(): Flow<PostDump>
}
