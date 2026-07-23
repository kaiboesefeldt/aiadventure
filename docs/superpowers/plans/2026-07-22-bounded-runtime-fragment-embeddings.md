# Bounded Runtime-Fragment Embeddings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Keep runtime-fragment retrieval available for long visible messages while giving the latest player action and game-master response equal semantic weight.

**Architecture:** A bounded-text embedding component recursively splits an oversized individual message and combines child vectors by character length. The resolver embeds player and game-master messages separately, averages those vectors equally, L2-normalizes the result, then queries the existing Lucene registry.

**Tech Stack:** Kotlin/JVM 21, kotlinx.coroutines, kotlin.test, JUnit Jupiter, existing DJL EmbeddingService, Lucene cosine vectors.

## Global Constraints

- Set maxEmbeddingCharacters to exactly 512 for the runtime tokenizer; it is an application limit, not ONNX metadata.
- Split oversized text nearest its midpoint at whitespace; if none exists, split at the midpoint so recursion terminates.
- Combine child vectors by original child character counts; do not tokenize or add token weighting.
- Embed the last visible player and game-master messages separately, then give their vectors exactly equal weight.
- L2-normalize the final query before the existing Lucene cosine query.
- Do not change matchText indexing, prompt construction, persistence, resolver timing, or the llama.cpp cache contract.
- Do not log text, chunks, or vectors; existing request-ID-only failure handling remains unchanged.

---

### Task 1: Add bounded single-text embeddings

**Files:**

- Create: server/src/main/kotlin/de/aidadventure/server/embedding/BoundedTextEmbedding.kt
- Create: server/src/test/kotlin/de/aidadventure/server/embedding/BoundedTextEmbeddingTest.kt

**Interfaces:**

- Consumes: EmbeddingService.embed(text: String): FloatArray.
- Produces: class BoundedTextEmbedding(private val embedding: EmbeddingService, private val maxEmbeddingCharacters: Int = MAX_EMBEDDING_CHARACTERS) with suspend fun embed(text: String): FloatArray.
- Produces: BoundedTextEmbedding.MAX_EMBEDDING_CHARACTERS = 4_000.

- [ ] **Step 1: Write the failing component tests**

Create BoundedTextEmbeddingTest.kt with a recording EmbeddingService fake mapping exact inputs to vectors. Add these cases:

~~~kotlin
@Test
fun embedsATextWithinTheLimitOnce() = runTest {
    val embedding = RecordingEmbedding(mapOf("short" to floatArrayOf(2f, 3f)))

    assertContentEquals(floatArrayOf(2f, 3f), BoundedTextEmbedding(embedding, 5).embed("short"))
    assertEquals(listOf("short"), embedding.inputs)
}

@Test
fun combinesMidpointWordBoundaryHalvesByCharacterLength() = runTest {
    val embedding = RecordingEmbedding(
        mapOf("one two " to floatArrayOf(2f, 0f), "three four" to floatArrayOf(0f, 4f)),
    )

    val vector = BoundedTextEmbedding(embedding, 10).embed("one two three four")

    assertContentEquals(floatArrayOf(8f / 9f, 20f / 9f), vector)
    assertEquals(listOf("one two ", "three four"), embedding.inputs)
}

@Test
fun splitsNoWhitespaceTextAtItsMidpoint() = runTest {
    val embedding = RecordingEmbedding(mapOf("abcd" to floatArrayOf(1f), "efgh" to floatArrayOf(3f)))

    assertContentEquals(floatArrayOf(2f), BoundedTextEmbedding(embedding, 4).embed("abcdefgh"))
    assertEquals(listOf("abcd", "efgh"), embedding.inputs)
}
~~~

Add a recursive case where "aa bb cc" at limit 3 reaches leaves "aa ", "bb ", and "cc"; assert its first coordinate is 11f / 4f. Add an empty-input case asserting IllegalArgumentException before the fake receives input.

- [ ] **Step 2: Run the new tests and verify they fail**

Run:

~~~bash
./gradlew --no-daemon :server:test --tests de.aidadventure.server.embedding.BoundedTextEmbeddingTest --console=plain
~~~

Expected: compilation failure because BoundedTextEmbedding does not exist.

- [ ] **Step 3: Implement the bounded recursive mean**

Create BoundedTextEmbedding.kt:

~~~kotlin
package de.aidadventure.server.embedding

import de.aidadventure.server.EmbeddingService
import kotlin.math.abs

class BoundedTextEmbedding(
    private val embedding: EmbeddingService,
    private val maxEmbeddingCharacters: Int = MAX_EMBEDDING_CHARACTERS,
) {
    init {
        require(maxEmbeddingCharacters >= 1) { "maxEmbeddingCharacters must be positive" }
    }

    suspend fun embed(text: String): FloatArray {
        require(text.isNotEmpty()) { "text must not be empty" }
        if (text.length <= maxEmbeddingCharacters) return embedding.embed(text)
        val (left, right) = split(text)
        val leftVector = embed(left)
        val rightVector = embed(right)
        require(leftVector.size == rightVector.size) { "Embedding vector dimensions differ" }
        val totalLength = left.length + right.length
        return FloatArray(leftVector.size) { index ->
            (leftVector[index] * left.length + rightVector[index] * right.length) / totalLength
        }
    }

    private fun split(text: String): Pair<String, String> {
        val midpoint = text.length / 2
        val splitAt = text.indices
            .filter { it in 1 until text.length && text[it - 1].isWhitespace() }
            .minByOrNull { abs(it - midpoint) }
            ?: midpoint
        return text.substring(0, splitAt) to text.substring(splitAt)
    }

    companion object {
        const val MAX_EMBEDDING_CHARACTERS = 4_000
    }
}
~~~

Preserve split whitespace so the two child lengths sum to the original length.

- [ ] **Step 4: Run the focused component tests and verify they pass**

Run:

~~~bash
./gradlew --no-daemon :server:test --tests de.aidadventure.server.embedding.BoundedTextEmbeddingTest --console=plain
~~~

Expected: all component cases pass.

- [ ] **Step 5: Commit the component**

~~~bash
git add server/src/main/kotlin/de/aidadventure/server/embedding/BoundedTextEmbedding.kt server/src/test/kotlin/de/aidadventure/server/embedding/BoundedTextEmbeddingTest.kt
git commit -m "feat(embedding): bound long text embeddings"
~~~

### Task 2: Build an equal-weight runtime-fragment query

**Files:**

- Modify: server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentIndexRegistry.kt
- Modify: server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentResolver.kt
- Modify: server/src/test/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentResolverTest.kt

**Interfaces:**

- Consumes: BoundedTextEmbedding.embed(text: String): FloatArray.
- Produces: internal RuntimeFragmentIndex, implemented by RuntimeFragmentIndexRegistry, with the registry's current prepare and query signatures.
- Produces: the existing public DefaultRuntimeFragmentResolver(embedding) constructor plus an internal DefaultRuntimeFragmentResolver(embedding, indexes: RuntimeFragmentIndex) test constructor.

- [ ] **Step 1: Write failing resolver tests that inspect the query vector**

Replace the current newline-concatenation assertion with a fake index that records queryVector. Its fake prepare must call embedding.embed for every definition match text, matching production preparation. Use this fixture:

~~~kotlin
val embedding = RecordingEmbeddingService(
    mapOf(
        "player" to floatArrayOf(3f, 0f),
        "answer" to floatArrayOf(0f, 4f),
        "match" to floatArrayOf(1f, 1f),
    ),
)
val index = RecordingIndex(RuntimeFragmentDefinition("match", "fragment"))
val resolver = DefaultRuntimeFragmentResolver(embedding, index)

val resolved = resolver.resolve(
    listOf(ChatEntry("player", "player"), ChatEntry("game-master", "answer")),
    listOf(RuntimeFragmentDefinition("match", "fragment")),
)

assertEquals(RuntimeFragmentDefinition("match", "fragment"), resolved)
assertEquals(listOf("match", "player", "answer"), embedding.inputs)
assertContentEquals(floatArrayOf(0.6f, 0.8f), index.queryVectors.single())
~~~

Add a second test with a player input longer than MAX_EMBEDDING_CHARACTERS, mapped recursive leaves, and a short answer. Assert no concatenated player plus newline plus answer input was embedded and that the recorded query is the normalized equal outer average.

- [ ] **Step 2: Run the resolver test and verify it fails**

Run:

~~~bash
./gradlew --no-daemon :server:test --tests de.aidadventure.server.runtimefragments.RuntimeFragmentResolverTest --console=plain
~~~

Expected: compilation failure because the injectable RuntimeFragmentIndex does not exist and the resolver still embeds a newline-concatenated query.

- [ ] **Step 3: Add the narrow registry seam and compose the query**

At the top of RuntimeFragmentIndexRegistry.kt, add and implement:

~~~kotlin
internal interface RuntimeFragmentIndex {
    suspend fun prepare(definitions: List<RuntimeFragmentDefinition>, embedding: EmbeddingService)

    suspend fun query(
        definitions: List<RuntimeFragmentDefinition>,
        embedding: EmbeddingService,
        queryVector: FloatArray,
    ): RuntimeFragmentDefinition?
}
~~~

Change RuntimeFragmentIndexRegistry to implement this interface. Keep DefaultRuntimeFragmentResolver's existing public one-argument constructor. Add an internal two-argument constructor accepting RuntimeFragmentIndex, and have the public constructor delegate to it with RuntimeFragmentIndexRegistry(). In RuntimeFragmentResolver.kt, use RuntimeFragmentIndex for the private constructor property, construct a BoundedTextEmbedding from the existing embedding service, and replace the concatenated query with:

~~~kotlin
val playerVector = boundedEmbedding.embed(lastPlayer.content)
val gameMasterVector = boundedEmbedding.embed(lastGameMaster.content)
require(playerVector.size == gameMasterVector.size) { "Embedding vector dimensions differ" }
val queryVector = l2Normalize(
    FloatArray(playerVector.size) { index -> (playerVector[index] + gameMasterVector[index]) / 2f },
)
prepare(definitions)
return indexes.query(definitions, embedding, queryVector)
~~~

Add:

~~~kotlin
private fun l2Normalize(vector: FloatArray): FloatArray {
    val magnitude = kotlin.math.sqrt(vector.sumOf { value -> value.toDouble() * value }.toFloat())
    require(magnitude > 0f) { "Embedding vector must not be zero" }
    return FloatArray(vector.size) { index -> vector[index] / magnitude }
}
~~~

Keep index preparation unchanged: scenario match texts are still embedded directly through the original EmbeddingService. Add no logging and no new exception handling.

- [ ] **Step 4: Run focused resolver and registry tests and verify they pass**

Run:

~~~bash
./gradlew --no-daemon :server:test --tests de.aidadventure.server.runtimefragments.RuntimeFragmentResolverTest --tests de.aidadventure.server.runtimefragments.RuntimeFragmentIndexRegistryTest --console=plain
~~~

Expected: all selected tests pass, including existing lifecycle and trace coverage.

- [ ] **Step 5: Commit the resolver integration**

~~~bash
git add server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentIndexRegistry.kt server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentResolver.kt server/src/test/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentResolverTest.kt
git commit -m "feat(fragments): bound retrieval query embeddings"
~~~

### Task 3: Full verification and handoff

**Files:**

- Verify only: server/src/main/kotlin/de/aidadventure/server/embedding/BoundedTextEmbedding.kt
- Verify only: server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentResolver.kt
- Verify only: server/src/test/kotlin/de/aidadventure/server/embedding/BoundedTextEmbeddingTest.kt
- Verify only: server/src/test/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentResolverTest.kt

**Interfaces:**

- Consumes: completed Tasks 1 and 2.
- Produces: fresh repository-wide verification evidence; no source changes.

- [ ] **Step 1: Inspect the final diff and commit range**

Run:

~~~bash
git status --short
git diff main...HEAD --check
git log --oneline main..HEAD
~~~

Expected: the committed specification/design and two feature commits are present, with no whitespace errors or uncommitted files.

- [ ] **Step 2: Run the repository verification gate**

Run:

~~~bash
./gradlew check
~~~

Expected: exit code 0, including server and Kotlin/JS verification with no excluded dependency-install tasks.

- [ ] **Step 3: Report verified results and runtime state**

Report the exact command outcomes, resulting commit IDs, and that this work starts no server or browser process.
