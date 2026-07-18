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

    private class CountingEmbeddingService : EmbeddingService {
        override val dimension = 2
        var calls = 0

        override suspend fun embed(text: String): FloatArray {
            calls++
            return floatArrayOf(1f, 0f)
        }
    }
}
