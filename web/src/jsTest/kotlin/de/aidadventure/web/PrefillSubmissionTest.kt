package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.PrefillAccepted
import de.aidadventure.web.api.PrefillRequest
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.api.TurnRequest
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.await

class PrefillSubmissionTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `debounce sends only newest complete input`() = GlobalScope.promise<Unit> {
        val scheduler = ManualPrefillScheduler()
        val api = RecordingAdventureApi()
        val prefill = PrefillSubmission(api, scheduler)

        prefill.changed(session(), scenario(), "live-1", "open")
        prefill.changed(session(), scenario(), "live-1", "open door")
        scheduler.runAll()
        nextTick()

        assertEquals(listOf("open door"), api.prefilledActions)
        assertEquals(listOf(2L), api.prefillRevisions)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `turn start cancels queued prefill`() = GlobalScope.promise<Unit> {
        val scheduler = ManualPrefillScheduler()
        val api = RecordingAdventureApi()
        val prefill = PrefillSubmission(api, scheduler)

        prefill.changed(session(), scenario(), "live-1", "open")
        prefill.turnStarted()
        scheduler.runAll()

        assertTrue(api.prefilledActions.isEmpty())
    }

    @Test
    fun `clearing input cancels queued prefill`() {
        val scheduler = ManualPrefillScheduler()
        val api = RecordingAdventureApi()
        val prefill = PrefillSubmission(api, scheduler)

        prefill.changed(session(), scenario(), "live-1", "open")
        prefill.changed(session(), scenario(), "live-1", "")
        scheduler.runAll()

        assertTrue(api.prefilledActions.isEmpty())
    }

    private fun session() = GameSession(
        id = "stored-1",
        scenarioId = "scenario-1",
        title = "Test adventure",
        modelId = "test-model",
        messages = emptyList(),
        createdAt = 1,
        updatedAt = 1,
        llamaCppPort = 8081,
    )

    private fun scenario() = Scenario("scenario-1", "test.md", "# Test", 1)

    private suspend fun nextTick() = kotlin.js.Promise<Unit> { resolve, _ ->
        kotlinx.browser.window.setTimeout({ resolve(Unit) }, 0)
    }.await()
}

private class ManualPrefillScheduler : PrefillScheduler {
    private var task: (() -> Unit)? = null

    override fun schedule(delayMs: Int, task: () -> Unit): Any {
        require(delayMs == 150)
        this.task = task
        return Any()
    }

    override fun cancel(handle: Any) {
        task = null
    }

    fun runAll() {
        task?.also { task = null }?.invoke()
    }
}

private class RecordingAdventureApi : AdventureApi {
    val prefilledActions = mutableListOf<String>()
    val prefillRevisions = mutableListOf<Long>()

    override suspend fun models(port: Int): List<String> = emptyList()

    override suspend fun prefill(request: PrefillRequest): PrefillAccepted {
        prefilledActions += request.playerAction
        prefillRevisions += request.revision
        return PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
    }

    override suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics = error("unused")
}
