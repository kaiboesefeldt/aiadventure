package de.aidadventure.server.embedding

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory
import ai.djl.repository.zoo.Criteria.builder
import ai.djl.repository.zoo.ModelZoo
import ai.djl.repository.zoo.ZooModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import de.aidadventure.server.EmbeddingService

class OnnxLocalEmbeddingService private constructor(
    modelUrl: String = DEFAULT_MODEL_URL,
) : EmbeddingService,
    AutoCloseable {
    private val model = loadModel(modelUrl)
    private val predictor = model.newPredictor()
    private val predictorMutex = Mutex()

    override val dimension: Int by lazy { runBlocking { embed("determine embedding size").size } }

    override suspend fun embed(text: String): FloatArray = predict("query: $text")

    override suspend fun embedPassage(text: String): FloatArray = predict("passage: $text")

    private suspend fun predict(text: String): FloatArray = predictorMutex.withLock { predictor.predict(text) }

    override fun close() {
        runBlocking {
            predictorMutex.withLock {
                predictor.close()
                model.close()
            }
        }
    }

    fun interface Factory {
        fun create(modelUrl: String): OnnxLocalEmbeddingService
    }

    companion object {
        const val DEFAULT_MODEL_URL = "djl://ai.djl.huggingface.onnxruntime/intfloat/multilingual-e5-small"

        var embeddingServiceFactory: Factory? = null

        fun create(modelUrl: String = DEFAULT_MODEL_URL) = embeddingServiceFactory?.create(modelUrl) ?: OnnxLocalEmbeddingService(modelUrl)

        private fun loadModel(modelUrl: String): ZooModel<String, FloatArray> =
            ModelZoo.loadModel(
                builder()
                    .setTypes(String::class.java, FloatArray::class.java)
                    .optModelUrls(modelUrl)
                    .optTranslatorFactory(TextEmbeddingTranslatorFactory())
                    .build(),
            )
    }
}
