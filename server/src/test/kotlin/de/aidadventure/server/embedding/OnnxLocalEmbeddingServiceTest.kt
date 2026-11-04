package de.aidadventure.server.embedding

import ai.djl.inference.Predictor
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ModelZoo
import ai.djl.repository.zoo.ZooModel
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class OnnxLocalEmbeddingServiceTest {
    @Test
    fun `computes dimension once and closes model`() =
        mockkStatic(Criteria::class, ModelZoo::class) {
            runTest {
                // arrange
                val criteria = mockk<Criteria<String, FloatArray>>()
                val criteriaBuilder = mockCriteriaBuilderFor(criteria)
                val predictor = mockPredictor()
                val model = mockModel(predictor)
                every { ModelZoo.loadModel(criteria) } returns model

                val service = OnnxLocalEmbeddingService.create(modelUrl = "custom-model")

                // act
                val result = service.embed("hello world")
                service.embedPassage("hello world")

                service.close()

                // assert
                assertEquals(3, service.dimension)
                assertContentEquals(floatArrayOf(0.1f, 0.2f, 0.3f), result)
                verify(exactly = 1) { Criteria.builder() }
                verify(exactly = 1) { criteriaBuilder.optModelUrls("custom-model") }
                verify(exactly = 1) { predictor.predict("query: determine embedding size") }
                verify(exactly = 1) { predictor.predict("query: hello world") }
                verify(exactly = 1) { predictor.predict("passage: hello world") }
                verify(exactly = 1) { predictor.close() }
                verify(exactly = 1) { model.close() }
            }
        }

    @Test
    fun `uses supported huggingface onnx zoo by default`() =
        mockkStatic(Criteria::class, ModelZoo::class) {
            runTest {
                val criteria = mockk<Criteria<String, FloatArray>>()
                val criteriaBuilder = mockCriteriaBuilderFor(criteria)
                val predictor = mockPredictor()
                val model = mockModel(predictor)
                every { ModelZoo.loadModel(criteria) } returns model
                every { criteriaBuilder.optModelUrls(OnnxLocalEmbeddingService.DEFAULT_MODEL_URL) } returns criteriaBuilder

                OnnxLocalEmbeddingService.create().close()

                verify(exactly = 1) {
                    criteriaBuilder.optModelUrls(OnnxLocalEmbeddingService.DEFAULT_MODEL_URL)
                }
            }
        }

    private fun mockModel(predictor: Predictor<String, FloatArray>): ZooModel<String, FloatArray> {
        val model = mockk<ZooModel<String, FloatArray>>()
        every { model.close() } just Runs
        every { model.newPredictor() } returns predictor
        return model
    }

    private fun mockPredictor(): Predictor<String, FloatArray> {
        val predictor = mockk<Predictor<String, FloatArray>>()
        every { predictor.predict("determine embedding size") } returns
            floatArrayOf(
                1.0f,
                2.0f,
                3.0f,
            )
        every { predictor.predict("query: determine embedding size") } returns floatArrayOf(1.0f, 2.0f, 3.0f)
        every { predictor.predict("hello world") } returns floatArrayOf(0.1f, 0.2f, 0.3f)
        every { predictor.predict("query: hello world") } returns floatArrayOf(0.1f, 0.2f, 0.3f)
        every { predictor.predict("passage: hello world") } returns floatArrayOf(0.1f, 0.2f, 0.3f)
        every { predictor.close() } just Runs
        return predictor
    }

    private fun mockCriteriaBuilderFor(criteria: Criteria<String, FloatArray>): Criteria.Builder<String, FloatArray> {
        val criteriaBuilder = mockk<Criteria.Builder<String, FloatArray>>()
        every { Criteria.builder() } returns criteriaBuilder
        every {
            criteriaBuilder.setTypes(
                String::class.java,
                FloatArray::class.java,
            )
        } returns criteriaBuilder
        every { criteriaBuilder.optModelUrls("custom-model") } returns criteriaBuilder
        every { criteriaBuilder.optModelUrls(OnnxLocalEmbeddingService.DEFAULT_MODEL_URL) } returns criteriaBuilder
        every { criteriaBuilder.optTranslatorFactory(any()) } returns criteriaBuilder
        every { criteriaBuilder.build() } returns criteria
        return criteriaBuilder
    }
}
