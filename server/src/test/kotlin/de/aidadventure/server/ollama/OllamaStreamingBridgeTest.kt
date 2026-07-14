package de.aidadventure.server.ollama

import de.aidadventure.server.model.TurnMetrics
import de.aidadventure.server.model.TurnStreamEvent
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OllamaStreamingBridgeTest {
    @Test
    fun `callback bridge delivers every token and done event when callbacks outpace its buffer`() = runBlocking {
        val bridgeBufferCapacity = 2
        val expected = buildList<TurnStreamEvent> {
            add(TurnStreamEvent.Token("first"))
            repeat(bridgeBufferCapacity + 1) { index ->
                add(TurnStreamEvent.Token("token-$index"))
            }
            add(TurnStreamEvent.Done(TurnMetrics(null, 12, null)))
        }
        val firstTokenCollected = CountDownLatch(1)
        val overflowAttempted = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()

        val stream = callbackFlow<TurnStreamEvent> {
            thread(start = true) {
                sendOrClose(expected.first())
                firstTokenCollected.await()
                expected.drop(1).dropLast(2).forEach(::sendOrClose)
                overflowAttempted.complete(Unit)
                sendOrClose(expected[expected.lastIndex - 1])
                sendOrClose(expected.last())
                close()
            }
            awaitClose { }
        }.buffer(bridgeBufferCapacity)
        val collection = async {
            runCatching {
                stream
                    .onEach { event ->
                        if (event == expected.first()) {
                            firstTokenCollected.countDown()
                            releaseCollector.await()
                        }
                        delay(1)
                    }
                    .toList()
            }
        }

        overflowAttempted.await()
        releaseCollector.complete(Unit)
        val result = collection.await()

        assertNull(result.exceptionOrNull())
        assertEquals(expected, result.getOrThrow())
    }
}
