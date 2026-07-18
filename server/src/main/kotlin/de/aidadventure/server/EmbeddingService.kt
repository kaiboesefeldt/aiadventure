package de.aidadventure.server

interface EmbeddingService {
    val dimension: Int

    suspend fun embed(text: String): FloatArray
}
