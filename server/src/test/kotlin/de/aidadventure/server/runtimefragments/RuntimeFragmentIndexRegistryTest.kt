package de.aidadventure.server.runtimefragments

import de.aidadventure.server.EmbeddingService
import de.aidadventure.server.model.RuntimeFragmentDefinition
import de.aidadventure.server.testutils.TestClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class RuntimeFragmentIndexRegistryTest {
    @Test
    fun sameOrderedDefinitionsReuseOnePreparedIndex() = runTest {
        val registry = RuntimeFragmentIndexRegistry()
        val embedding = CountingEmbeddingService()
        val definitions = listOf(RuntimeFragmentDefinition("match", "fragment"))

        registry.prepare(definitions, embedding)
        registry.prepare(definitions, embedding)

        assertEquals(1, registry.indexCount)
        assertEquals(1, embedding.calls)
        registry.close()
    }

    @Test
    fun idleIndexesAreEvictedBeforeNewIndexIsConstructed() = runTest {
        val clock = TestClock(Instant.EPOCH)
        val registry = RuntimeFragmentIndexRegistry(clock = clock)
        val embedding = CountingEmbeddingService()

        registry.prepare(listOf(RuntimeFragmentDefinition("one", "one")), embedding)
        clock.advance(16.minutes)
        registry.prepare(listOf(RuntimeFragmentDefinition("two", "two")), embedding)

        assertEquals(1, registry.indexCount)
        registry.close()
    }

    @Test
    fun overflowEvictsLeastRecentlyAccessedIndexWhenClockDoesNotAdvance() = runTest {
        val clock = TestClock(Instant.EPOCH)
        val registry = RuntimeFragmentIndexRegistry(clock = clock, maxIndexes = 2)
        val embedding = CountingEmbeddingService()
        val first = listOf(RuntimeFragmentDefinition("first", "first"))
        val second = listOf(RuntimeFragmentDefinition("second", "second"))
        val third = listOf(RuntimeFragmentDefinition("third", "third"))

        registry.prepare(first, embedding)
        registry.prepare(second, embedding)
        registry.query(first, embedding, floatArrayOf(1f, 0f))
        registry.prepare(third, embedding)
        registry.prepare(first, embedding)
        registry.prepare(second, embedding)

        assertEquals(4, embedding.calls)
        registry.close()
    }

    @Test
    fun `query traces selected fragment and similarity only when trace is enabled`() = runTest {
        val trace = RecordingTraceSink(isTraceEnabled = true)
        val registry = RuntimeFragmentIndexRegistry(traceSink = trace)
        val embedding = CountingEmbeddingService()
        val definition = RuntimeFragmentDefinition("match", "fragment\\nwith tab\\t")

        registry.query(listOf(definition), embedding, floatArrayOf(1f, 0f))
        registry.query(emptyList(), embedding, floatArrayOf(1f, 0f))

        assertEquals(listOf(TracedResult(definition.fragment, 1.0f)), trace.results)
        registry.close()

        val disabledTrace = RecordingTraceSink(isTraceEnabled = false)
        val disabledRegistry = RuntimeFragmentIndexRegistry(traceSink = disabledTrace)
        disabledRegistry.query(listOf(definition), embedding, floatArrayOf(1f, 0f))

        assertEquals(emptyList(), disabledTrace.results)
        disabledRegistry.close()
    }

    private class CountingEmbeddingService : EmbeddingService {
        override val dimension = 2
        var calls = 0

        override suspend fun embed(text: String): FloatArray {
            calls++
            return floatArrayOf(1f, 0f)
        }
    }

    private class RecordingTraceSink(override val isTraceEnabled: Boolean) : RuntimeFragmentTraceSink {
        val results = mutableListOf<TracedResult>()

        override fun selected(fragment: String, similarity: Float) {
            results += TracedResult(fragment, similarity)
        }
    }

    private data class TracedResult(val fragment: String, val similarity: Float)
}
